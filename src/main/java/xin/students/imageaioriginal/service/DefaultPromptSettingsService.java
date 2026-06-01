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

    private static final String DEFAULT_MAIN_PROMPT =
            "生成高转化电商主图：突出手机膜产品质感、包装完整度和平台风格，画面干净高级，主体清晰，适合跨境电商首图。";
    private static final String DEFAULT_INTRO_PROMPT =
            "生成产品介绍图：围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点进行模块化展示，信息层级清晰，适合详情页。";
    private static final String DEFAULT_ANALYSIS_PROMPT =
            "请分析上传图片中的产品类型、材质、包装、颜色、机型线索、可用于主图和介绍图的卖点，不要编造看不见的信息。";
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
                     "select main_prompt, intro_prompt, analysis_prompt, custom_selling_points from default_prompt_settings where id = 1"
             );
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                String analysisPrompt = normalize(resultSet.getString("analysis_prompt"), DEFAULT_ANALYSIS_PROMPT);
                return new DefaultPromptSettings(
                        normalizeGenerationPrompt(resultSet.getString("main_prompt"), DEFAULT_MAIN_PROMPT, analysisPrompt),
                        normalizeGenerationPrompt(resultSet.getString("intro_prompt"), DEFAULT_INTRO_PROMPT, analysisPrompt),
                        analysisPrompt,
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
                DEFAULT_CUSTOM_SELLING_POINTS
        ));
    }

    public DefaultPromptSettings saveSettings(DefaultPromptSettings settings) {
        ensureTable();
        String analysisPrompt = normalize(settings.analysisPrompt(), DEFAULT_ANALYSIS_PROMPT);
        String mainPrompt = normalizeGenerationPrompt(settings.mainPrompt(), DEFAULT_MAIN_PROMPT, analysisPrompt);
        String introPrompt = normalizeGenerationPrompt(settings.introPrompt(), DEFAULT_INTRO_PROMPT, analysisPrompt);
        List<String> customSellingPoints = normalizeSellingPoints(settings.customSellingPoints());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into default_prompt_settings (id, main_prompt, intro_prompt, analysis_prompt, custom_selling_points)
                     values (1, ?, ?, ?, ?)
                     on duplicate key update
                       main_prompt = values(main_prompt),
                       intro_prompt = values(intro_prompt),
                       analysis_prompt = values(analysis_prompt),
                       custom_selling_points = values(custom_selling_points),
                       updated_at = current_timestamp
                     """)) {
            statement.setString(1, mainPrompt);
            statement.setString(2, introPrompt);
            statement.setString(3, analysisPrompt);
            statement.setString(4, toJson(customSellingPoints));
            statement.executeUpdate();
            return new DefaultPromptSettings(mainPrompt, introPrompt, analysisPrompt, customSellingPoints);
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
                      custom_selling_points text not null,
                      updated_at timestamp not null default current_timestamp on update current_timestamp
                    ) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci
                    """);
            addColumnIfMissing(connection, "analysis_prompt", "text null");
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

    private String normalizeGenerationPrompt(String value, String fallback, String analysisPrompt) {
        String normalized = normalize(value, fallback);
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
