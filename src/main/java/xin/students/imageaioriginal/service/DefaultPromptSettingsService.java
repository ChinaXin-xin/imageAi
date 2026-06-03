package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.entity.DefaultPromptSettingsEntity;
import xin.students.imageaioriginal.mapper.DefaultPromptSettingsMapper;
import xin.students.imageaioriginal.model.DefaultPromptSettings;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Service
public class DefaultPromptSettingsService {

    private static final long SETTINGS_ID = 1L;

    private static final String DEFAULT_MAIN_PROMPT = """
            生成高转化电商主图。先严格还原上传实拍图中的手机膜、镜头膜和配件真实结构；如果启用排版图，按排版图的主体区、配件区、留白、安全边距和对齐关系填图。
            手机必须完整入画并保留安全边距；屏幕膜/钢化膜/防窥膜与手机屏幕长宽比一致、尺寸接近可覆盖屏幕区域；镜头膜只匹配后摄区域，不能放大到接近半个手机；配件按参考图等比例缩放并整齐摆放。
            产品结构和比例优先级高于风格：不得把异形镜头膜改成通用款，不得统一不同大小的孔位，不得增加或删除开孔、镜圈、配件，不得把产品自由散落摆放。
            主图按 Amazon/TEMU 平台首图标准：不加文字、图标、角标、贴纸文案和水印；通过左右位置、俯拍/斜拍角度、背景光影、轮廓光和轻微 3D 层次做差异化，避免重复铺货感。
            """;
    private static final String DEFAULT_INTRO_PROMPT = """
            生成产品介绍图。按实拍图锁定手机膜/镜头膜/配件结构和比例；如果启用排版图，优先沿用排版图的信息模块、产品区域、配件区域、网格/分栏、留白和对齐关系。
            手机主体尽量完整入画；屏幕膜与手机屏幕比例一致；镜头膜只对应后摄区域；清洁/安装配件必须按参考图外形、颜色、标签区域和可见文字等比例摆放。
            围绕高清、防指纹、抗摔、防窥、易安装、镜头保护等卖点做清晰模块化展示，信息不要遮挡产品，版面整齐有秩序。
            """;
    private static final String PREVIOUS_DEFAULT_MAIN_PROMPT = """
            生成高转化电商主图。先严格还原上传实拍图中的手机膜、镜头膜和配件真实结构，再做电商视觉美化。
            产品结构优先级高于风格：不得把异形镜头膜改成通用款，不得统一不同大小的孔位，不得增加或删除开孔、镜圈、配件。
            主图按 Amazon/TEMU 平台首图标准：不加文字、图标、角标、贴纸文案和水印；通过左右位置、俯拍/斜拍角度、手机颜色、背景光影、轮廓光和轻微 3D 层次做差异化，避免重复铺货感。
            钢化膜轮廓可以加清晰高光和玻璃边缘光效，提高高清晰度、立体感和科技感；风格只服务于真实产品展示，不遮挡、不改变产品结构。
            """;
    private static final String PREVIOUS_DEFAULT_INTRO_PROMPT =
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
            5. 仅在图片可见时描述手机膜相关清洁/安装辅助配件；输出它们的真实形状、颜色、材质、数量、相对尺寸和是否有可见文字，不要套用固定配件外观；
            6. 生图时必须禁止模型改成通用款、标准款或常见款的关键细节。
            7. 如果是镜头膜，必须明确输出大孔数量、小孔数量、左右/上下位置、每个小孔的相对大小顺序，以及是否禁止生成成等大孔。
            8. 如果是任意型号镜头膜，必须先识别手机品牌/型号线索，再按上传图写清所有大孔/小孔的数量、左右/上下位置、大小关系和非对称结构；不要套用其他手机型号的常见孔位。
            9. 如果出现清洁/安装辅助配件，必须识别真实外形、颜色、尺寸比例和是否有文字；若有文字，必须抄出参考图可见文字，后续生图只能复现参考图文字，不要生成无字替代品或凭空文字。
            10. 必须输出手机、屏幕膜、镜头膜和清洁/安装配件之间的相对比例：屏幕膜是否应接近手机屏幕尺寸、镜头膜应对应后摄区域多大范围、配件相对手机大约是小件/中件/大件；不要让后续生图把镜头膜或配件放大成接近半个手机。
            11. 客户产品范围只有手机、钢化膜、高清膜、防窥膜、镜头膜及已上传/已选择的手机膜相关清洁安装配件；不要推断包装盒、收纳袋、卡片、托盘、支架、底座或其他赠品。
            输出请按“图片1、图片2...”分别描述，最后增加“结构锁定要点”小节，用简短明确的生成约束总结孔位、外形和数量。
            """;
    private static final String PREVIOUS_DEFAULT_ANALYSIS_PROMPT = """
            请客观深析上传图片，重点输出后续生图必须锁定的真实产品结构，不要编造看不见的信息。
            如果图片中包含手机膜、镜头膜、保护壳或电子配件，必须逐项描述：
            1. 产品类型、外轮廓、边缘形状、缺口、倒角和厚度感；
            2. 开孔/孔位数量、相对位置、排列方向、每个孔的相对大小；
            3. 哪些孔位大小不一致、哪些结构是非对称或异形结构；
            4. 材质、颜色、透明度、反光、高光、表面纹理；
            5. 仅在图片可见时描述手机膜相关清洁/安装辅助配件；输出它们的真实形状、颜色、材质、数量、相对尺寸和是否有可见文字，不要套用固定配件外观；
            6. 生图时必须禁止模型改成通用款、标准款或常见款的关键细节。
            7. 如果是镜头膜，必须明确输出大孔数量、小孔数量、左右/上下位置、每个小孔的相对大小顺序，以及是否禁止生成成等大孔。
            8. 如果是任意型号镜头膜，必须先识别手机品牌/型号线索，再按上传图写清所有大孔/小孔的数量、左右/上下位置、大小关系和非对称结构；不要套用其他手机型号的常见孔位。
            9. 如果出现清洁/安装辅助配件，必须识别真实外形、颜色、尺寸比例和是否有文字；若有文字，必须抄出参考图可见文字，后续生图只能复现参考图文字，不要生成无字替代品或凭空文字。
            10. 客户产品范围只有手机、钢化膜、高清膜、防窥膜、镜头膜及已上传/已选择的手机膜相关清洁安装配件；不要推断包装盒、收纳袋、卡片、托盘、支架、底座或其他赠品。
            输出请按“图片1、图片2...”分别描述，最后增加“结构锁定要点”小节，用简短明确的生成约束总结孔位、外形和数量。
            """;
    private static final String DEFAULT_TARGET_TEMPLATE_PROMPT = """
            请作为跨境电商图片视觉风格分析师，只分析这张参考风格图的视觉风格，不要照抄产品内容。
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
    private final DefaultPromptSettingsMapper defaultPromptSettingsMapper;
    private volatile boolean tableEnsured;
    private volatile DefaultPromptSettings cachedSettings;

    public DefaultPromptSettingsService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            DefaultPromptSettingsMapper defaultPromptSettingsMapper
    ) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.defaultPromptSettingsMapper = defaultPromptSettingsMapper;
    }

    @PostConstruct
    public void initialize() {
        getSettings();
    }

    public DefaultPromptSettings getSettings() {
        DefaultPromptSettings cached = cachedSettings;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = cachedSettings;
            if (cached != null) {
                return cached;
            }
            ensureTable();
            DefaultPromptSettingsEntity entity = defaultPromptSettingsMapper.selectById(SETTINGS_ID);
            DefaultPromptSettings settings = entity == null
                    ? saveSettings(new DefaultPromptSettings(
                    DEFAULT_MAIN_PROMPT,
                    DEFAULT_INTRO_PROMPT,
                    DEFAULT_ANALYSIS_PROMPT,
                    DEFAULT_TARGET_TEMPLATE_PROMPT,
                    DEFAULT_CUSTOM_SELLING_POINTS
            ))
                    : toSettings(entity);
            if (entity != null && shouldPersistNormalizedSettings(entity, settings)) {
                settings = saveSettings(settings);
            }
            cachedSettings = settings;
            return settings;
        }
    }

    public DefaultPromptSettings saveSettings(DefaultPromptSettings settings) {
        ensureTable();
        String analysisPrompt = normalizeAnalysisPrompt(settings.analysisPrompt());
        String targetTemplatePrompt = normalize(settings.targetTemplatePrompt(), DEFAULT_TARGET_TEMPLATE_PROMPT);
        String mainPrompt = normalizeGenerationPrompt(settings.mainPrompt(), DEFAULT_MAIN_PROMPT, analysisPrompt);
        String introPrompt = normalizeGenerationPrompt(settings.introPrompt(), DEFAULT_INTRO_PROMPT, analysisPrompt);
        List<String> customSellingPoints = normalizeSellingPoints(settings.customSellingPoints());

        DefaultPromptSettingsEntity entity = new DefaultPromptSettingsEntity();
        entity.setId(SETTINGS_ID);
        entity.setMainPrompt(mainPrompt);
        entity.setIntroPrompt(introPrompt);
        entity.setAnalysisPrompt(analysisPrompt);
        entity.setTargetTemplatePrompt(targetTemplatePrompt);
        entity.setCustomSellingPoints(toJson(customSellingPoints));

        if (defaultPromptSettingsMapper.selectById(SETTINGS_ID) == null) {
            defaultPromptSettingsMapper.insert(entity);
        } else {
            defaultPromptSettingsMapper.updateById(entity);
        }
        DefaultPromptSettings saved = new DefaultPromptSettings(mainPrompt, introPrompt, analysisPrompt, targetTemplatePrompt, customSellingPoints);
        cachedSettings = saved;
        return saved;
    }

    private DefaultPromptSettings toSettings(DefaultPromptSettingsEntity entity) {
        String analysisPrompt = normalizeAnalysisPrompt(entity.getAnalysisPrompt());
        return new DefaultPromptSettings(
                normalizeGenerationPrompt(entity.getMainPrompt(), DEFAULT_MAIN_PROMPT, analysisPrompt),
                normalizeGenerationPrompt(entity.getIntroPrompt(), DEFAULT_INTRO_PROMPT, analysisPrompt),
                analysisPrompt,
                normalize(entity.getTargetTemplatePrompt(), DEFAULT_TARGET_TEMPLATE_PROMPT),
                parseSellingPoints(entity.getCustomSellingPoints())
        );
    }

    private boolean shouldPersistNormalizedSettings(DefaultPromptSettingsEntity entity, DefaultPromptSettings settings) {
        return !sameText(entity.getMainPrompt(), settings.mainPrompt())
                || !sameText(entity.getIntroPrompt(), settings.introPrompt())
                || !sameText(entity.getAnalysisPrompt(), settings.analysisPrompt())
                || !sameText(entity.getTargetTemplatePrompt(), settings.targetTemplatePrompt());
    }

    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
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
                tableEnsured = true;
            } catch (SQLException ex) {
                throw new IllegalStateException("初始化默认提示词表失败", ex);
            }
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
        if (sameText(normalized, LEGACY_DEFAULT_ANALYSIS_PROMPT)
                || sameText(normalized, PREVIOUS_DEFAULT_ANALYSIS_PROMPT)) {
            return DEFAULT_ANALYSIS_PROMPT.trim();
        }
        return normalized;
    }

    private String normalizeGenerationPrompt(String value, String fallback, String analysisPrompt) {
        String normalized = normalize(value, fallback);
        if (sameText(normalized, LEGACY_DEFAULT_MAIN_PROMPT)
                || sameText(normalized, PREVIOUS_DEFAULT_MAIN_PROMPT)
                || sameText(normalized, PREVIOUS_DEFAULT_INTRO_PROMPT)) {
            return fallback.trim();
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
