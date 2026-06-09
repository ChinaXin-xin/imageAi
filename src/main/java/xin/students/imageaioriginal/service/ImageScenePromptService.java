package xin.students.imageaioriginal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import xin.students.imageaioriginal.config.GptProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ImageScenePromptService {

    private static final Logger LOG = LoggerFactory.getLogger("imageai.gpt");
    private static final int MAX_SCENES = 20;
    private static final int MAX_FINAL_PROMPT_CHARS = 6000;
    private static final int MAX_SCENE_PROMPT_CHARS = 900;
    private static final int MAX_SCENE_SETTING_CHARS = 3000;
    private static final List<String> FORBIDDEN_OBJECT_TERMS = List.of(
            "小黑包", "黑色小包", "黑色小袋", "无字黑色小袋", "黑色包装", "黑色便携包", "白色小袋", "白色小包", "空白白色小袋", "便携袋", "收纳袋", "布袋", "绒布袋", "软布袋", "防尘袋",
            "包装盒", "纸盒", "彩盒", "礼盒", "外盒", "包装袋", "额外包装", "未上传包装", "未选择配件",
            "说明书", "安装卡", "卡片", "托盘", "展示托盘", "底托", "底座", "支架", "展示架", "展示道具", "背景道具",
            "pouch", "bag", "box", "card", "tray", "stand", "holder", "prop"
    );

    private final GptProperties gptProperties;
    private final UploadImageAnalysisService uploadImageAnalysisService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ImageScenePromptService(
            GptProperties gptProperties,
            UploadImageAnalysisService uploadImageAnalysisService,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.gptProperties = gptProperties;
        this.uploadImageAnalysisService = uploadImageAnalysisService;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public List<ScenePrompt> planScenes(String imageType, String finalPrompt, int count, String scenePrompt) {
        return planScenes(imageType, finalPrompt, count, scenePrompt, false);
    }

    public List<ScenePrompt> planScenes(String imageType, String finalPrompt, int count, String scenePrompt, boolean hasUploadedTemplate) {
        int normalizedCount = Math.max(0, Math.min(MAX_SCENES, count));
        if (normalizedCount <= 0) {
            return List.of();
        }
        boolean requiresLensStructureLock = requiresLensStructureLock(finalPrompt);
        String normalizedScenePrompt = normalizeScenePrompt(scenePrompt);
        if (normalizedCount == 1) {
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock, normalizedScenePrompt, hasUploadedTemplate);
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        // 主图和介绍图会分别传入各自的最终生图提示词，场景规划必须基于当前类型独立生成。
        String prompt = buildPlannerPrompt(imageType, finalPrompt, normalizedCount, normalizedScenePrompt, hasUploadedTemplate);
        LOG.info(
                "场景规划开始：请求ID={}，图片类型={}，规划数量={}，模型={}，提示词字符数={}",
                requestId,
                imageType,
                normalizedCount,
                gptProperties.resolvedModel(),
                prompt.length()
        );
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", gptProperties.resolvedModel());
            request.put("temperature", 0.35);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", "你是跨境电商手机膜图片场景策划师，只输出合法 JSON。"),
                    Map.of("role", "user", "content", prompt)
            ));

            JsonNode response = restClient.post()
                    .uri(uploadImageAnalysisService.chatCompletionsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + uploadImageAnalysisService.resolveApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            String text = extractText(response);
            List<ScenePrompt> scenes = normalizeScenes(
                    parseScenes(text),
                    imageType,
                    normalizedCount,
                    requiresLensStructureLock,
                    normalizedScenePrompt,
                    hasUploadedTemplate
            );
            LOG.info(
                    "场景规划响应：请求ID={}，图片类型={}，规划数量={}，解析数量={}，返回内容={}",
                    requestId,
                    imageType,
                    normalizedCount,
                    scenes.size(),
                    text
            );
            return scenes;
        } catch (Exception ex) {
            LOG.warn(
                    "场景规划失败：请求ID={}，图片类型={}，规划数量={}，错误={}",
                    requestId,
                    imageType,
                    normalizedCount,
                    ex.getMessage()
            );
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock, normalizedScenePrompt, hasUploadedTemplate);
        }
    }

    private String buildPlannerPrompt(String imageType, String finalPrompt, int count, String scenePrompt, boolean hasUploadedTemplate) {
        String safePrompt = abbreviate(taskParameterSection(finalPrompt), MAX_FINAL_PROMPT_CHARS);
        String safeScenePrompt = abbreviate(scenePrompt, MAX_SCENE_SETTING_CHARS);
        String userRequirement = safeScenePrompt.isBlank()
                ? "用户未输入场景图提示词，请根据基础提示词、卖点、套装规格和平台要求自动规划不同场景。"
                : safeScenePrompt;
        String scenePromptMode = safeScenePrompt.isBlank()
                ? "用户未输入场景图提示词：请你基于基础提示词、卖点、套装规格和平台要求自动规划不同场景。"
                : "用户输入了场景图提示词：必须在用户给定范围内规划，不要越出用户指定的场景/卖点方向。";
        String layoutMode = hasUploadedTemplate
                ? "当前类型启用了排版图：场景规划只作为轻量构图/光影参考，不能突破排版图主体区、配件区、留白、安全边距和对齐关系。"
                : "当前类型未启用排版图：除主图第 1 张全配件合集外，其它主图或介绍图可按场景卖点从已选配件中自动选择部分配件展示，不要求每张都把配件全展示完；只能从已选配件中选择，未选配件禁止出现。";
        String prompt = """
        请根据下面的最终生图提示词，为“%s”规划 %d 个不同场景的图片描述。

        输出必须是 JSON，不要 Markdown，不要解释。格式：
        {
          "scenes": [
            {"index": 1, "sceneTitle": "", "prompt": "本张图的场景描述"}
          ]
        }

        要求：
        a. 返回的json中不要说与第几张图不同。
        b. scenes 数量必须等于 %d，index 从 1 到 %d。
        c. %s
        d. %s
        如果“%s”为主图且数量大于 1，index=1 必须是套装合集图：手机、膜片/镜头膜和所有已选择配件按真实比例同框整齐展示。

        用户要求：
        %s
        %s
        %s

        基础提示词：
        %s
        """.formatted(imageType, count, count, count, scenePromptMode, layoutMode, imageType, userRequirement, scenePromptMode, layoutMode, safePrompt);

        LOG.info("场景规划提示词：{}", prompt);

        return prompt;
    }

    String taskParameterSection(String finalPrompt) {
        String normalized = finalPrompt == null ? "" : finalPrompt.trim().replace("\r\n", "\n");
        String marker = "# 【任务参数】";
        int start = normalized.indexOf(marker);
        if (start < 0) {
            return normalized;
        }
        int nextSection = normalized.indexOf("\n# 【", start + marker.length());
        if (nextSection < 0) {
            return normalized.substring(start).trim();
        }
        return normalized.substring(start, nextSection).trim();
    }

    private List<ScenePrompt> parseScenes(String text) throws Exception {
        JsonNode root = objectMapper.readTree(stripJsonFence(text));
        JsonNode scenesNode = root.path("scenes");
        if (!scenesNode.isArray()) {
            scenesNode = root.path("images");
        }
        if (!scenesNode.isArray()) {
            scenesNode = root.path("prompts");
        }
        if (!scenesNode.isArray() && root.isArray()) {
            scenesNode = root;
        }
        if (!scenesNode.isArray()) {
            throw new IllegalStateException("场景规划 JSON 缺少 scenes 数组");
        }
        List<ScenePrompt> scenes = new ArrayList<>();
        for (JsonNode node : scenesNode) {
            int index = node.path("index").asInt(scenes.size() + 1);
            String title = text(node, "sceneTitle", "title", "name");
            String prompt = text(node, "prompt", "description", "scenePrompt", "imageDescription");
            if (prompt == null || prompt.isBlank()) {
                continue;
            }
            scenes.add(new ScenePrompt(index, normalizeTitle(title, index), abbreviate(prompt.trim(), MAX_SCENE_PROMPT_CHARS)));
        }
        return scenes;
    }

    private List<ScenePrompt> normalizeScenes(
            List<ScenePrompt> parsedScenes,
            String imageType,
            int count,
            boolean requiresLensStructureLock,
            String scenePrompt,
            boolean hasUploadedTemplate
    ) {
        List<ScenePrompt> fallback = fallbackScenes(imageType, count, requiresLensStructureLock, scenePrompt, hasUploadedTemplate);
        List<ScenePrompt> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScenePrompt parsed = i < parsedScenes.size() ? parsedScenes.get(i) : null;
            if (parsed == null || parsed.prompt() == null || parsed.prompt().isBlank() || containsForbiddenObject(parsed.prompt())) {
                normalized.add(fallback.get(i));
            } else {
                normalized.add(new ScenePrompt(
                        i + 1,
                        normalizeTitle(parsed.sceneTitle(), i + 1),
                        appendConfiguredSceneDirective(parsed.prompt().trim(), imageType, i, count, requiresLensStructureLock, scenePrompt)
                ));
            }
        }
        return normalized;
    }

    boolean containsForbiddenObject(String prompt) {
        String normalized = prompt == null ? "" : prompt.replaceAll("\\s+", "").toLowerCase();
        return FORBIDDEN_OBJECT_TERMS.stream().anyMatch(normalized::contains);
    }

    List<ScenePrompt> fallbackScenes(String imageType, int count, boolean requiresLensStructureLock, String scenePrompt) {
        return fallbackScenes(imageType, count, requiresLensStructureLock, scenePrompt, false);
    }

    List<ScenePrompt> fallbackScenes(String imageType, int count, boolean requiresLensStructureLock, String scenePrompt, boolean hasUploadedTemplate) {
        String cameraHoleLock = requiresLensStructureLock
                ? "镜头膜必须按上传图/深析结果保持对应机型的外轮廓、孔位数量、位置和大小差异，不套用其他手机型号。"
                : "产品结构按上传图/深析结果保持外轮廓、数量、位置和大小差异。";
        List<String> source = sceneDirectives(scenePrompt);
        if (source.isEmpty()) {
            source = autoSceneDirectives();
        }
        List<ScenePrompt> scenes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String scene = "主图".equals(imageType) && count > 1 && i == 0
                    ? "套装合集主图：手机、膜片/镜头膜和所有已选择配件按真实比例同框整齐展示，主体完整入画，配件分区对齐。"
                    : i < source.size()
                    ? source.get(i)
                    : "基于基础提示词和已配置场景继续扩展新的卖点、构图、背景、光影或角度，不要重复已有图片造型。";
            String typeLock = "主图".equals(imageType) ? "主图必须无文字、无图标、无角标、无卖点标签、无水印。" : "";
            String accessoryMode = hasUploadedTemplate
                    ? "按排版图区域展示配件。"
                    : "除主图第1张合集外，本张可按卖点只展示部分已选配件，未选配件禁止出现。";
            scenes.add(new ScenePrompt(
                    i + 1,
                    "场景" + (i + 1),
                    abbreviate(scene + " " + typeLock + " " + accessoryMode + " " + cameraHoleLock, MAX_SCENE_PROMPT_CHARS)
            ));
        }
        return scenes;
    }

    private String appendConfiguredSceneDirective(
            String prompt,
            String imageType,
            int zeroBasedIndex,
            int total,
            boolean requiresLensStructureLock,
            String scenePrompt
    ) {
        String directive = sceneDirectiveForIndex(scenePrompt, zeroBasedIndex);
        if ("主图".equals(imageType) && total > 1 && zeroBasedIndex == 0) {
            directive = "套装合集主图：手机、膜片/镜头膜和所有已选择配件按真实比例同框整齐展示，主体完整入画，配件分区对齐。 " + directive;
        }
        String typeLock = "主图".equals(imageType)
                ? " "
                : "";
        String lensStructureLock = requiresLensStructureLock
                //? "不改变孔位数量、位置、大小差异和一体式/分离式形态。"
                ? " "
                : "";
        return abbreviate(prompt + " " + directive + " " + typeLock + " " + lensStructureLock, MAX_SCENE_PROMPT_CHARS);
    }

    private List<String> autoSceneDirectives() {
        return List.of(
                "3D立体斜角安装态：手机完整入画，屏幕膜/钢化膜与手机分层错位展示，有厚度、阴影和空间纵深。",
                "规整平铺套装图：俯拍或45度俯拍，手机、膜片和与当前卖点相关的已选配件按真实比例整齐分区。",
                "近景结构细节图：保留主商品整体关系，用旁侧局部放大或边缘高光展示膜片边缘、镜头膜孔位和贴合细节。",
                "防窥卖点图：仅在任务选择防窥膜时使用深色防窥质感和侧视隐私效果，未选择防窥时改为屏幕保护结构展示。",
                "高清透亮卖点图：突出透明清亮玻璃质感、屏幕显示清晰度和高光反射，不改变手机型号和产品结构。",
                "防指纹/疏油卖点图：用干净反光、少量水滴或指纹对比表达洁净效果，不添加文字、包装或未选配件。",
                "易安装步骤感图：展示屏幕膜与手机的对位关系和必要的已选择清洁/安装辅助配件，配件按参考图数量和比例出现。"
        );
    }

    private String normalizeScenePrompt(String scenePrompt) {
        return scenePrompt == null ? "" : scenePrompt.trim();
    }

    private String sceneDirectiveForIndex(String scenePrompt, int zeroBasedIndex) {
        List<String> directives = sceneDirectives(scenePrompt);
        if (zeroBasedIndex < 0 || zeroBasedIndex >= directives.size()) {
            return "";
        }
        return directives.get(zeroBasedIndex);
    }

    private List<String> sceneDirectives(String scenePrompt) {
        String normalized = normalizeScenePrompt(scenePrompt);
        List<String> directives = new ArrayList<>();
        for (String line : normalized.split("\\R+")) {
            String directive = line
                    .replaceFirst("^\\s*(?:\\d+|[一二三四五六七八九十]+)[.．、)）\\s-]*", "")
                    .replaceFirst("^\\s*第(?:\\d+|[一二三四五六七八九十]+)张[：:、.．)）\\s-]*", "")
                    .replaceFirst("^\\s*[-*•]+\\s*", "")
                    .trim();
            if (!directive.isBlank() && !containsForbiddenObject(directive)) {
                directives.add(directive);
            }
        }
        if (directives.isEmpty() && !normalized.isBlank() && !containsForbiddenObject(normalized)) {
            directives.add(normalized);
        }
        return directives;
    }

    private boolean requiresLensStructureLock(String basePrompt) {
        String normalized = basePrompt == null ? "" : basePrompt.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("镜头膜")
                || normalized.contains("镜头保护")
                || normalized.contains("镜头保护膜")
                || normalized.contains("camera protector")
                || normalized.contains("lens protector");
    }

    private String extractText(JsonNode response) {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return content.asText();
        }
        throw new IllegalStateException("GPT 未返回有效场景规划");
    }

    private String stripJsonFence(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "");
            normalized = normalized.replaceFirst("\\s*```$", "");
        }
        return normalized.trim();
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private String normalizeTitle(String value, int index) {
        return "场景" + index;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    public record ScenePrompt(
            int index,
            String sceneTitle,
            String prompt
    ) {
    }
}
