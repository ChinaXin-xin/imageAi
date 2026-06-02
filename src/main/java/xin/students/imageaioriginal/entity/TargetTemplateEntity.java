package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("target_templates")
public class TargetTemplateEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String templateType;
    private String name;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private byte[] content;
    private byte[] thumbnail;
    private String thumbnailContentType;
    private String styleAnalysis;
    private String model;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
