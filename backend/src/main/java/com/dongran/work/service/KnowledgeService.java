package com.dongran.work.service;

import com.dongran.work.exception.ApiException;
import com.dongran.work.infrastructure.Database;
import com.dongran.work.repository.KnowledgeRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeService {
  private final KnowledgeRepository repository;
  private final PdfTextExtractionService pdfExtractor;
  private final Database db;
  private final ProjectService projects;
  private final KnowledgeRetrievalService retrieval;
  private final com.dongran.work.repository.KnowledgeIndexRepository index;

  public KnowledgeService(
      KnowledgeRepository repository,
      PdfTextExtractionService pdfExtractor,
      Database db,
      ProjectService projects,
      KnowledgeRetrievalService retrieval,
      com.dongran.work.repository.KnowledgeIndexRepository index) {
    this.repository = repository;
    this.pdfExtractor = pdfExtractor;
    this.db = db;
    this.projects = projects;
    this.retrieval = retrieval;
    this.index = index;
  }

  public List<Map<String, Object>> list(String projectId) {
    return repository.findVisible(projectId);
  }

  public Map<String, Object> get(String id) {
    return repository.findRequired(id);
  }

  @Transactional
  public Map<String, Object> save(String id, String projectId, String name, String content) {
    if (projectId != null && !projectId.isBlank()) projects.get(projectId);
    else projectId = null;
    if (name == null
        || name.isBlank()
        || name.length() > 200
        || content == null
        || content.isBlank()
        || content.length() > 1_000_000) throw ApiException.bad("资料名称或内容不合法，最多 100 万字符。");
    if (id == null) {
      id = Database.id();
      repository.insert(
          id,
          projectId,
          name,
          content,
          ProjectService.hash(content.getBytes(StandardCharsets.UTF_8)),
          Database.now(),
          Database.now());
    } else {
      if (get(id).get("sourceName") != null)
        throw ApiException.conflict("导入文件保留原始版本，不能直接修改提取文本；请删除后重新导入。");
      repository.update(
          name,
          content,
          ProjectService.hash(content.getBytes(StandardCharsets.UTF_8)),
          Database.now(),
          id);
    }
    repository.deleteIndex(id);
    repository.insertIndex(id, name, content);
    retrieval.replaceText(id, name, content);
    return get(id);
  }

  @Transactional
  public void delete(String id) {
    get(id);
    repository.deleteIndex(id);
    index.deleteChunks(id);
    repository.delete(id);
  }

  public List<Map<String, Object>> search(String projectId, String query) {
    if (query == null || query.isBlank()) return list(projectId);
    @SuppressWarnings("unchecked")
    var results = (List<Map<String, Object>>) retrieval.search(projectId, query, 20).get("results");
    return results;
  }

  @Transactional
  public Map<String, Object> upload(String projectId, MultipartFile file) throws IOException {
    if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024)
      throw ApiException.bad("文件为空或超过 10 MB。");
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("文档").replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1);
    byte[] bytes = file.getBytes();
    String content;
    PdfTextExtractionService.Result extraction = null;
    String extension =
        name.contains(".")
            ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
            : "";
    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      extraction = pdfExtractor.extract(bytes);
      content = extraction.content();
    } else if (Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
        .contains(extension)) {
      content = office(bytes, name);
    } else {
      if (!Set.of(
              "txt",
              "md",
              "markdown",
              "java",
              "js",
              "ts",
              "tsx",
              "jsx",
              "json",
              "yaml",
              "yml",
              "xml",
              "html",
              "css",
              "csv",
              "sql",
              "py",
              "rs",
              "go",
              "sh",
              "log")
          .contains(extension))
        throw ApiException.bad("支持 PDF、Word、Excel、PowerPoint、OpenDocument、RTF、Markdown 和文本代码文件。");
      for (byte b : bytes) if (b == 0) throw ApiException.bad("文件不是 UTF-8 文本。");
      try {
        content =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
      } catch (java.nio.charset.CharacterCodingException e) {
        throw ApiException.bad("文本文件需要 UTF-8 编码，请转换编码后导入。");
      }
    }
    if (name.isBlank() || name.length() > 200) throw ApiException.bad("文件名为空或超过 200 字。");
    Map<String, Object> saved;
    if (content.isBlank()) {
      if (projectId != null && !projectId.isBlank()) projects.get(projectId);
      else projectId = null;
      String id = Database.id();
      repository.insert(
          id, projectId, name, "", ProjectService.hash(bytes), Database.now(), Database.now());
      saved = get(id);
    } else saved = save(null, projectId, name, content);
    repository.source(
        String.valueOf(saved.get("id")),
        name,
        extension.equals("pdf")
            ? "application/pdf"
            : extension.equals("docx")
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/octet-stream",
        bytes);
    if (extraction != null)
      repository.extraction(
          String.valueOf(saved.get("id")), extraction.status(), extraction.warning());
    return get(String.valueOf(saved.get("id")));
  }

  public Map<String, Object> preview(String id) {
    var row = get(id);
    var result = new LinkedHashMap<String, Object>();
    result.put("id", id);
    result.put("name", row.get("name"));
    result.put("mime", row.get("sourceMime"));
    result.put("format", "text");
    result.put("content", row.get("content"));
    result.put("extractionStatus", row.get("extractionStatus"));
    result.put("extractionWarning", row.get("extractionWarning"));
    if (row.get("sourceName") != null) {
      result.put("format", previewFormat(String.valueOf(row.get("sourceName"))));
      result.put("sourceUrl", "/api/knowledge/" + id + "/source");
    }
    result.put("projectId", row.get("projectId"));
    result.put("sourceName", row.get("sourceName"));
    return result;
  }

  public Map<String, Object> fragment(String id, String chunkId) {
    var doc = get(id);
    String text = String.valueOf(doc.get("content"));
    int position = 0;
    for (var chunk : index.chunks(id)) {
      int end = Math.min(text.length(), position + 1200);
      if (end < text.length()) {
        int line = text.lastIndexOf('\n', end);
        if (line > position + 700) end = line + 1;
      }
      if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
      if (chunkId.equals(chunk.get("id"))) {
        if (!text.substring(position, end).equals(chunk.get("content")))
          throw ApiException.conflict("片段已改变，请重新检索。");
        return Map.of(
            "chunkId",
            chunkId,
            "documentId",
            id,
            "start",
            position,
            "end",
            end,
            "ordinal",
            chunk.get("ordinal"),
            "content",
            chunk.get("content"));
      }
      if (end == text.length()) break;
      position = Math.max(position + 1, end - 160);
      if (Character.isLowSurrogate(text.charAt(position))) position++;
    }
    throw ApiException.missing("片段已失效或不属于此文档，请重新检索。");
  }

  public byte[] source(String id) {
    get(id);
    byte[] bytes = repository.sourceBytes(id);
    if (bytes == null) throw ApiException.missing("此资料没有原始文件。");
    return bytes;
  }

  @Transactional
  public Map<String, Object> reextract(String id) {
    var old = get(id);
    if (!"application/pdf".equals(old.get("sourceMime"))) throw ApiException.bad("仅 PDF 支持重新提取。");
    var result = pdfExtractor.extract(source(id));
    repository.update(
        String.valueOf(old.get("name")),
        result.content(),
        ProjectService.hash(result.content().getBytes(StandardCharsets.UTF_8)),
        Database.now(),
        id);
    repository.extraction(id, result.status(), result.warning());
    repository.deleteIndex(id);
    repository.insertIndex(id, String.valueOf(old.get("name")), result.content());
    retrieval.replaceText(id, String.valueOf(old.get("name")), result.content());
    return preview(id);
  }

  private String previewFormat(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".pdf")) return "pdf";
    if (n.endsWith(".docx")) return "docx";
    if (n.endsWith(".md") || n.endsWith(".markdown")) return "markdown";
    return "text";
  }

  private String office(byte[] bytes, String name) {
    try {
      var metadata = new Metadata();
      metadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY, name);
      var parser = new org.apache.tika.parser.AutoDetectParser();
      var context = new org.apache.tika.parser.ParseContext();
      context.set(
          org.apache.tika.extractor.EmbeddedDocumentExtractor.class,
          new org.apache.tika.extractor.EmbeddedDocumentExtractor() {
            public boolean shouldParseEmbedded(Metadata m) {
              return false;
            }

            public void parseEmbedded(
                InputStream in,
                org.xml.sax.ContentHandler handler,
                Metadata m,
                boolean outputHtml) {}
          });
      var handler = new org.apache.tika.sax.BodyContentHandler(1_000_000);
      parser.parse(new ByteArrayInputStream(bytes), handler, metadata, context);
      return handler.toString();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw ApiException.bad("无法解析该文档格式，请检查文件是否损坏或受密码保护。");
    }
  }
}
