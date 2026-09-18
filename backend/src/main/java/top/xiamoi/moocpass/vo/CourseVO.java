package top.xiamoi.moocpass.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseVO {
    private String courseId;
    private String classId;
    private String cpi;
    private String name;
    private String teacher;
    private String coverUrl;
    private Integer platformProgress;
}
