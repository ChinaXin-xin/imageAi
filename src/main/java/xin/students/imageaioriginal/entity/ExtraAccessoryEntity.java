package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("extra_accessories")
public class ExtraAccessoryEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private byte[] content;
    private byte[] thumbnail;
    private String thumbnailContentType;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
