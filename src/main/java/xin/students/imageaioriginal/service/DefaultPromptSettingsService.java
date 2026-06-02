package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.model.DefaultPromptSettings;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Service
public class DefaultPromptSettingsService {

    private static final String DEFAULT_MAIN_PROMPT = """
            生成高转化电商主图。先严格还原上传实拍图中的手机膜、镜头膜和配件真实结构，再做电商视觉美化。
            产品结构优先级高于风格：不得把异形镜头膜改成通用款，不得统一不同大小的孔位，不得增加或删除开孔、镜圈、配件。
            画面干净高级，主体清晰，适合跨境电商首图；风格只服务于真实产品展示，不遮挡、不改变产品结构。
            """;
    private static final String DEFAULT_INTRO_PROMPT =
            "生成产品介绍图：围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点进行模块化展示，信息层级清晰，适合详情页。";
    private static final String LEGACY_DEFAULT_MAIN_PROMPT =
            "生成高转化电商主图：突出手机膜产品质感、包装完整度和平台风格，画面干净高级，主体清晰，适合跨境电商首图。";
    private static final String LEGACY_DEFAULT_ANALYSIS_PROMPT =
            "请分析上传图片中的产品类型、材质、包装、颜色、机型线索、可用于主图和介绍图的卖点，不要编造看不见的信息。";
    private static final String DEFAULT_ANALYSIS_PROMPT = """
            请客观深析上传图片，重点输出后续生图必须锁定的真实产品结构，不要编造看不见的信息。

            如果图片中包含手机膜、镜头膜、保护壳或电子配件，必须逐项描述：
            1. 产品类型、外轮廓、边缘形状、缺口、倒角和厚度感；
            2. 开孔/孔位数量、相对位置、排列方向、每个孔的相对大小；
            3. 哪些孔位大小不一致、哪些结构是非对称或异形结构；
            4. 材质、颜色、透明度、反光、高光、表面纹理；
            5. 包装、托盘、贴纸、无尘布、酒精包等可见配件的形状、颜色、数量和相对尺寸；
            6. 生图时必须禁止模型改成通用款、标准款或常见款的关键细节。
            7. 如果是镜头膜，必须明确输出大孔数量、小孔数量、左右/上下位置、每个小孔的相对大小顺序，以及是否禁止生成成等大孔。
            8. 如果是三星 S23U / S23 Ultra 镜头膜，尤其要检查右侧三个小孔是否大小不一致，并写清右上、右中、右下三个孔的大小关系。

            输出请按“图片1、图片2...”分别描述，最后增加“结构锁定要点”小节，用简短明确的生成约束总结孔位、外形和数量。
            """;
    private static final String DEFAULT_TARGET_TEMPLATE_PROMPT = """
            请作为跨境电商图片视觉风格分析师，只分析这张目标模板图的视觉风格，不要照抄产品内容。

            请输出适合后续生图使用的中文风格说明，重点包含：
            1. 画面构图和主体摆放方式
            2. 背景材质、颜色氛围和空间层次
            3. 光影、高光、反射、阴影、3D质感
            4. 文字/图标/信息模块的排版风格，如果没有就说明无明显文案
            5. 生成时需要保持的风格约束

            只描述风格和画面语言，不要要求生成模板里的具体商品，不要编造品牌。
            """;
    private static final List<String> DEFAULT_CUSTOM_SELLING_POINTS =
            List.of("高清透亮", "9H硬度", "防指纹", "全屏覆盖", "易安装", "镜头保护");

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public DefaultPromptSettingsService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        getSettings();
    }

    public DefaultPromptSettings getSettings() {
        ensureTable();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select main_prompt, intro_prompt, analysis_prompt, target_template_prompt, custom_selling_points from default_prompt_settings where id = 1"
             );
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                String analysisPrompt = normalizeAnalysisPrompt(resultSet.getString("analysis_prompt"));
                return new DefaultPromptSettings(
                        normalizeGenerationPrompt(resultSet.getString("main_prompt"), DEFAULT_MAIN_PROMPT, analysisPrompt),
                        normalizeGenerationPrompt(resultSet.getString("intro_prompt"), DEFAULT_INTRO_PROMPT, analysisPrompt),
                        analysisPrompt,
                        normalize(resultSet.getString("target_template_prompt"), DEFAULT_TARGET_TEMPLATE_PROMPT),
                        parseSellingPoints(resultSet.getString("custom_selling_points"))
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取默认提示词失败", ex);
        }
        return saveSettings(new DefaultPromptSettings(
                DEFAULT_MAIN_PROMPT,
                DEFAULT_INTRO_PROMPT,
                DEFAULT_ANALYSIS_PROMPT,
                DEFAULT_TARGET_TEMPLATE_PROMPT,
                DEFAULT_CUSTOM_SELLING_POINTS
        ));
    }

    public DefaultPromptSettings saveSettings(DefaultPromptSettings settings) {
        ensureTable();
        String analysisPrompt = normalizeAnalysisPrompt(settings.analysisPrompt());
        String targetTemplatePrompt = normalize(settings.targetTemplatePrompt(), DEFAULT_TARGET_TEMPLATE_PROMPT);
        String mainPrompt = normalizeGenerationPrompt(settings.mainPrompt(), DEFAULT_MAIN_PROMPT, analysisPrompt);
        String introPrompt = normalizeGenerationPrompt(settings.introPrompt(), DEFAULT_INTRO_PROMPT, analysisPrompt);
        List<String> customSellingPoints = normalizeSellingPoints(settings.customSellingPoints());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into default_prompt_settings (id, main_prompt, intro_prompt, analysis_prompt, target_template_prompt, custom_selling_points)
                     values (1, ?, ?, ?, ?, ?)
                     on duplicate key update
                       main_prompt = values(main_prompt),
                       intro_prompt = values(intro_prompt),
                       analysis_prompt = values(analysis_prompt),
                       target_template_prompt = values(target_template_prompt),
                       custom_selling_points = values(custom_selling_points),
                       updated_at = current_timestamp
                     """)) {
            statement.setString(1, mainPrompt);
            statement.setString(2, introPrompt);
            statement.setString(3, analysisPrompt);
            statement.setString(4, targetTemplatePrompt);
            statement.setString(5, toJson(customSellingPoints));
            statement.executeUpdate();
            return new DefaultPromptSettings(mainPrompt, introPrompt, analysisPrompt, targetTemplatePrompt, customSellingPoints);
        } catch (SQLException ex) {
            throw new IllegalStateException("保存默认提示词失败", ex);
        }
    }

    private void ensureTable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists default_prompt_settings (
                      id bigint primary key,
                      main_prompt text not null,
                      intro_prompt text not null,
                      analysis_prompt text not null,
                      target_template_prompt text null,
                      custom_selling_points text not null,
                      updated_at timestamp not null default current_timestamp on update current_timestamp
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
            addColumnIfMissing(connection, "analysis_prompt", "text null");
            addColumnIfMissing(connection, "target_template_prompt", "text null");
            addColumnIfMissing(connection, "custom_selling_points", "text null");
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化默认提示词表失败", ex);
        }
    }

    private void addColumnIfMissing(Connection connection, String columnName, String definition) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, "default_prompt_settings", columnName)) {
            if (columns.next()) {
                return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("alter table default_prompt_settings add column " + columnName + " " + definition);
        }
    }

    private String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String normalizeAnalysisPrompt(String value) {
        String normalized = normalize(value, DEFAULT_ANALYSIS_PROMPT);
        if (sameText(normalized, LEGACY_DEFAULT_ANALYSIS_PROMPT)) {
            return DEFAULT_ANALYSIS_PROMPT.trim();
        }
        return normalized;
    }

    private String normalizeGenerationPrompt(String value, String fallback, String analysisPrompt) {
        String normalized = normalize(value, fallback);
        if (sameText(normalized, LEGACY_DEFAULT_MAIN_PROMPT)) {
            return DEFAULT_MAIN_PROMPT.trim();
        }
        if (sameText(normalized, analysisPrompt) || looksLikeAnalysisPrompt(normalized)) {
            return fallback;
        }
        return normalized;
    }

    private boolean sameText(String left, String right) {
        return left != null && right != null && left.trim().equals(right.trim());
    }

    private boolean looksLikeAnalysisPrompt(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "");
        return normalized.contains("请分析上传图片")
                || normalized.contains(LEGACY_DEFAULT_ANALYSIS_PROMPT.replaceAll("\\s+", ""))
                || normalized.contains("深析上传图")
                || normalized.contains("只分析图片中与手机膜产品相关的内容")
                || (normalized.contains("输出要求") && normalized.contains("不要写设计建议"));
    }

    private List<String> parseSellingPoints(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CUSTOM_SELLING_POINTS;
        }
        try {
            List<String> points = objectMapper.readValue(value, new TypeReference<>() {
            });
            return normalizeSellingPoints(points);
        } catch (JsonProcessingException ex) {
            return DEFAULT_CUSTOM_SELLING_POINTS;
        }
    }

    private List<String> normalizeSellingPoints(List<String> points) {
        if (points == null || points.isEmpty()) {
            return DEFAULT_CUSTOM_SELLING_POINTS;
        }
        List<String> normalized = points.stream()
                .filter(point -> point != null && !point.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        return normalized.isEmpty() ? DEFAULT_CUSTOM_SELLING_POINTS : normalized;
    }

    private String toJson(List<String> points) {
        try {
            return objectMapper.writeValueAsString(points);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化自定义卖点失败", ex);
        }
    }
}
