package com.dongran.work;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GitService {
    private final ProjectService projects;
    private final CommandService commands;
    private final Path dataDirectory;
    public GitService(ProjectService projects,CommandService commands,Path dataDirectory){this.projects=projects;this.commands=commands;this.dataDirectory=dataDirectory;}
    private Map<String,Object> run(Path path,String... args){var command=new ArrayList<>(List.of("git","-c","safe.directory="+path,"-C",path.toString()));command.addAll(List.of(args));return commands.direct(path,command,60);}
    private String checked(Path path,String... args){var result=run(path,args);if(!Objects.equals(result.get("exitCode"),0))throw ApiException.bad("Git 操作失败："+result.get("output"));return String.valueOf(result.get("output"));}
    public Map<String,Object> status(String projectId){
        Path root=projects.root(projectId);var probe=run(root,"rev-parse","--is-inside-work-tree");
        if(!Objects.equals(probe.get("exitCode"),0))return Map.of("repository",false,"branch","","status","");
        return Map.of("repository",true,"branch",String.valueOf(run(root,"branch","--show-current").get("output")).trim(),"status",checked(root,"status","--short"),"diff",checked(root,"diff","--stat"));
    }
    public Map<String,Object> diff(String projectId){Path root=projects.root(projectId);return Map.of("unstaged",checked(root,"diff","--no-ext-diff","--no-textconv"),"staged",checked(root,"diff","--cached","--no-ext-diff","--no-textconv"));}
    public Map<String,Object> init(String projectId){Path root=projects.root(projectId);checked(root,"init","-b","main");return status(projectId);}
    public Map<String,Object> commit(String projectId,Map<String,Object> body){
        Path root=projects.root(projectId);String message=Database.required(body,"message",4000);
        Object value=body.get("paths");if(!(value instanceof List<?> paths)||paths.isEmpty()||paths.size()>200)throw ApiException.bad("请选择要提交的文件。");
        var args=new ArrayList<>(List.of("add","--"));
        for(Object item:paths){if(!(item instanceof String path)||path.isBlank())throw ApiException.bad("文件路径不合法。");projects.resolve(projectId,path,true);args.add(path);}
        checked(root,args.toArray(String[]::new));checked(root,"commit","-m",message);return status(projectId);
    }
    public Map<String,Object> fetch(String projectId){return Map.of("output",checked(projects.root(projectId),"fetch","--prune"));}
    public Map<String,Object> worktrees(String projectId){return Map.of("output",checked(projects.root(projectId),"worktree","list","--porcelain"));}
    public Map<String,Object> addWorktree(String projectId,Map<String,Object> body){
        Path root=projects.root(projectId);String branch=Database.required(body,"branch",120);String base=Database.text(body,"base","HEAD");
        if(branch.startsWith("-")||base.startsWith("-")||base.length()>120)throw ApiException.bad("分支名称不合法。");
        checked(root,"check-ref-format","--branch",branch);
        checked(root,"rev-parse","--verify",base+"^{commit}");
        Path directory=dataDirectory.resolve("worktrees").resolve(projectId).resolve(Database.id());
        try{Files.createDirectories(directory.getParent());}catch(Exception e){throw ApiException.bad("无法创建工作树目录。");}
        checked(root,"worktree","add","-b",branch,directory.toString(),base);
        return Map.of("path",directory.toString(),"branch",branch,"project",projects.open(directory.toString()));
    }
    public void removeWorktree(String projectId,String path){
        Path root=projects.root(projectId),candidate=Path.of(path).toAbsolutePath().normalize();
        Path managed=dataDirectory.resolve("worktrees").resolve(projectId).toAbsolutePath().normalize();
        if(!candidate.startsWith(managed)||candidate.equals(managed))throw ApiException.forbidden("只能移除由本项目创建的工作树。");
        if(!checked(candidate,"status","--porcelain").isBlank())throw ApiException.conflict("工作树包含未提交的更改，不能移除。");
        checked(root,"worktree","remove",candidate.toString());
    }
}
