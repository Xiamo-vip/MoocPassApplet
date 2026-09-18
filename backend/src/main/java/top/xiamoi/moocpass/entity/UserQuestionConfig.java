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
@TableName("user_question_config")
public class UserQuestionConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String provider;

    private String aiBaseUrl;

    private String aiKey;

    private String aiModel;

    private Boolean submit;

    private Double coverRate;

    private String configJson;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
