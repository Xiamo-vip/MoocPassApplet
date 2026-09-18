package top.xiamoi.moocpass.task;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.common.ApiException;
import top.xiamoi.moocpass.config.MoocProperties;
import top.xiamoi.moocpass.infrastructure.*;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

@Component
@Profile("worker")
public class TaskWorker {
    private final Db db;
    private final TaskQueue queue;
    private final CourseExecution execution;
    private final TaskLogger logger;
    private final ExecutorService executor;
    private final int slots;
    private final ZoneId zone;
    private final String owner=UUID.randomUUID().toString();
    private final ConcurrentHashMap<Long,Running> running=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private record Running(Map<String,Object> task,FutureTask<Void> future) {}
    public TaskWorker(Db db,TaskQueue queue,CourseExecution execution,TaskLogger logger,MoocProperties config) {
        this.db=db;this.queue=queue;this.execution=execution;this.logger=logger;
        slots=Math.max(1,Math.min(config.getWorkerSlots(),config.getGlobalSlots()));
        zone=ZoneId.of(config.getZone());
        executor=Executors.newFixedThreadPool(slots);
    }
    @Scheduled(fixedDelay=2000,initialDelay=3000)
    public void dispatch() {
        if(closing) return;
        while(running.size()<slots) {
            var claimed=queue.claim(owner);
            if(claimed.isEmpty()) return;
            var task=claimed.get();
            long id=Db.id(task,"id");
            FutureTask<Void> future=new FutureTask<>(() -> { run(task);return null; });
            running.put(id,new Running(task,future));
            executor.execute(future);
        }
    }
    private void run(Map<String,Object> task) {
        long id=Db.id(task,"id");
        CourseExecution.Outcome outcome;
        try(ExecutionScope ignored=new ExecutionScope(db,task,owner,zone)) {
            logger.event(id,"INFO","STARTED","执行器已领取任务，正在读取平台进度");
            outcome=execution.execute(task);
        } catch(TaskSignal signal) {
            String state=signal.state();
            if("RETRY_WAIT".equals(state)) {
                var settings=Json.read(Db.text(task,"configJson"),TaskSettings.class);
                if(Db.id(task,"attempts")>settings.maxRetries()) state="FAILED";
            }
            if("WAITING_AUTH".equals(state))
                db.update("UPDATE mp_platform_account SET status='EXPIRED' WHERE id=?",Db.id(task,"accountId"));
            outcome=new CourseExecution.Outcome(state,signal.code(),signal.getMessage(),null);
        } catch(ApiException error) {
            String state=Set.of("SECRET_REVOKED","ENCRYPTION_UNAVAILABLE","CAPABILITY_UNSUPPORTED").contains(error.code())?"WAITING_USER":"FAILED";
            outcome=new CourseExecution.Outcome(state,error.code(),error.getMessage(),null);
        } catch(Exception error) {
            outcome=new CourseExecution.Outcome("FAILED","EXECUTION_FAILED","执行失败，请查看平台状态后重试",null);
        }
        try {
            Thread.interrupted();
            queue.finish(task,owner,outcome.state(),outcome.code(),outcome.message(),outcome.platformProgress());
            logger.event(id,"INFO",outcome.state(),outcome.message());
        } finally { running.remove(id); }
    }
    @Scheduled(fixedDelay=15000,initialDelay=5000)
    public void heartbeat() {
        for(var entry:running.values()) {
            if(!queue.heartbeat(entry.task(),owner)) entry.future().cancel(true);
        }
    }
    @PreDestroy
    public void close() {
        closing=true;
        executor.shutdown();
        try {
            if(!executor.awaitTermination(10,TimeUnit.SECONDS)) executor.shutdownNow();
        } catch(InterruptedException error) { executor.shutdownNow();Thread.currentThread().interrupt(); }
    }
}
