package top.xiamoi.moocpass.task;

import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.platform.CourseResource;
import java.time.*;
import java.util.*;

public final class ExecutionScope implements AutoCloseable {
    private static final ThreadLocal<ExecutionScope> CURRENT = new ThreadLocal<>();
    private final Db db;
    private final Map<String,Object> task;
    private final String owner;
    private final long version;
    private final TaskSettings settings;
    private final ZoneId zone;
    private String currentResource = "";
    private String checkpointState = "";
    private boolean requiresReview;
    private String answerPendingMessage = "";
    public ExecutionScope(Db db, Map<String,Object> task, String owner, ZoneId zone) {
        this.db=db; this.task=task; this.owner=owner; this.zone=zone;
        version=Db.id(task,"version");
        settings=Json.read(Db.text(task,"configJson"),TaskSettings.class);
        CURRENT.set(this);
    }
    public static ExecutionScope current() {
        ExecutionScope scope=CURRENT.get();
        if(scope==null) throw new IllegalStateException("Task execution context is missing");
        return scope;
    }
    public static ExecutionScope optional() { return CURRENT.get(); }
    public long taskId() { return Db.id(task,"id"); }
    public long userId() { return Db.id(task,"userId"); }
    public TaskSettings settings() { return settings; }
    public String resourceId() { return currentResource; }
    public boolean requiresReview() { return requiresReview; }
    public void requireReview() { requiresReview=true; }
    public String answerPendingMessage() { return answerPendingMessage; }
    public void markAnswerPending(String message) { answerPendingMessage=message; }
    public static void check() {
        var scope=CURRENT.get();
        if(Thread.currentThread().isInterrupted()) throw new TaskSignal("PAUSED","INTERRUPTED","任务执行已中断");
        if(scope==null) return;
        var row=scope.db.one("SELECT state FROM mp_task WHERE id=? AND owner=? AND version=? AND lease_until>CURRENT_TIMESTAMP",
            scope.taskId(),scope.owner,scope.version).orElseThrow(() -> new TaskSignal("RETRY_WAIT","LEASE_LOST","执行租约已失效"));
        String state=Db.text(row,"state");
        if("CANCELING".equals(state)) throw new TaskSignal("CANCELED","USER_CANCELED","用户已取消任务");
        if("PAUSING".equals(state)) throw new TaskSignal("PAUSED","USER_PAUSED","用户已暂停任务");
        if(!Set.of("RUNNING","VERIFYING").contains(state)) throw new TaskSignal(state,"STATE_CHANGED","任务状态已变更");
        if("COURSE".equals(Db.text(scope.task,"kind")) && !scope.settings.allowedNow(scope.zone))
            throw new TaskSignal("QUEUED","TIME_WINDOW","等待下一个学习时间段");
    }
    public static void rethrowControl(Throwable error) {
        if(error instanceof TaskSignal signal) throw signal;
        if(Thread.currentThread().isInterrupted()) throw new TaskSignal("PAUSED","INTERRUPTED","任务执行已中断");
        var scope=CURRENT.get();
        if(scope!=null&&"SUBMITTING".equals(scope.checkpointState))
            throw new TaskSignal("WAITING_USER","SUBMISSION_UNCERTAIN","上次提交结果不确定，请先到平台核对");
    }
    public static void sleep(long millis) {
        long until=System.nanoTime()+Duration.ofMillis(Math.max(0,millis)).toNanos();
        while(System.nanoTime()<until) {
            check();
            try { Thread.sleep(Math.min(1000,Math.max(1,(until-System.nanoTime())/1_000_000))); }
            catch(InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new TaskSignal("PAUSED","INTERRUPTED","任务执行已中断");
            }
        }
    }
    public boolean includes(String id,String parent,String type) {
        return settings.includes(new CourseResource(id,parent,"",type,false));
    }
    public void begin(String resourceId) {
        check();
        currentResource=resourceId;
        checkpointState="";
        var previous=db.one("SELECT state FROM mp_task_checkpoint WHERE task_id=? AND resource_id=?",taskId(),resourceId);
        if(previous.isPresent() && Set.of("SUBMITTING","UNCERTAIN").contains(Db.text(previous.get(),"state")))
            throw new TaskSignal("WAITING_USER","SUBMISSION_UNCERTAIN","上次提交结果不确定，请先到平台核对");
        checkpoint("STARTED");
    }
    public void checkpoint(String state) {
        check();
        if(currentResource.isBlank()) return;
        checkpointState=state;
        db.update("INSERT INTO mp_task_checkpoint(task_id,resource_id,state,detail_json) VALUES(?,?,?,'{}') "
            +"ON DUPLICATE KEY UPDATE state=VALUES(state),updated_at=CURRENT_TIMESTAMP",taskId(),currentResource,state);
    }
    public void progress(int percent,String step) {
        check();
        db.update("UPDATE mp_task SET progress=?,current_step=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND owner=? AND version=?",
            Math.max(0,Math.min(99,percent)),step.length()>250?step.substring(0,250):step,taskId(),owner,version);
    }
    @Override public void close() { CURRENT.remove(); }
}
