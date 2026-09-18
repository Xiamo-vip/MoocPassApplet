package top.xiamoi.moocpass.course;

import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import top.xiamoi.moocpass.account.AccountService;
import top.xiamoi.moocpass.platform.PlatformCatalog;
import top.xiamoi.moocpass.task.TaskService;
import top.xiamoi.moocpass.common.*;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/v1")
public class CourseController {
    private final PlatformCatalog platforms;
    private final AccountService accounts;
    private final CourseService courses;
    private final TaskService tasks;
    private final CourseResourceLoader resourceLoader;
    public CourseController(PlatformCatalog platforms,AccountService accounts,CourseService courses,TaskService tasks,
                            CourseResourceLoader resourceLoader) {
        this.platforms=platforms;this.accounts=accounts;this.courses=courses;this.tasks=tasks;
        this.resourceLoader=resourceLoader;
    }
    @GetMapping("/platforms") public ApiResponse<?> platforms() { return ApiResponse.ok(platforms.list()); }
    @GetMapping("/accounts") public ApiResponse<?> accounts() { return ApiResponse.ok(accounts.list(RequestContext.userId())); }
    @PostMapping("/accounts") public ApiResponse<?> bind(@RequestBody AccountService.Input input) {
        return ApiResponse.ok(accounts.bind(RequestContext.userId(),null,input));
    }
    @PostMapping("/accounts/{id}/reauth") public ApiResponse<?> reauth(@PathVariable long id,@RequestBody AccountService.Input input) {
        return ApiResponse.ok(accounts.bind(RequestContext.userId(),id,input));
    }
    @PostMapping("/accounts/{id}/verify") public ApiResponse<?> verify(@PathVariable long id) {
        return ApiResponse.ok(accounts.verify(RequestContext.userId(),id));
    }
    @DeleteMapping("/accounts/{id}") public ApiResponse<?> delete(@PathVariable long id) {
        accounts.delete(RequestContext.userId(),id);
        return ApiResponse.ok(Map.of("unbound",true));
    }
    @PostMapping("/accounts/{id}/sync") public ApiResponse<?> sync(@PathVariable long id) {
        return ApiResponse.ok(courses.sync(RequestContext.userId(), id));
    }
    @GetMapping("/courses") public ApiResponse<?> courses(@RequestParam(required=false) String accountId,
        @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="1") int page) {
        Long parsedAccountId = null;
        if (accountId != null && !accountId.isBlank() && !"undefined".equalsIgnoreCase(accountId) && !"null".equalsIgnoreCase(accountId)) {
            try {
                parsedAccountId = Long.parseLong(accountId.trim());
            } catch (NumberFormatException ignored) {}
        }
        return ApiResponse.ok(courses.list(RequestContext.userId(),parsedAccountId,query,page));
    }
    @GetMapping("/courses/{id}/resources") public ApiResponse<?> resources(@PathVariable long id,
        @RequestParam(defaultValue="false") boolean refresh) {
        return ApiResponse.ok(resourceLoader.get(RequestContext.userId(),id,refresh));
    }
}
