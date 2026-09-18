package top.xiamoi.moocpass.task;

import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.infrastructure.Db;

@Component
public class TaskLogger {
    private final Db db;
    public TaskLogger(Db db) { this.db=db; }
    public void log(Long taskId,String message) { event(taskId,"INFO","PROGRESS",message); }
    public void event(Long taskId,String level,String type,String message) {
        if(taskId==null) return;
        String safe=redact(message);
        db.transaction(() -> {
            var row=db.one("SELECT log_seq FROM mp_task WHERE id=? FOR UPDATE",taskId);
            if(row.isEmpty()) return null;
            long sequence=Db.id(row.get(),"logSeq")+1;
            db.update("UPDATE mp_task SET log_seq=? WHERE id=?",sequence,taskId);
            db.update("INSERT INTO mp_task_log(task_id,seq,level,event_type,message) VALUES(?,?,?,?,?)",
                taskId,sequence,level,type,safe);
            return null;
        });
    }
    public static String redact(String message) {
        if(message==null) return "";
        String result=message.replaceAll("(?i)(token|password|secret|api[_-]?key|authorization|cookie)(\\s*[:=]\\s*)[^\\s&,;]+","$1$2[redacted]")
            .replaceAll("https?://[^\\s]+","[平台地址]")
            .replaceAll("[\\x{1F000}-\\x{1FAFF}✔▶✅❌⚠]","")
            .replaceAll("[│├└─]","").trim();
        return result.length()>1800?result.substring(0,1800):result;
    }
}
