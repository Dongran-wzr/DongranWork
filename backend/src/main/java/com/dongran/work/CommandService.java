package com.dongran.work;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

@Service
public class CommandService {
    private final Database db;
    private final ProjectService projects;
    private final PreferenceService preferences;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(4);
    private final ConcurrentMap<String,Process> processes=new ConcurrentHashMap<>();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private volatile boolean closing;
    public CommandService(Database db,ProjectService projects,PreferenceService preferences){this.db=db;this.projects=projects;this.preferences=preferences;}
    public Map<String,Object> get(String id){return db.one("SELECT * FROM command_runs WHERE id=?",id);}
    public String start(String projectId,String taskId,String command,int timeout){
        if(closing)throw ApiException.conflict("应用正在退出。");
        if(command.isBlank()||command.length()>4000||timeout<1||timeout>3600)throw ApiException.bad("命令或超时不合法。");
        Path directory=projects.root(projectId);String id=Database.id();
        db.jdbc.update("INSERT INTO command_runs(id,project_id,task_id,command,status,created_at,updated_at) VALUES (?,?,?,?,'queued',?,?)",id,projectId,taskId,command,Database.now(),Database.now());
        executor.submit(()->execute(id,directory,command,timeout));return id;
    }
    private List<String> shell(String command){
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        if(!windows)return List.of("/bin/sh","-c",command);
        if(preferences.string("shell","PowerShell").equals("Command Prompt"))return List.of("cmd.exe","/d","/s","/c",command);
        return List.of("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-Command","[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(); "+command);
    }
    private void execute(String id,Path directory,String command,int timeout){
        boolean acquired=false;Process process=null;
        try{
            slots.acquire();acquired=true;
            if(closing||cancelled.contains(id))return;
            var builder=new ProcessBuilder(shell(command)).directory(directory.toFile()).redirectErrorStream(true);
            builder.environment().remove("DONGRAN_TOKEN");builder.environment().remove("DONGRAN_MODEL_API_KEY");
            process=builder.start();processes.put(id,process);
            db.jdbc.update("UPDATE command_runs SET status='running',updated_at=? WHERE id=?",Database.now(),id);
            Process running=process;var reader=executor.submit(()->pump(id,running.getInputStream()));
            boolean done=process.waitFor(timeout,TimeUnit.SECONDS);
            if(!done)terminate(process);
            try{reader.get(3,TimeUnit.SECONDS);}catch(Exception ignored){reader.cancel(true);}
            String status=cancelled.contains(id)?"cancelled":!done?"timed_out":process.exitValue()==0?"completed":"failed";
            db.jdbc.update("UPDATE command_runs SET status=?,exit_code=?,updated_at=? WHERE id=?",status,done?process.exitValue():null,Database.now(),id);
        }catch(InterruptedException e){Thread.currentThread().interrupt();db.jdbc.update("UPDATE command_runs SET status='cancelled',updated_at=? WHERE id=?",Database.now(),id);}
        catch(Exception e){db.jdbc.update("UPDATE command_runs SET status='failed',output=?,updated_at=? WHERE id=?","无法启动命令："+e.getClass().getSimpleName(),Database.now(),id);}
        finally{if(process!=null&&process.isAlive())terminate(process);processes.remove(id);cancelled.remove(id);if(acquired)slots.release();}
    }
    private void pump(String id,InputStream input){
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            char[] chunk=new char[2048];StringBuilder output=new StringBuilder();long last=0;int size;
            while((size=reader.read(chunk))!=-1){
                output.append(chunk,0,size);if(output.length()>262144)output.delete(0,output.length()-262144);
                if(System.nanoTime()-last>150_000_000){db.jdbc.update("UPDATE command_runs SET output=?,updated_at=? WHERE id=?",output.toString(),Database.now(),id);last=System.nanoTime();}
            }
            db.jdbc.update("UPDATE command_runs SET output=?,updated_at=? WHERE id=?",output.toString(),Database.now(),id);
        }catch(IOException ignored){}
    }
    public Map<String,Object> await(String id) throws InterruptedException{
        while(true){var run=get(id);if(!Set.of("queued","running").contains(run.get("status")))return run;Thread.sleep(150);}
    }
    public void cancel(String id){get(id);cancelled.add(id);Process process=processes.get(id);if(process!=null)terminate(process);db.jdbc.update("UPDATE command_runs SET status='cancelled',updated_at=? WHERE id=? AND status IN ('queued','running')",Database.now(),id);}
    public void cancelTask(String taskId){db.jdbc.queryForList("SELECT id FROM command_runs WHERE task_id=? AND status IN ('queued','running')",taskId).forEach(r->cancel(String.valueOf(r.get("id"))));}
    static void terminate(Process process){process.descendants().forEach(child->{try{child.destroyForcibly();}catch(Exception ignored){}});process.destroyForcibly();}
    public Map<String,Object> direct(Path directory,List<String> arguments,int timeout){
        Process process=null;
        try{
            var builder=new ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true);builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().remove("DONGRAN_TOKEN");builder.environment().remove("DONGRAN_MODEL_API_KEY");
            process=builder.start();Process running=process;
            var output=executor.submit(()->{try(var stream=running.getInputStream()){var buffer=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=stream.read(chunk))!=-1)if(buffer.size()<1_000_000)buffer.write(chunk,0,Math.min(n,1_000_000-buffer.size()));return buffer.toString(StandardCharsets.UTF_8);}});
            if(!process.waitFor(timeout,TimeUnit.SECONDS)){terminate(process);throw ApiException.conflict("操作超时。");}
            return Map.of("exitCode",process.exitValue(),"output",output.get(3,TimeUnit.SECONDS));
        }catch(ApiException e){throw e;}catch(Exception e){throw ApiException.bad("无法执行外部程序，请确认已安装 Git 或相应工具。");}
        finally{if(process!=null&&process.isAlive())terminate(process);}
    }
    @PreDestroy void close(){closing=true;processes.values().forEach(CommandService::terminate);executor.shutdownNow();}
}
