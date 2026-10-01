package com.dongran.work;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class ApprovalService {
    private final Database db;private final TaskEvents events;
    private final ConcurrentMap<String,CompletableFuture<Boolean>> waiting=new ConcurrentHashMap<>();
    public ApprovalService(Database db,TaskEvents events){this.db=db;this.events=events;}
    public void ask(String taskId,String projectId,String action,Map<String,Object> arguments)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        String id=Database.id();var response=new CompletableFuture<Boolean>();waiting.put(id,response);
        try{
            db.jdbc.update("INSERT INTO approvals(id,task_id,project_id,action,arguments,status,created_at,updated_at) VALUES (?,?,?,?,?,'pending',?,?)",id,taskId,projectId,action,db.json(arguments),Database.now(),Database.now());
            events.status(taskId,"awaiting_approval",null);events.emit(taskId,"approval",Map.of("id",id,"action",action,"arguments",arguments));
            boolean allowed=response.get(10,TimeUnit.MINUTES);
            if(!allowed)throw ApiException.forbidden("用户拒绝了操作："+action);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            events.status(taskId,"running",null);
        }finally{
            waiting.remove(id);db.jdbc.update("UPDATE approvals SET status='expired',updated_at=? WHERE id=? AND status='pending'",Database.now(),id);
        }
    }
    public List<Map<String,Object>> list(String taskId){
        return db.jdbc.queryForList("SELECT * FROM approvals WHERE task_id=? ORDER BY created_at",taskId).stream().map(row->{row.put("arguments",db.object(String.valueOf(row.get("arguments"))));return row;}).toList();
    }
    public void resolve(String id,boolean allowed){
        var future=waiting.get(id);if(future==null)throw ApiException.conflict("此确认请求已经失效。");
        if(db.jdbc.update("UPDATE approvals SET status=?,updated_at=? WHERE id=? AND status='pending'",allowed?"approved":"denied",Database.now(),id)!=1)throw ApiException.conflict("此确认请求已处理。");
        future.complete(allowed);
    }
    @PreDestroy void close(){waiting.values().forEach(f->f.complete(false));}
}
