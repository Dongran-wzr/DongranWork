package com.dongran.work;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Database db;private final LocalSecurity security;private final PreferenceService preferences;private final ProjectService projects;private final GitService git;private final KnowledgeService knowledge;private final MemoryService memories;private final ModelClient model;private final CredentialService credentials;private final CommandService commands;private final AgentService agents;private final ApprovalService approvals;private final TaskEvents events;private final ScheduleService schedules;private final ConnectionService connections;
    private final Semaphore streamSlots=new Semaphore(32);
    public ApiController(Database db,LocalSecurity security,PreferenceService preferences,ProjectService projects,GitService git,KnowledgeService knowledge,MemoryService memories,ModelClient model,CredentialService credentials,CommandService commands,AgentService agents,ApprovalService approvals,TaskEvents events,ScheduleService schedules,ConnectionService connections){this.db=db;this.security=security;this.preferences=preferences;this.projects=projects;this.git=git;this.knowledge=knowledge;this.memories=memories;this.model=model;this.credentials=credentials;this.commands=commands;this.agents=agents;this.approvals=approvals;this.events=events;this.schedules=schedules;this.connections=connections;}
    @GetMapping("/health") public Object health(){return Map.of("status","ok","version","0.1.0");}
    @PostMapping("/session") public Object session(HttpServletResponse response){security.session(response);return Map.of("connected",true);}
    @GetMapping("/bootstrap") public Object bootstrap(){return Map.of("settings",preferences.all(),"projects",projects.list(),"schedules",schedules.snapshot(),"model",model.status(),"timezone",java.time.ZoneId.systemDefault().toString());}
    @GetMapping("/settings") public Object settings(){return preferences.all();}
    @PatchMapping("/settings") public Object settings(@RequestBody Map<String,Object> body){return preferences.patch(body);}
    @GetMapping("/settings/export") public Object export(){return Map.of("version",1,"settings",preferences.all());}
    @PutMapping("/settings/import") public Object importSettings(@RequestBody Map<String,Object> body){if(!(body.get("version") instanceof Number n)||n.intValue()!=1||!(body.get("settings") instanceof Map<?,?> values))throw ApiException.bad("配置格式不合法。");return preferences.patch(db.object(db.json(values)));}
    @GetMapping("/account") public Object account(){var result=new LinkedHashMap<String,Object>();preferences.all().forEach((k,v)->{if(k.startsWith("account"))result.put(k,v);});return result;}
    @PatchMapping("/account") public Object account(@RequestBody Map<String,Object> body){if(body.keySet().stream().anyMatch(k->!Set.of("accountNickname","accountTitle","accountBio","accountAvatar").contains(k)))throw ApiException.bad("账户字段不合法。");preferences.patch(body);return account();}
    @GetMapping("/projects") public Object projects(){return projects.list();}
    @PostMapping("/projects") public Object open(@RequestBody Map<String,Object> body){return projects.open(Database.required(body,"path",2000));}
    @GetMapping("/directories") public Object directories(@RequestParam(required=false) String path){return projects.browse(path);}
    @GetMapping("/projects/{id}/files") public Object files(@PathVariable String id,@RequestParam(defaultValue="") String path){return projects.files(id,path);}
    @GetMapping("/projects/{id}/file") public Object file(@PathVariable String id,@RequestParam String path){return projects.read(id,path);}
    @PutMapping("/projects/{id}/file") public Object write(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);Object content=body.get("content");if(!(content instanceof String text))throw ApiException.bad("文件内容必须是文本。");return projects.write(id,Database.required(body,"path",1000),text,Database.text(body,"expectedSha256",null));}
    @GetMapping("/projects/{id}/git") public Object git(@PathVariable String id){return git.status(id);}
    @GetMapping("/projects/{id}/git/diff") public Object diff(@PathVariable String id){return git.diff(id);}
    @PostMapping("/projects/{id}/git/init") public Object gitInit(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);return git.init(id);}
    @PostMapping("/projects/{id}/git/commit") public Object commit(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);return git.commit(id,body);}
    @PostMapping("/projects/{id}/git/fetch") public Object fetch(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);return git.fetch(id);}
    @GetMapping("/projects/{id}/worktrees") public Object worktrees(@PathVariable String id){return git.worktrees(id);}
    @PostMapping("/projects/{id}/worktrees") public Object addWorktree(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);return git.addWorktree(id,body);}
    @DeleteMapping("/projects/{id}/worktrees") public Object removeWorktree(@PathVariable String id,@RequestBody Map<String,Object> body){confirmed(body);git.removeWorktree(id,Database.required(body,"path",2000));return Map.of("deleted",true);}
    @GetMapping("/memories") public Object memories(@RequestParam(required=false) String projectId,@RequestParam(defaultValue="false") boolean effective){return memories.list(projectId,effective);}
    @PostMapping("/memories") public Object addMemory(@RequestBody Map<String,Object> body){return memories.save(null,body);}
    @PutMapping("/memories/{id}") public Object editMemory(@PathVariable String id,@RequestBody Map<String,Object> body){return memories.save(id,body);}
    @DeleteMapping("/memories/{id}") public Object deleteMemory(@PathVariable String id){memories.delete(id);return Map.of("deleted",true);}
    @GetMapping("/knowledge") public Object knowledge(@RequestParam(required=false) String projectId,@RequestParam(required=false) String query){return query==null?knowledge.list(projectId):knowledge.search(projectId,query);}
    @GetMapping("/knowledge/{id}") public Object knowledgeDocument(@PathVariable String id){return knowledge.get(id);}
    @PostMapping("/knowledge") public Object addKnowledge(@RequestBody Map<String,Object> body){return knowledge.save(null,Database.text(body,"projectId",null),Database.required(body,"name",200),Database.required(body,"content",1_000_000));}
    @PutMapping("/knowledge/{id}") public Object editKnowledge(@PathVariable String id,@RequestBody Map<String,Object> body){var old=knowledge.get(id);return knowledge.save(id,(String)old.get("projectId"),Database.required(body,"name",200),Database.required(body,"content",1_000_000));}
    @PostMapping(value="/knowledge/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object upload(@RequestParam(required=false) String projectId,@RequestParam MultipartFile file)throws IOException{return knowledge.upload(projectId,file);}
    @DeleteMapping("/knowledge/{id}") public Object deleteKnowledge(@PathVariable String id){knowledge.delete(id);return Map.of("deleted",true);}
    @GetMapping("/models/status") public Object models(){return model.status();}
    @PutMapping("/credentials/{id}") public Object credential(@PathVariable String id,@RequestBody Map<String,Object> body){return credentials.save(id,Database.text(body,"value",""),Database.bool(body,"remember",true));}
    @DeleteMapping("/credentials/{id}") public Object deleteCredential(@PathVariable String id){credentials.delete(id);return Map.of("deleted",true);}
    @PostMapping("/models/test") public Object testModel()throws Exception{long start=System.nanoTime();var result=model.complete(List.of(Map.of("role","user","content","请只回复 OK。")),List.of(),delta->{});return Map.of("connected",true,"reply",result.text(),"elapsedMs",(System.nanoTime()-start)/1_000_000);}
    @GetMapping("/tasks") public Object tasks(@RequestParam(required=false) String projectId){return agents.list(projectId);}
    @PostMapping("/tasks") public Object task(@RequestBody Map<String,Object> body){return agents.create(body);}
    @GetMapping("/tasks/{id}") public Object task(@PathVariable String id){return agents.get(id);}
    @PostMapping("/tasks/{id}/messages") public Object reply(@PathVariable String id,@RequestBody Map<String,Object> body){return agents.reply(id,Database.required(body,"prompt",16000));}
    @PostMapping("/tasks/{id}/cancel") public Object cancel(@PathVariable String id){agents.cancel(id);return Map.of("cancelled",true);}
    @PostMapping("/approvals/{id}") public Object approval(@PathVariable String id,@RequestBody Map<String,Object> body){if(!(body.get("approved") instanceof Boolean))throw ApiException.bad("必须明确批准或拒绝。");approvals.resolve(id,(Boolean)body.get("approved"));return Map.of("resolved",true);}
    @GetMapping(value="/tasks/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestHeader(value="Last-Event-ID",required=false) String last){
        agents.get(id);if(!streamSlots.tryAcquire())throw ApiException.conflict("事件连接数过多。");
        long cursor=after;if(last!=null)try{cursor=Math.max(cursor,Long.parseLong(last));}catch(NumberFormatException ignored){}
        SseEmitter emitter=new SseEmitter(1_800_000L);long initial=cursor;var open=new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(()->open.set(false));emitter.onTimeout(()->open.set(false));emitter.onError(error->open.set(false));
        Thread.startVirtualThread(()->{long current=initial,lastPing=0;try{
            while(open.get()){
                var entries=events.since(id,current);for(var entry:entries){current=((Number)entry.get("id")).longValue();emitter.send(SseEmitter.event().id(String.valueOf(current)).name(String.valueOf(entry.get("type"))).data(entry.get("data")));}
                String status=String.valueOf(db.one("SELECT status FROM tasks WHERE id=?",id).get("status"));
                if(entries.isEmpty()&&!Set.of("queued","running","awaiting_approval").contains(status)){emitter.send(SseEmitter.event().name("end").data(Map.of("status",status)));break;}
                if(System.currentTimeMillis()-lastPing>10000){emitter.send(SseEmitter.event().comment("heartbeat"));lastPing=System.currentTimeMillis();}Thread.sleep(150);
            }
            emitter.complete();
        }catch(Exception e){emitter.completeWithError(e);}finally{streamSlots.release();}});return emitter;
    }
    @PostMapping("/commands") public Object command(@RequestBody Map<String,Object> body){confirmed(body);String id=commands.start(Database.required(body,"projectId",100),null,Database.required(body,"command",4000),Database.number(body,"timeout",120,1,3600));return commands.get(id);}
    @GetMapping("/commands/{id}") public Object command(@PathVariable String id){return commands.get(id);}
    @PostMapping("/commands/{id}/cancel") public Object cancelCommand(@PathVariable String id){commands.cancel(id);return commands.get(id);}
    @GetMapping("/schedules") public Object schedules(){return schedules.snapshot();}
    @PutMapping("/schedules") public Object replaceSchedules(@RequestBody Map<String,Object> body){return schedules.replace(body);}
    @PostMapping("/schedules") public Object addSchedule(@RequestBody Map<String,Object> body){return schedules.save(null,body);}
    @PutMapping("/schedules/{id}") public Object editSchedule(@PathVariable String id,@RequestBody Map<String,Object> body){return schedules.save(id,body);}
    @DeleteMapping("/schedules/{id}") public Object deleteSchedule(@PathVariable String id){schedules.delete(id);return Map.of("deleted",true);}
    @PostMapping("/schedules/{id}/run") public Object runSchedule(@PathVariable String id){return schedules.runNow(id);}
    @GetMapping("/schedules/{id}/runs") public Object scheduleRuns(@PathVariable String id){return schedules.runs(id);}
    @PostMapping("/connections/{id}/test") public Object testConnection(@PathVariable String id)throws Exception{return connections.test(id);}
    @GetMapping("/connections/{id}/tools") public Object connectionTools(@PathVariable String id)throws Exception{return connections.tools(id);}
    @GetMapping("/extensions") public Object extensions(){return Map.of("installed",preferences.collection("installedExtensions"),"catalog",List.of(Map.of("id","product-docs","name","需求文档"),Map.of("id","code-review","name","代码审查"),Map.of("id","browser-check","name","浏览器验证")));}
    @PostMapping("/extensions/{id}/run") public Object extension(@PathVariable String id,@RequestBody Map<String,Object> body){
        if(preferences.collection("installedExtensions").stream().noneMatch(e->id.equals(e.get("id"))&&Database.bool(e,"enabled",false)))throw ApiException.conflict("扩展未启用。");
        String prompt=Map.of("product-docs","分析当前项目并生成需求文档，写入 docs/requirements.md，明确范围与验收标准。","code-review","审查当前项目 Git 变更，报告真实的缺陷、风险和缺失测试。","browser-check","检查当前项目的浏览器测试配置，在获得命令执行授权后运行现有浏览器测试，报告真实结果；没有测试配置时明确说明。").get(id);
        if(prompt==null)throw ApiException.missing("扩展不存在。");var input=new LinkedHashMap<>(body);input.put("prompt",prompt);return agents.create(input);
    }
    @GetMapping("/activity") public Object activity(@RequestParam(required=false) String projectId){return db.jdbc.queryForList("SELECT substr(created_at,1,10) AS date,count(*) AS count FROM tasks WHERE (? IS NULL OR project_id=?) GROUP BY substr(created_at,1,10) ORDER BY date",projectId,projectId);}
    private void confirmed(Map<String,Object> body){if(!Boolean.TRUE.equals(body.get("confirmed")))throw ApiException.forbidden("此操作需要用户明确确认。");}
}
