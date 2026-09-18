package top.xiamoi.moocpass.task;

import org.springframework.stereotype.Service;
import top.xiamoi.moocpass.account.AccountService;
import top.xiamoi.moocpass.course.CourseService;
import top.xiamoi.moocpass.entity.*;
import top.xiamoi.moocpass.infrastructure.*;
import top.xiamoi.moocpass.platform.*;
import java.util.*;

@Service
public class CourseExecution {
    private final AccountService accounts;
    private final CourseService courses;
    private final PlatformCatalog platforms;
    private final TaskLogger logger;
    private final Db db;
    public CourseExecution(AccountService accounts,CourseService courses,PlatformCatalog platforms,TaskLogger logger,Db db) {
        this.accounts=accounts;this.courses=courses;this.platforms=platforms;this.logger=logger;this.db=db;
    }
    public record Outcome(String state,String code,String message,Integer platformProgress) {}
    public Outcome execute(Map<String,Object> task) {
        var scope=ExecutionScope.current();
        if("SYNC".equals(Db.text(task,"kind"))) {
            courses.sync(scope.userId(),Db.id(task,"accountId"));
            return new Outcome("COMPLETED","","课程与章节已同步",null);
        }
        var course=courses.owned(scope.userId(),Db.id(task,"courseId"));
        var credential=accounts.credential(scope.userId(),Db.id(task,"accountId"));
        var adapter=platforms.require(credential.getPlatformCode());
        var settings=scope.settings();
        var external=courses.toExternal(course);
        logger.event(scope.taskId(),"INFO","SYNCING","任务已启动，正在读取平台章节");
        var initial=adapter.getResources(credential,external,(completed,total) -> {
            ExecutionScope.check();
            db.update("UPDATE mp_task SET current_step=? WHERE id=? AND state='RUNNING'",
                "正在读取章节 " + completed + "/" + total,scope.taskId());
        });
        courses.updateResources(scope.userId(),Db.id(task,"courseId"),initial,external.getPlatformProgress());
        Set<String> validIds=new HashSet<>();
        initial.forEach(resource -> { validIds.add(resource.id()); validIds.add(resource.parentId()); });
        if(!validIds.containsAll(settings.resourceIds()))
            throw new TaskSignal("WAITING_USER","COURSE_CHANGED","部分所选章节已变更，请重新选择资源");
        var selected=initial.stream().filter(settings::includes).toList();
        if(selected.isEmpty()) throw new TaskSignal("WAITING_USER","COURSE_CHANGED","所选资源已变更，请重新同步课程");
        int initiallyDone=0;
        for(CourseResource resource:selected) {
            if(resource.completed()) {
                initiallyDone++;
                db.update("INSERT INTO mp_task_checkpoint(task_id,resource_id,state,detail_json) VALUES(?,?,?,'{}') "
                    +"ON DUPLICATE KEY UPDATE state=VALUES(state),updated_at=CURRENT_TIMESTAMP",
                    scope.taskId(),resource.id(),"VERIFIED");
                logger.event(scope.taskId(),"INFO","SKIPPED","已跳过平台确认完成的资源："+resource.title());
            }
        }
        if(initiallyDone==selected.size()) {
            logger.event(scope.taskId(),"INFO","SKIPPED","所选范围已全部完成，直接进入平台进度核验");
        }
        CourseTask bridge=CourseTask.builder().id(scope.taskId()).userId(scope.userId())
            .platformCode(credential.getPlatformCode()).courseId(external.getCourseId()).classId(external.getClassId())
            .courseName(external.getName()).speed(settings.speed()).autoAnswer(settings.autoAnswer())
            .submitAnswer("SUBMIT".equals(settings.answerMode())).coverRate(settings.coverRate()).status("RUNNING").progress(0).build();
        if(selected.stream().anyMatch(resource -> !resource.completed()))
            adapter.executeCourseTask(bridge,credential,UserQuestionConfig.builder().submit(false).coverRate(settings.coverRate()).build(),null,logger);
        ExecutionScope.check();
        if("FAILED".equals(bridge.getStatus())) throw new TaskSignal("RETRY_WAIT","PLATFORM_REQUEST_FAILED","部分平台操作失败，等待核验后重试");
        db.update("UPDATE mp_task SET state='VERIFYING' WHERE id=? AND state='RUNNING'",scope.taskId());
        logger.event(scope.taskId(),"INFO","VERIFYING","正在核对平台确认的学习进度");
        var verified=adapter.getResources(accounts.credential(scope.userId(),Db.id(task,"accountId")),external);
        courses.updateResources(scope.userId(),Db.id(task,"courseId"),verified,external.getPlatformProgress());
        Map<String,CourseResource> byId=new HashMap<>();
        verified.forEach(resource -> byId.put(resource.id(),resource));
        int done=0;
        for(CourseResource resource:selected) {
            var actual=byId.get(resource.id());
            if(actual!=null&&actual.completed()) {
                done++;
                db.update("UPDATE mp_task_checkpoint SET state='VERIFIED',updated_at=CURRENT_TIMESTAMP WHERE task_id=? AND resource_id=?",
                    scope.taskId(),resource.id());
            }
        }
        int selectedProgress=done*100/selected.size();
        Integer platformProgress=external.getPlatformProgress()!=null?external.getPlatformProgress():selectedProgress;
        if(done==selected.size()) return new Outcome("COMPLETED","","平台已确认选定范围全部完成",platformProgress);
        if(!scope.answerPendingMessage().isBlank())
            return new Outcome("PARTIAL","ANSWERS_SAVED",scope.answerPendingMessage(),platformProgress);
        if(scope.requiresReview()) return new Outcome("PARTIAL","ANSWERS_DISABLED","自动答题已关闭，测验尚未提交",platformProgress);
        if(Db.id(task,"attempts")<=settings.maxRetries())
            return new Outcome("RETRY_WAIT","PROGRESS_UNCONFIRMED","平台暂未确认全部进度，稍后自动复核",platformProgress);
        return new Outcome("PARTIAL","PROGRESS_UNCONFIRMED","已核验 "+done+"/"+selected.size()+" 个资源，其余资源尚未被平台确认完成",platformProgress);
    }
}
