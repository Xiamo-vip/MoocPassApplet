package top.xiamoi.moocpass.mapper;

import org.springframework.stereotype.Component;
import top.xiamoi.moocpass.entity.CourseTask;
import top.xiamoi.moocpass.task.ExecutionScope;

@Component
public class CourseTaskMapper {
    public int updateById(CourseTask task) {
        ExecutionScope scope=ExecutionScope.optional();
        if(scope!=null) scope.progress(task.getProgress()==null?0:task.getProgress(),
            task.getCurrentChapter()==null?"":task.getCurrentChapter());
        return 1;
    }
}
