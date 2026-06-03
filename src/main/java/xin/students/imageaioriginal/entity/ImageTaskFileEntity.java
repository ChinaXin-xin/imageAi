package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("image_task_files")
public class ImageTaskFileEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String fileGroup;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private byte[] content;
    private byte[] thumbnail;
    private String thumbnailContentType;
    private Integer sortOrder;
    private Timestamp createdAt;
}
