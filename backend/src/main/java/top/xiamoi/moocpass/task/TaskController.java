package top.xiamoi.moocpass.task;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import top.xiamoi.moocpass.common.*;
import top.xiamoi.moocpass.answer.QuestionAnswerService;
import top.xiamoi.moocpass.infrastructure.*;
import java.util.*;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class TaskController {
    private final TaskService tasks;
    private final QuestionAnswerService answers;
    private final Db db;
    public TaskController(TaskService tasks,QuestionAnswerService answers,Db db) { this.tasks=tasks;this.answers=answers;this.db=db; }
    @PostMapping("/task-batches/preflight")
    public ApiResponse<?> preflight(@RequestBody TaskService.BatchInput input) { return ApiResponse.ok(tasks.preflight(RequestContext.userId(),input)); }
    @PostMapping("/task-batches")
    public ApiResponse<?> create(@RequestHeader("Idempotency-Key") String key,@RequestBody TaskService.BatchInput input) {
        return ApiResponse.ok(tasks.create(RequestContext.userId(),key,input));
    }
    @GetMapping("/tasks")
    public ApiResponse<?> list(@RequestParam(required=false) String state,@RequestParam(defaultValue="1") int page) {
        return ApiResponse.ok(tasks.list(RequestContext.userId(),state,page));
    }
    @GetMapping({"/tasks/{id}","/sync-jobs/{id}"})
    public ApiResponse<?> detail(@PathVariable long id) { return ApiResponse.ok(tasks.detail(RequestContext.userId(),id)); }
    @PostMapping("/tasks/{id}/{action:pause|resume|cancel|retry}")
    public ApiResponse<?> action(@PathVariable long id,@PathVariable String action) {
        return ApiResponse.ok(tasks.action(RequestContext.userId(),id,action));
    }
    @GetMapping("/tasks/{id}/logs")
    public ApiResponse<?> logs(@PathVariable long id,@RequestParam(defaultValue="0") long afterSeq) {
        return ApiResponse.ok(tasks.logs(RequestContext.userId(),id,afterSeq));
    }
    @GetMapping("/tasks/{id}/answers")
    public ApiResponse<?> answers(@PathVariable long id) { return ApiResponse.ok(answers.list(RequestContext.userId(),id)); }
    @GetMapping("/preferences")
    public ApiResponse<?> preferences() {
        String raw=Db.text(db.one("SELECT preferences FROM mp_user WHERE id=?",RequestContext.userId()).orElseThrow(),"preferences");
        return ApiResponse.ok(raw.isBlank()?TaskSettings.defaults():Json.read(raw,TaskSettings.class));
    }
    @PutMapping("/preferences")
    public ApiResponse<?> preferences(@RequestBody TaskSettings input) {
        input.validate();
        db.update("UPDATE mp_user SET preferences=? WHERE id=?",Json.write(input),RequestContext.userId());
        return ApiResponse.ok(input);
    }
}
