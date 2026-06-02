package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("image_task_results")
public class ImageTaskResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String resultType;
    private Integer itemIndex;
    private String status;
    private String prompt;
    private String imageUrl;
    private String imageBase64;
    private String revisedPrompt;
    private String rawResponse;
    private String errorMessage;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Long parentResultId;
    private Integer versionIndex;
    private String editSuggestion;
}
