package com.dongran.work;

import jakarta.annotation.PostConstruct;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScheduleService {
    private final Database db;private final ProjectService projects;private final AgentService agents;private final TransactionTemplate transaction;
    public ScheduleService(Database db,ProjectService projects,AgentService agents,TransactionTemplate transaction){this.db=db;this.projects=projects;this.agents=agents;this.transaction=transaction;}
    public List<Map<String,Object>> list(){return db.jdbc.queryForList("SELECT document FROM schedules ORDER BY id").stream().map(row->db.object(String.valueOf(row.get("document")))).toList();}
    public Map<String,Object> snapshot(){var tasks=list();return Map.of("tasks",tasks,"revision",ProjectService.hash(db.json(tasks).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    public Map<String,Object> get(String id){return db.object(String.valueOf(db.one("SELECT document FROM schedules WHERE id=?",id).get("document")));}
    @SuppressWarnings("unchecked") @Transactional public Map<String,Object> replace(Map<String,Object> body){
        if(!Objects.equals(body.get("revision"),snapshot().get("revision")))throw ApiException.conflict("定时任务已更新，请刷新列表后重试。");
        if(!(body.get("tasks") instanceof List<?> tasks)||tasks.size()>100)throw ApiException.bad("定时任务最多 100 项。");
        Set<String> ids=new HashSet<>();var validated=new ArrayList<Map<String,Object>>();
        for(Object item:tasks){if(!(item instanceof Map<?,?> raw))throw ApiException.bad("定时任务格式不合法。");var task=(Map<String,Object>)raw;validate(task);if(!ids.add(String.valueOf(task.get("id"))))throw ApiException.bad("定时任务标识重复。");validated.add(task);}
        for(var task:validated){String id=String.valueOf(task.get("id"));var old=db.jdbc.queryForList("SELECT document FROM schedules WHERE id=?",id);if(!old.isEmpty()&&db.object(String.valueOf(old.getFirst().get("document"))).equals(task))continue;store(task,Instant.now());}
        for(var old:list())if(!ids.contains(String.valueOf(old.get("id"))))db.jdbc.update("DELETE FROM schedules WHERE id=?",old.get("id"));
        return snapshot();
    }
    @Transactional public Map<String,Object> save(String id,Map<String,Object> body){var copy=new LinkedHashMap<>(body);if(id==null){copy.put("id",Database.id());copy.put("createdAt",Database.now());}else {var old=get(id);copy.put("id",id);copy.put("createdAt",old.get("createdAt"));}copy.put("updatedAt",Database.now());validate(copy);store(copy,Instant.now());return copy;}
    public void delete(String id){get(id);db.jdbc.update("DELETE FROM schedules WHERE id=?",id);}
    public void validate(Map<String,Object> task){
        String id=Database.required(task,"id",100);if(!id.matches("[a-zA-Z0-9_-]+"))throw ApiException.bad("任务标识不合法。");
        Database.required(task,"name",80);Database.required(task,"prompt",4000);
        String scope=Database.required(task,"scope",10);if(!Set.of("global","project").contains(scope))throw ApiException.bad("工作范围不合法。");
        if(scope.equals("project"))projects.get(Database.required(task,"projectId",100));else if(task.get("projectId")!=null)throw ApiException.bad("全局计划不能绑定项目。");
        if(!(task.get("enabled") instanceof Boolean))throw ApiException.bad("启用状态不合法。");
        String frequency=Database.required(task,"frequency",10);if(!Set.of("daily","weekly","once").contains(frequency))throw ApiException.bad("重复频率不合法。");
        String time=Database.required(task,"time",5);if(!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))throw ApiException.bad("时间格式应为 HH:mm。");
        if(!(task.get("weekdays") instanceof List<?> days)||days.size()>7||days.stream().anyMatch(d->!(d instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1||n.intValue()>7)||new HashSet<>(days).size()!=days.size())throw ApiException.bad("星期设置不合法。");
        if(frequency.equals("weekly")&&((List<?>)task.get("weekdays")).isEmpty())throw ApiException.bad("每周计划至少选择一天。");
        if(frequency.equals("once")){try{LocalDate.parse(Database.required(task,"date",10));}catch(Exception e){throw ApiException.bad("日期不合法。");}if(Database.bool(task,"enabled",true)&&next(task,Instant.now())==null)throw ApiException.bad("单次计划需要设置未来时间。");}
    }
    static Instant next(Map<String,Object> task,Instant after){
        if(!Database.bool(task,"enabled",false))return null;
        ZoneId zone=ZoneId.systemDefault();LocalTime time=LocalTime.parse(String.valueOf(task.get("time")));String frequency=String.valueOf(task.get("frequency"));
        if(frequency.equals("once")){Instant due=LocalDate.parse(String.valueOf(task.get("date"))).atTime(time).atZone(zone).toInstant();return due.isAfter(after)?due:null;}
        LocalDate day=after.atZone(zone).toLocalDate();
        for(int i=0;i<9;i++){
            LocalDate candidate=day.plusDays(i);Instant due=candidate.atTime(time).atZone(zone).toInstant();
            if(!due.isAfter(after))continue;
            if(frequency.equals("daily")||((List<?>)task.get("weekdays")).stream().anyMatch(d->((Number)d).intValue()==candidate.getDayOfWeek().getValue()))return due;
        }
        return null;
    }
    private void store(Map<String,Object> task,Instant after){Instant next=next(task,after);db.jdbc.update("INSERT INTO schedules VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET document=excluded.document,next_run=excluded.next_run,updated_at=excluded.updated_at",task.get("id"),db.json(task),next==null?null:next.toString(),Database.now());}
    @PostConstruct void skipOfflineOccurrences(){
        Instant now=Instant.now();
        for(var row:db.jdbc.queryForList("SELECT id,document,next_run FROM schedules WHERE next_run IS NOT NULL")){
            if(!Instant.parse(String.valueOf(row.get("next_run"))).isAfter(now)){
                var task=db.object(String.valueOf(row.get("document")));if("once".equals(task.get("frequency"))){task.put("enabled",false);task.put("updatedAt",Database.now());}store(task,now);
            }
        }
        db.jdbc.update("UPDATE schedule_runs SET status='interrupted',error='应用退出时尚未提交执行。' WHERE status='claimed'");
    }
    @Scheduled(fixedDelay=1000) public void tick(){
        Instant now=Instant.now();
        var due=db.jdbc.queryForList("SELECT id,next_run FROM schedules WHERE next_run IS NOT NULL AND julianday(next_run)<=julianday(?) ORDER BY next_run LIMIT 20",now.toString());
        for(var item:due){
            Map<String,Object> task=transaction.execute(status->{
                var rows=db.jdbc.queryForList("SELECT document,next_run FROM schedules WHERE id=?",item.get("id"));if(rows.isEmpty()||!Objects.equals(rows.getFirst().get("next_run"),item.get("next_run")))return null;
                var document=db.object(String.valueOf(rows.getFirst().get("document")));
                int claimed=db.jdbc.update("INSERT OR IGNORE INTO schedule_runs(id,schedule_id,due_at,status) VALUES (?,?,?,'claimed')",Database.id(),item.get("id"),item.get("next_run"));
                if("once".equals(document.get("frequency"))){document.put("enabled",false);document.put("updatedAt",Database.now());}store(document,now);return claimed==1?document:null;
            });
            if(task==null)continue;
            try{
                Integer active=db.jdbc.queryForObject("SELECT count(*) FROM tasks WHERE schedule_id=? AND status IN ('queued','running','awaiting_approval')",Integer.class,item.get("id"));
                if(active!=null&&active>0){db.jdbc.update("UPDATE schedule_runs SET status='skipped',error='上一轮仍在执行。' WHERE schedule_id=? AND due_at=?",item.get("id"),item.get("next_run"));continue;}
                var result=runNow(String.valueOf(item.get("id")));
                db.jdbc.update("UPDATE schedule_runs SET status='submitted',task_id=? WHERE schedule_id=? AND due_at=?",result.get("id"),item.get("id"),item.get("next_run"));
            }catch(Exception e){db.jdbc.update("UPDATE schedule_runs SET status='failed',error=? WHERE schedule_id=? AND due_at=?",e instanceof ApiException?e.getMessage():"提交失败",item.get("id"),item.get("next_run"));}
        }
    }
    public Map<String,Object> runNow(String id){var task=get(id);var input=new LinkedHashMap<String,Object>();input.put("projectId",task.get("projectId"));input.put("prompt",task.get("prompt"));input.put("scheduleId",id);return agents.create(input);}
    public List<Map<String,Object>> runs(String id){get(id);return db.jdbc.queryForList("SELECT r.*,t.status AS taskStatus FROM schedule_runs r LEFT JOIN tasks t ON t.id=r.task_id WHERE r.schedule_id=? ORDER BY due_at DESC LIMIT 100",id);}
}
