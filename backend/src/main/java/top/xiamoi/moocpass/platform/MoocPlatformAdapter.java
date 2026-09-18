package top.xiamoi.moocpass.platform;

import top.xiamoi.moocpass.entity.CourseTask;
import top.xiamoi.moocpass.entity.UserPlatformConfig;
import top.xiamoi.moocpass.entity.UserQuestionConfig;
import top.xiamoi.moocpass.solver.QuestionSolver;
import top.xiamoi.moocpass.task.TaskLogger;
import top.xiamoi.moocpass.vo.CourseVO;

import java.util.List;

public interface MoocPlatformAdapter {

    String getPlatformCode();

    String getPlatformName();

    boolean validateAccount(String username, String password);

    boolean validateAuth(String token);

    default String accountIdentity(String username, String secret) {
        return username;
    }

    List<CourseVO> getCourseList(UserPlatformConfig platformConfig);

    List<CourseResource> getResources(UserPlatformConfig config, CourseVO course);

    default List<CourseResource> getResources(UserPlatformConfig config, CourseVO course,
                                             java.util.function.BiConsumer<Integer, Integer> progress) {
        return getResources(config, course);
    }

    void executeCourseTask(CourseTask task,
                           UserPlatformConfig platformConfig,
                           UserQuestionConfig questionConfig,
                           QuestionSolver questionSolver,
                           TaskLogger taskLogger);
}
