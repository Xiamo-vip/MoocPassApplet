package top.xiamoi.moocpass.task;

import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.config.MoocProperties;
import top.xiamoi.moocpass.infrastructure.*;
import java.time.*;
import java.util.*;

@Service
public class TaskQueue {
    private final Db db;
    private final MoocProperties config;
    private final ZoneId zone;
    public TaskQueue(Db db,MoocProperties config) { this.db=db;this.config=config;zone=ZoneId.of(config.getZone()); }
    public Optional<Map<String,Object>> claim(String owner) {
        return db.transaction(() -> {
            db.one("SELECT id FROM mp_scheduler_lock WHERE id=1 FOR UPDATE").orElseThrow();
            reap();
            if(db.count("SELECT COUNT(*) FROM mp_task WHERE owner IS NOT NULL AND lease_until>CURRENT_TIMESTAMP")>=config.getGlobalSlots())
                return Optional.empty();
            var candidates=db.list("SELECT t.* FROM mp_task t WHERE t.state IN ('QUEUED','RETRY_WAIT') "
                +"AND t.owner IS NULL AND t.next_run_at<=CURRENT_TIMESTAMP ORDER BY t.id LIMIT 100");
            for(var task:candidates) {
                long id=Db.id(task,"id"),accountId=Db.id(task,"accountId"),userId=Db.id(task,"userId");
                TaskSettings settings=Json.read(Db.text(task,"configJson"),TaskSettings.class);
                if("COURSE".equals(Db.text(task,"kind"))&&!settings.allowedNow(zone)) {
                    db.update("UPDATE mp_task SET next_run_at=? WHERE id=?",settings.nextWindow(zone),id);
                    continue;
                }
                if(db.count("SELECT COUNT(*) FROM mp_account_lease WHERE account_id=? AND lease_until>CURRENT_TIMESTAMP",accountId)>0) continue;
                if(db.count("SELECT COUNT(*) FROM mp_task WHERE user_id=? AND owner IS NOT NULL AND lease_until>CURRENT_TIMESTAMP",
                    userId)>=config.getUserSlots()) continue;
                LocalDateTime until=LocalDateTime.now(zone).plusSeconds(config.getLeaseSeconds());
                db.update("DELETE FROM mp_account_lease WHERE account_id=? AND lease_until<=CURRENT_TIMESTAMP",accountId);
                db.update("INSERT INTO mp_account_lease(account_id,task_id,owner,lease_until) VALUES(?,?,?,?)",accountId,id,owner,until);
                db.update("UPDATE mp_task SET state='RUNNING',owner=?,lease_until=?,version=version+1,attempts=attempts+1,"
                    +"updated_at=CURRENT_TIMESTAMP WHERE id=?",owner,until,id);
                return db.one("SELECT * FROM mp_task WHERE id=?",id);
            }
            return Optional.empty();
        });
    }
    private void reap() {
        var expired=db.list("SELECT id,state FROM mp_task WHERE owner IS NOT NULL AND lease_until<=CURRENT_TIMESTAMP FOR UPDATE");
        for(var task:expired) {
            long id=Db.id(task,"id");
            String state=Db.text(task,"state");
            String next="CANCELING".equals(state)?"CANCELED":"PAUSING".equals(state)?"PAUSED":"RETRY_WAIT";
            db.update("UPDATE mp_task SET state=?,owner=NULL,lease_until=NULL,next_run_at=CURRENT_TIMESTAMP,"
                +"error_code='WORKER_RECOVERED',error_message='执行器中断，等待重新核验进度',updated_at=CURRENT_TIMESTAMP WHERE id=?",next,id);
            db.update("DELETE FROM mp_account_lease WHERE task_id=?",id);
            if(TaskService.TERMINAL.contains(next)) db.update("DELETE FROM mp_active_course WHERE task_id=?",id);
        }
    }
    public boolean heartbeat(Map<String,Object> task,String owner) {
        return db.transaction(() -> {
            LocalDateTime until=LocalDateTime.now(zone).plusSeconds(config.getLeaseSeconds());
            int changed=db.update("UPDATE mp_task SET lease_until=? WHERE id=? AND owner=? AND version=? AND lease_until>CURRENT_TIMESTAMP",
                until,Db.id(task,"id"),owner,Db.id(task,"version"));
            if(changed==0) return false;
            db.update("UPDATE mp_account_lease SET lease_until=? WHERE task_id=? AND owner=?",until,Db.id(task,"id"),owner);
            return true;
        });
    }
    public void finish(Map<String,Object> task,String owner,String state,String code,String message,Integer platformProgress) {
        db.transaction(() -> {
            long id=Db.id(task,"id");
            var current=db.one("SELECT state FROM mp_task WHERE id=? AND owner=? AND version=? FOR UPDATE",id,owner,Db.id(task,"version"));
            if(current.isEmpty()) return null;
            String finalState="CANCELING".equals(Db.text(current.get(),"state"))?"CANCELED"
                :"PAUSING".equals(Db.text(current.get(),"state"))?"PAUSED":state;
            var settings=Json.read(Db.text(task,"configJson"),TaskSettings.class);
            LocalDateTime next="QUEUED".equals(finalState)?settings.nextWindow(zone)
                :LocalDateTime.now(zone).plusSeconds(Math.min(300,15L*Db.id(task,"attempts")));
            String safeMessage=TaskLogger.redact(message);
            if(safeMessage.length()>500) safeMessage=safeMessage.substring(0,500);
            db.update("UPDATE mp_task SET state=?,owner=NULL,lease_until=NULL,error_code=?,error_message=?,platform_progress=?,"
                +"progress=CASE WHEN ?='COMPLETED' THEN 100 WHEN ? IS NOT NULL AND ? > progress THEN ? ELSE progress END,"
                +"next_run_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                finalState,code,safeMessage,platformProgress,finalState,platformProgress,platformProgress,platformProgress,next,id);
            db.update("DELETE FROM mp_account_lease WHERE task_id=? AND owner=?",id,owner);
            if(TaskService.TERMINAL.contains(finalState)) db.update("DELETE FROM mp_active_course WHERE task_id=?",id);
            return null;
        });
    }
}
