package top.xiamoi.moocpass.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("course_task")
public class CourseTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String platformCode;

    private String courseId;

    private String classId;

    private String courseName;

    private String coverUrl;

    private String teacher;

    private Double speed;

    private String status;

    private Integer progress;

    private String currentChapter;

    private String questionProvider;

    private Boolean autoAnswer;

    private Boolean submitAnswer;

    private Double coverRate;

    private String errorMessage;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private boolean inspection;

    @Builder.Default
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.List<top.xiamoi.moocpass.platform.CourseResource> resources = new java.util.ArrayList<>();
}
