package top.xiamoi.moocpass.task;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.config.MoocProperties;
import top.xiamoi.moocpass.infrastructure.Db;
import java.time.*;

@Component
@Profile("worker")
public class RetentionJob {
    private final Db db;
    private final MoocProperties config;
    public RetentionJob(Db db,MoocProperties config) { this.db=db;this.config=config; }
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void clean() {
        var now=LocalDateTime.now(ZoneId.of(config.getZone()));
        db.update("DELETE FROM mp_task_log WHERE created_at<?",now.minusDays(config.getLogRetentionDays()));
        db.update("DELETE FROM mp_auth_session WHERE refresh_expires_at<?",now.minusDays(1));
        db.update("DELETE FROM mp_api_usage WHERE created_at<?",now.minusDays(config.getHistoryRetentionDays()));
        db.update("DELETE FROM mp_question_result WHERE task_id IN "
            +"(SELECT id FROM mp_task WHERE state IN ('COMPLETED','PARTIAL','FAILED','CANCELED') AND updated_at<?)",
            now.minusDays(config.getHistoryRetentionDays()));
        db.update("DELETE FROM mp_answer_cache WHERE updated_at<?",now.minusDays(config.getHistoryRetentionDays()));
    }
}
