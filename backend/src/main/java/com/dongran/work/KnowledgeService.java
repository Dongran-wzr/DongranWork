package com.dongran.work;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {
    private final Database db;
    private final ProjectService projects;
    public KnowledgeService(Database db,ProjectService projects){this.db=db;this.projects=projects;}
    public List<Map<String,Object>> list(String projectId){return db.jdbc.queryForList("SELECT id,project_id AS projectId,name,length(content) AS characters,created_at AS createdAt,updated_at AS updatedAt FROM knowledge WHERE project_id IS NULL OR project_id=? ORDER BY updated_at DESC LIMIT 200",projectId);}
    public Map<String,Object> get(String id){return db.one("SELECT id,project_id AS projectId,name,content,created_at AS createdAt,updated_at AS updatedAt FROM knowledge WHERE id=?",id);}
    @Transactional public Map<String,Object> save(String id,String projectId,String name,String content){
        if(projectId!=null&&!projectId.isBlank())projects.get(projectId);else projectId=null;
        if(name==null||name.isBlank()||name.length()>200||content==null||content.isBlank()||content.length()>1_000_000)throw ApiException.bad("资料名称或内容不合法，最多 100 万字符。");
        if(id==null){id=Database.id();db.jdbc.update("INSERT INTO knowledge VALUES (?,?,?,?,?,?,?)",id,projectId,name,content,ProjectService.hash(content.getBytes(StandardCharsets.UTF_8)),Database.now(),Database.now());}
        else{get(id);db.jdbc.update("UPDATE knowledge SET name=?,content=?,checksum=?,updated_at=? WHERE id=?",name,content,ProjectService.hash(content.getBytes(StandardCharsets.UTF_8)),Database.now(),id);}
        db.jdbc.update("DELETE FROM knowledge_fts WHERE id=?",id);db.jdbc.update("INSERT INTO knowledge_fts(id,name,content) VALUES (?,?,?)",id,name,content);return get(id);
    }
    @Transactional public void delete(String id){get(id);db.jdbc.update("DELETE FROM knowledge_fts WHERE id=?",id);db.jdbc.update("DELETE FROM knowledge WHERE id=?",id);}
    public List<Map<String,Object>> search(String projectId,String query){
        if(query==null||query.isBlank())return list(projectId);
        String term=query.trim();if(term.length()>200)throw ApiException.bad("查询关键词过长。");
        if(term.codePointCount(0,term.length())<3)return db.jdbc.queryForList("SELECT id,name,substr(content,1,600) AS excerpt,project_id AS projectId FROM knowledge WHERE (project_id IS NULL OR project_id=?) AND (instr(lower(name),lower(?))>0 OR instr(lower(content),lower(?))>0) LIMIT 20",projectId,term,term);
        String phrase="\""+term.replace("\"","\"\"")+"\"";
        return db.jdbc.queryForList("SELECT k.id,k.name,k.project_id AS projectId,snippet(knowledge_fts,2,'','',' … ',40) AS excerpt FROM knowledge_fts JOIN knowledge k ON k.id=knowledge_fts.id WHERE knowledge_fts MATCH ? AND (k.project_id IS NULL OR k.project_id=?) ORDER BY rank LIMIT 20",phrase,projectId);
    }
    public Map<String,Object> upload(String projectId,MultipartFile file) throws IOException{
        if(file.isEmpty()||file.getSize()>10*1024*1024)throw ApiException.bad("文件为空或超过 10 MB。");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("文档").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        byte[] bytes=file.getBytes();String content;
        if(name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            try(var pdf=Loader.loadPDF(bytes)){if(pdf.getNumberOfPages()>500)throw ApiException.bad("PDF 最多支持 500 页。");content=new PDFTextStripper().getText(pdf);}
        }else if(name.toLowerCase(Locale.ROOT).endsWith(".docx"))content=docx(bytes);
        else{
            String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
            if(!Set.of("txt","md","markdown","java","js","ts","tsx","jsx","json","yaml","yml","xml","html","css","csv","sql","py","rs","go","sh","log").contains(extension))throw ApiException.bad("支持文本、Markdown、代码、PDF 和 DOCX 文件。");
            for(byte b:bytes)if(b==0)throw ApiException.bad("文件不是 UTF-8 文本。");content=new String(bytes,StandardCharsets.UTF_8);
        }
        return save(null,projectId,name,content);
    }
    private String docx(byte[] bytes){
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            java.util.zip.ZipEntry entry;int count=0;
            while((entry=zip.getNextEntry())!=null){
                if(++count>2000)throw ApiException.bad("文档结构过于复杂。");
                if(!entry.getName().equals("word/document.xml"))continue;
                byte[] xml=zip.readNBytes(4_000_001);if(xml.length>4_000_000)throw ApiException.bad("文档解压后过大。");
                var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
                var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));var nodes=document.getElementsByTagName("w:p");var text=new StringBuilder();
                for(int i=0;i<nodes.getLength();i++)text.append(nodes.item(i).getTextContent()).append('\n');return text.toString();
            }
            throw ApiException.bad("DOCX 文档缺少正文。");
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.bad("无法解析 DOCX 文档。");}
    }
}
