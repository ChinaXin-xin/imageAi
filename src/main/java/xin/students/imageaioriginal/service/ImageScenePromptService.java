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
                7. 多张图必须包含风格和角度变化：至少一张 3D 立体斜角/悬浮分层图，至少一张平铺图，至少一张近景细节图；如果数量不足 3，就优先保证一张立体斜角、一张平铺。
                8. 不要规划任何额外黑色小包、收纳袋、包装盒、卡片、未上传包装或未选择配件；酒精包只能按参考图形态出现。
                9. 如果基础提示词包含三星 S23U 镜头膜，所有场景都必须提醒右侧三个小孔大小不一致，不能做成等大。

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
            if (parsed == null || parsed.prompt() == null || parsed.prompt().isBlank() || containsForbiddenObject(parsed.prompt())) {
                normalized.add(fallback.get(i));
            } else {
                normalized.add(new ScenePrompt(
                        i + 1,
                        normalizeTitle(parsed.sceneTitle(), i + 1),
                        appendDiversityDirective(parsed.prompt().trim(), imageType, i, count)
                ));
            }
        }
        return normalized;
    }

    private boolean containsForbiddenObject(String prompt) {
        String normalized = prompt == null ? "" : prompt.replaceAll("\\s+", "").toLowerCase();
        return normalized.contains("小黑包")
                || normalized.contains("黑色小包")
                || normalized.contains("黑色包装")
                || normalized.contains("收纳袋")
                || normalized.contains("布袋")
                || normalized.contains("包装盒")
                || normalized.contains("额外包装")
                || normalized.contains("说明书")
                || normalized.contains("安装卡")
                || normalized.contains("卡片")
                || normalized.contains("托盘")
                || normalized.contains("pouch")
                || normalized.contains("bag")
                || normalized.contains("box")
                || normalized.contains("card")
                || normalized.contains("tray");
    }

    private List<ScenePrompt> fallbackScenes(String imageType, int count) {
        String[] mainScenes = {
                "3D 立体斜角主图，手机模型、屏幕膜和镜头膜悬浮分层展示，强调厚度、边缘高光和空间纵深；镜头膜右侧三个小孔必须不等大。",
                "高端平铺主图，45 度俯拍，产品与配件均衡分布，禁止额外黑色小包或未上传包装，镜头膜孔位清晰可见。",
                "近景精密结构展示，放大镜头膜和屏幕膜边缘，强调玻璃反光、边缘厚度和右侧三个小孔大小差异。",
                "斜俯拍金属科技背景，手机模型居中，屏幕膜与镜头膜形成前后层次，带真实阴影和轻微悬浮感。",
                "极简平台首图构图，主体大而清楚，保留真实套装数量和产品结构，背景干净适合 Amazon/Temu。"
        };
        String[] introScenes = {
                "3D 立体卖点介绍图，手机与保护膜分层悬浮，展示全屏覆盖、玻璃厚度和真实阴影，信息模块不要遮挡产品。",
                "平铺套装介绍图，严格只展示所选配件数量，酒精包按参考图形态出现，不要额外黑色小包、包装盒或卡片。",
                "镜头保护近景介绍图，放大一体式镜头膜，右侧三个小孔必须大小不一致，右中孔最小，禁止做成等大孔。",
                "易安装步骤感介绍图，展示除尘贴、无尘布、酒精包和屏幕膜安装关系，保持产品结构真实。",
                "高清透亮/防指纹卖点介绍图，斜角展示屏幕膜反光和疏油洁净效果，背景适合 Amazon/Temu。"
        };
        String[] source = "介绍图".equals(imageType) ? introScenes : mainScenes;
        List<ScenePrompt> scenes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            scenes.add(new ScenePrompt(i + 1, "场景" + (i + 1), source[i % source.length]));
        }
        return scenes;
    }

    private String appendDiversityDirective(String prompt, String imageType, int zeroBasedIndex, int total) {
        String directive = switch (zeroBasedIndex % 5) {
            case 0 -> "本张必须是 3D 立体斜角/悬浮分层场景，有明显厚度、真实阴影和空间纵深。";
            case 1 -> "本张必须是平铺/俯拍场景，物品摆放清楚，严格禁止额外黑色小包、收纳袋、包装盒或未选配件。";
            case 2 -> "本张必须是近景细节场景，放大镜头膜孔位和屏幕膜边缘，尤其保持 S23U 右侧三个小孔大小不一致。";
            case 3 -> "本张必须是斜俯拍商业场景，产品有前后层次和真实反射，不要纯平无纵深。";
            default -> "本张必须是干净平台风格场景，适合 Amazon/Temu，主体大而清晰，配件数量严格准确。";
        };
        if (total == 2 && zeroBasedIndex == 1) {
            directive = "本张必须是平铺/俯拍场景，与第 1 张立体斜角图形成明显区别，严格禁止额外包装或未选配件。";
        }
        String structureLock = "所有场景都必须保留上传图真实产品结构，不改变孔位数量、位置和大小差异。";
        return abbreviate(prompt + " " + directive + " " + structureLock, MAX_SCENE_PROMPT_CHARS);
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
