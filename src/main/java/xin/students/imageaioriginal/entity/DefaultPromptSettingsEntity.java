package xin.students.imageaioriginal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("default_prompt_settings")
public class DefaultPromptSettingsEntity {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String mainPrompt;
    private String introPrompt;
    private String analysisPrompt;
    private String targetTemplatePrompt;
    private String scenePrompt;
    private String customSellingPoints;
    private Timestamp updatedAt;
}
