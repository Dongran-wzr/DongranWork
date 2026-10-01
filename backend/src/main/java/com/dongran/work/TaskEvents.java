package com.dongran.work;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TaskEvents {
    private final Database db;
    public TaskEvents(Database db){this.db=db;}
    public void emit(String taskId,String type,Object payload){db.jdbc.update("INSERT INTO events(task_id,type,data,created_at) VALUES (?,?,?,?)",taskId,type,db.json(payload),Database.now());}
    public List<Map<String,Object>> since(String taskId,long id){return db.jdbc.queryForList("SELECT id,type,data,created_at AS createdAt FROM events WHERE task_id=? AND id>? ORDER BY id LIMIT 200",taskId,id).stream().map(row->{row.put("data",db.object(String.valueOf(row.get("data"))));return row;}).toList();}
    public void status(String taskId,String status,String error){db.jdbc.update("UPDATE tasks SET status=?,error=?,updated_at=? WHERE id=?",status,error,Database.now(),taskId);emit(taskId,"status",Map.of("status",status,"error",error==null?"":error));}
    public void message(String taskId,String role,String agent,String content){db.jdbc.update("INSERT INTO messages(task_id,role,agent,content,created_at) VALUES (?,?,?,?,?)",taskId,role,agent,content,Database.now());emit(taskId,"message",Map.of("role",role,"agent",agent,"content",content));}
}
