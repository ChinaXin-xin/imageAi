package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("image_tasks")
public class ImageTaskEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String productName;
    private String status;
    private String payloadJson;
    private String analysisJson;
    private String finalMainPrompt;
    private String finalIntroPrompt;
    private byte[] thumbnail;
    private String thumbnailContentType;
    private String thumbnailFileName;
    private Integer realPhotoCount;
    private Integer packageImageCount;
    private Integer templateCount;
    private String errorMessage;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp startedAt;
    private Timestamp completedAt;
}
