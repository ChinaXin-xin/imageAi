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
    private static final int MAX_BASE_PROMPT_CHARS = 6000;
    private static final int MAX_SCENE_PROMPT_CHARS = 700;

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

    public List<ScenePrompt> planScenes(String imageType, String basePrompt, int count) {
        int normalizedCount = Math.max(0, Math.min(MAX_SCENES, count));
        if (normalizedCount <= 0) {
            return List.of();
        }
        if (normalizedCount == 1) {
            return fallbackScenes(imageType, normalizedCount);
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String prompt = buildPlannerPrompt(imageType, basePrompt, normalizedCount);
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
            request.put("temperature", 0.7);
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
            List<ScenePrompt> scenes = normalizeScenes(parseScenes(text), imageType, normalizedCount);
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
            return fallbackScenes(imageType, normalizedCount);
        }
    }

    private String buildPlannerPrompt(String imageType, String basePrompt, int count) {
        String safePrompt = abbreviate(basePrompt == null ? "" : basePrompt.trim(), MAX_BASE_PROMPT_CHARS);
        return """
                请根据下面的最终生图基础提示词，为“%s”规划 %d 个不同场景的图片描述。

                输出必须是 JSON，不要 Markdown，不要解释。格式：
                {
                  "scenes": [
                    {"index": 1, "sceneTitle": "场景短标题", "prompt": "本张图的场景描述"}
                  ]
                }

                要求：
                1. scenes 数量必须等于 %d，index 从 1 到 %d。
                2. 每个 prompt 控制在 350 个中文字符以内。
                3. 每张图必须是不同场景、构图、背景、光影、展示角度或卖点表达，避免重复。
                4. 不要改写产品真实结构，不要改变孔位数量/位置/大小差异，不要添加未选配件。
                5. 主图场景保持电商首图质感，尽量无文字；介绍图可以更偏卖点表达和信息层级。
                6. prompt 只写本张图相对基础提示词需要变化的场景规划，不要重复粘贴完整基础提示词。

                基础提示词：
                %s
                """.formatted(imageType, count, count, count, safePrompt);
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

    private List<ScenePrompt> normalizeScenes(List<ScenePrompt> parsedScenes, String imageType, int count) {
        List<ScenePrompt> fallback = fallbackScenes(imageType, count);
        List<ScenePrompt> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScenePrompt parsed = i < parsedScenes.size() ? parsedScenes.get(i) : null;
            if (parsed == null || parsed.prompt() == null || parsed.prompt().isBlank()) {
                normalized.add(fallback.get(i));
            } else {
                normalized.add(new ScenePrompt(
                        i + 1,
                        normalizeTitle(parsed.sceneTitle(), i + 1),
                        abbreviate(parsed.prompt().trim(), MAX_SCENE_PROMPT_CHARS)
                ));
            }
        }
        return normalized;
    }

    private List<ScenePrompt> fallbackScenes(String imageType, int count) {
        String[] mainScenes = {
                "深色科技平铺主图，冷蓝边缘光，产品与配件均衡分布，镜头膜孔位清晰可见。",
                "浅色干净电商主图，柔和棚拍光，手机膜悬浮分层展示，配件整齐环绕。",
                "近景精密结构展示，强调玻璃反光、边缘厚度和镜头膜异形孔位差异。",
                "斜俯拍高级金属背景，手机模型居中，屏幕膜与镜头膜形成清晰层次。",
                "极简 Amazon 首图构图，主体大而清楚，保留真实套装数量和产品结构。"
        };
        String[] introScenes = {
                "高清透亮卖点场景，展示屏幕膜清晰度、玻璃反射和全屏覆盖边缘。",
                "9H 硬度卖点场景，用干净结构化布局表现防刮抗摔质感。",
                "防指纹卖点场景，突出膜面洁净、疏油层和易清洁效果。",
                "易安装套装场景，展示除尘贴、无尘布、酒精包和贴膜步骤感。",
                "镜头保护卖点场景，近距离突出镜头膜孔位、边缘和防护厚度。"
        };
        String[] source = "介绍图".equals(imageType) ? introScenes : mainScenes;
        List<ScenePrompt> scenes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            scenes.add(new ScenePrompt(i + 1, "场景" + (i + 1), source[i % source.length]));
        }
        return scenes;
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
