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
        int normalizedCount = Math.max(0, Math.min(MAX_SCENES, count));
        if (normalizedCount <= 0) {
            return List.of();
        }
        boolean requiresLensStructureLock = requiresLensStructureLock(finalPrompt);
        String normalizedScenePrompt = normalizeScenePrompt(scenePrompt);
        if (normalizedCount == 1) {
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock, normalizedScenePrompt);
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        // 主图和介绍图会分别传入各自的最终生图提示词，场景规划必须基于当前类型独立生成。
        String prompt = buildPlannerPrompt(imageType, finalPrompt, normalizedCount, normalizedScenePrompt);
        LOG.info(
                "gpt.scene-plan.start id={} type={} count={} model={} promptChars={}",
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
                    normalizedScenePrompt
            );
            LOG.info(
                    "gpt.scene-plan.response id={} type={} count={} parsedCount={} result={}",
                    requestId,
                    imageType,
                    normalizedCount,
                    scenes.size(),
                    text
            );
            return scenes;
        } catch (Exception ex) {
            LOG.warn(
                    "gpt.scene-plan.failed id={} type={} count={} message={}",
                    requestId,
                    imageType,
                    normalizedCount,
                    ex.getMessage()
            );
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock, normalizedScenePrompt);
        }
    }

    private String buildPlannerPrompt(String imageType, String finalPrompt, int count, String scenePrompt) {
        String safePrompt = abbreviate(finalPrompt == null ? "" : finalPrompt.trim(), MAX_FINAL_PROMPT_CHARS);
        String safeScenePrompt = abbreviate(scenePrompt, MAX_SCENE_SETTING_CHARS);
        return """
        请根据下面的最终生图提示词，为“%s”规划 %d 个不同场景的图片描述。

        输出必须是 JSON，不要 Markdown，不要解释。格式：
        {
          "scenes": [
            {"index": 1, "sceneTitle": "", "prompt": "本张图的场景描述"}
          ]
        }

        要求：
        0. 返回的json中不要说与第几张图不同。
        1. scenes 数量必须等于 %d，index 从 1 到 %d。
        2. 每个 prompt 控制在 300 个中文字符以内，只写本张图相对基础提示词需要变化的场景规划，不要重复完整基础提示词。
        3. 优先按“用户手动输入的场景图要求”逐条分配到每张图；如果场景图要求少于图片数量，请基于已有场景继续扩展不同卖点、构图、背景、光影或角度，避免多张图同一造型。
        4. 每个场景都必须继承基础提示词中的产品结构、真实比例、安装关系、配件数量、禁改项和负面约束，不得因简写场景而改变或省略硬规则。
        5. 禁止规划镜头膜悬浮在后摄模组上方、后方或遮挡摄像头的场景；如果镜头膜与后摄手机同框展示，只允许两种关系：已精准安装到手机后摄镜头模组上，且孔内必须露出真实摄像头玻璃、闪光灯和传感器；或按真实比例平铺在手机旁边（注意镜头膜与手机的比例），不得挡住、盖住或替代摄像头、闪光灯、传感器。
        6. 近景或细节场景不能裁掉主商品整体关系；需要局部细节时，用旁侧局部放大模块、边缘高光或局部特写表达，不得改变安装关系、孔位数量、孔位大小或配件数量。
        7. 不得规划膜片位于摄像模组后方、手机中部、手机底部、手机另一侧无关区域，或任何会遮挡后摄镜头、闪光灯、传感器的位置。

        用户手动输入的场景图要求：
        %s

        基础提示词：
        %s
        """.formatted(imageType, count, count, count, safeScenePrompt, safePrompt);
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
            String scenePrompt
    ) {
        List<ScenePrompt> fallback = fallbackScenes(imageType, count, requiresLensStructureLock, scenePrompt);
        List<ScenePrompt> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScenePrompt parsed = i < parsedScenes.size() ? parsedScenes.get(i) : null;
            if (parsed == null || parsed.prompt() == null || parsed.prompt().isBlank() || containsForbiddenObject(parsed.prompt())) {
                normalized.add(fallback.get(i));
            } else {
                normalized.add(new ScenePrompt(
                        i + 1,
                        normalizeTitle(parsed.sceneTitle(), i + 1),
                        appendConfiguredSceneDirective(parsed.prompt().trim(), imageType, i, requiresLensStructureLock, scenePrompt)
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
        String cameraHoleLock = requiresLensStructureLock
                ? "镜头膜必须按上传图/深析结果保持对应机型的外轮廓、孔位数量、位置和大小差异，不套用其他手机型号。"
                : "产品结构按上传图/深析结果保持外轮廓、数量、位置和大小差异。";
        List<String> source = sceneDirectives(scenePrompt);
        List<ScenePrompt> scenes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String scene = i < source.size()
                    ? source.get(i)
                    : "基于基础提示词和已配置场景继续扩展新的卖点、构图、背景、光影或角度，不要重复已有图片造型。";
            String typeLock = "主图".equals(imageType) ? "主图必须无文字、无图标、无角标、无卖点标签、无水印。" : "";
            scenes.add(new ScenePrompt(
                    i + 1,
                    "场景" + (i + 1),
                    abbreviate(scene + " " + typeLock + " " + cameraHoleLock, MAX_SCENE_PROMPT_CHARS)
            ));
        }
        return scenes;
    }

    private String appendConfiguredSceneDirective(
            String prompt,
            String imageType,
            int zeroBasedIndex,
            boolean requiresLensStructureLock,
            String scenePrompt
    ) {
        String directive = sceneDirectiveForIndex(scenePrompt, zeroBasedIndex);
        String typeLock = "主图".equals(imageType)
                ? "主图必须无文字、无图标、无角标、无卖点标签、无水印。"
                : "";
        String lensStructureLock = requiresLensStructureLock
                ? "不改变孔位数量、位置、大小差异和一体式/分离式形态。"
                : "";
        return abbreviate(prompt + " " + directive + " " + typeLock + " " + lensStructureLock, MAX_SCENE_PROMPT_CHARS);
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
        if (directives.isEmpty() && !containsForbiddenObject(normalized)) {
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
        return value == null || value.isBlank() ? "场景" + index : abbreviate(value.trim(), 80);
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
