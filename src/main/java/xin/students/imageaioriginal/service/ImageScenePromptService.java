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

    public List<ScenePrompt> planScenes(String imageType, String finalPrompt, int count) {
        int normalizedCount = Math.max(0, Math.min(MAX_SCENES, count));
        if (normalizedCount <= 0) {
            return List.of();
        }
        boolean requiresLensStructureLock = requiresLensStructureLock(finalPrompt);
        if (normalizedCount == 1) {
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock);
        }

        String requestId = UUID.randomUUID().toString().substring(0, 8);
        // 主图和介绍图会分别传入各自的最终生图提示词，场景规划必须基于当前类型独立生成。
        String prompt = buildPlannerPrompt(imageType, finalPrompt, normalizedCount);
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
            List<ScenePrompt> scenes = normalizeScenes(parseScenes(text), imageType, normalizedCount, requiresLensStructureLock);
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
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock);
        }
    }

    private String buildPlannerPrompt(String imageType, String finalPrompt, int count) {
        String safePrompt = abbreviate(finalPrompt == null ? "" : finalPrompt.trim(), MAX_FINAL_PROMPT_CHARS);
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
        3. 每张图必须在场景、构图、背景、光影、角度或卖点表达上明显不同；多张图至少包含：3D立体斜角安装态展示图、规整平铺图、近景细节图；数量不足3时优先保证立体斜角安装态和平铺。
        4. 每个场景都必须继承基础提示词中的产品结构、真实比例、安装关系、配件数量、禁改项和负面约束，不得因简写场景而改变或省略硬规则。
        5. 禁止规划镜头膜悬浮在后摄模组上方、后方或遮挡摄像头的场景；如果镜头膜与后摄手机同框展示，只允许两种关系：已精准安装到手机后摄镜头模组上，且孔内必须露出真实摄像头玻璃、闪光灯和传感器；或按真实比例平铺在手机旁边（注意镜头膜与手机的比例），不得挡住、盖住或替代摄像头、闪光灯、传感器。
        6. 近景或细节场景不能裁掉主商品整体关系；需要局部细节时，用旁侧局部放大模块、边缘高光或局部特写表达，不得改变安装关系、孔位数量、孔位大小或配件数量。
        7. 不得规划膜片位于摄像模组后方、手机中部、手机底部、手机另一侧无关区域，或任何会遮挡后摄镜头、闪光灯、传感器的位置。
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

    private List<ScenePrompt> normalizeScenes(
            List<ScenePrompt> parsedScenes,
            String imageType,
            int count,
            boolean requiresLensStructureLock
    ) {
        List<ScenePrompt> fallback = fallbackScenes(imageType, count, requiresLensStructureLock);
        List<ScenePrompt> normalized = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ScenePrompt parsed = i < parsedScenes.size() ? parsedScenes.get(i) : null;
            if (parsed == null || parsed.prompt() == null || parsed.prompt().isBlank() || containsForbiddenObject(parsed.prompt())) {
                normalized.add(fallback.get(i));
            } else {
                normalized.add(new ScenePrompt(
                        i + 1,
                        normalizeTitle(parsed.sceneTitle(), i + 1),
                        appendDiversityDirective(parsed.prompt().trim(), imageType, i, count, requiresLensStructureLock)
                ));
            }
        }
        return normalized;
    }

    boolean containsForbiddenObject(String prompt) {
        String normalized = prompt == null ? "" : prompt.replaceAll("\\s+", "").toLowerCase();
        return FORBIDDEN_OBJECT_TERMS.stream().anyMatch(normalized::contains);
    }

    List<ScenePrompt> fallbackScenes(String imageType, int count, boolean requiresLensStructureLock) {
        String cameraHoleLock = requiresLensStructureLock
                ? "镜头膜必须按上传图/深析结果保持对应机型的外轮廓、孔位数量、位置和大小差异，不套用其他手机型号。"
                : "产品结构按上传图/深析结果保持外轮廓、数量、位置和大小差异。";
        String[] mainScenes = {
                "无文字规整 3D 立体斜角主图，手机完整入画并保留安全边距，屏幕膜与手机屏幕同比例分层展示，镜头膜只在后摄区域大小范围内，配件按参考图等比例放入配件区；" + cameraHoleLock,
                "无文字高端平铺主图，45 度俯拍，手机完整显示，屏幕膜与手机左右分区且尺寸匹配，已选择配件按一行或网格整齐对齐，只出现白名单物品；" + cameraHoleLock,
                "无文字精密结构主图，保留完整手机和膜片关系，用旁侧局部放大/边缘高光强调钢化膜轮廓、镜头膜孔位和屏幕膜边缘，不裁残手机主体；" + cameraHoleLock,
                "无文字斜俯拍科技主图，主体区和配件区清晰分离，产品有前后层次、真实反射和阴影，排版整齐不散落；" + cameraHoleLock,
                "无文字极简 TEMU/Amazon 平台首图，主体大而清楚但完整入画，手机颜色在任务允许时与其他图不同，保留真实套装数量、比例和产品结构；" + cameraHoleLock
        };
        String[] introScenes = {
                "3D 立体卖点介绍图，手机与保护膜按真实比例分层展示，手机完整入画，信息模块按排版图区域或整齐分栏放置，不遮挡产品；" + cameraHoleLock,
                "平铺套装介绍图，严格只展示所选配件数量，清洁/安装辅助配件按参考图形态、颜色、尺寸比例和可见文字出现，所有物品按网格或分区整齐对齐；" + cameraHoleLock,
                "镜头保护介绍图，保留完整手机/后摄关系，用局部放大模块展示一体式镜头膜孔位，镜头膜比例只匹配后摄区域；" + cameraHoleLock,
                "易安装步骤感介绍图，展示已选择的清洁/安装辅助配件和屏幕膜安装关系；配件按参考图形态、颜色、尺寸比例和可见文字出现，版面整齐不散乱；" + cameraHoleLock,
                "高清透亮/防指纹卖点介绍图，手机完整显示，斜角展示屏幕膜反光和疏油洁净效果，背景适合 Amazon/Temu，产品比例真实；" + cameraHoleLock
        };
        String[] source = "介绍图".equals(imageType) ? introScenes : mainScenes;
        List<ScenePrompt> scenes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            scenes.add(new ScenePrompt(i + 1, "场景" + (i + 1), source[i % source.length]));
        }
        return scenes;
    }

    private String appendDiversityDirective(
            String prompt,
            String imageType,
            int zeroBasedIndex,
            int total,
            boolean requiresLensStructureLock
    ) {
        String directive = switch (zeroBasedIndex % 5) {
            case 0 -> "本张必须是规整 3D 立体斜角/分层场景，有明显厚度、真实阴影和空间纵深；钢化膜/屏幕膜与手机左右错位但比例一致。";
            case 1 -> "本张必须是平铺/俯拍场景，物品摆放清楚，手机与钢化膜左右位置要和第 1 张不同，严格禁止非参考图黑/白小袋、收纳袋、包装盒或未选配件。";
            case 2 -> "本张必须是结构细节场景，手机主体仍完整入画，用旁侧局部放大或边缘高光展示钢化膜轮廓光、镜头膜孔位和屏幕膜边缘。";
            case 3 -> "本张必须是斜俯拍商业场景，主体区和配件区清晰分离，产品有前后层次和真实反射，背景光影与前几张不同，不要纯平无纵深。";
            default -> "本张必须是干净平台风格场景，适合 Amazon/Temu，主体大而清晰但完整入画，配件数量和比例严格准确；如任务未指定手机颜色，可与其他图使用不同手机颜色。";
        };
        if (total == 2 && zeroBasedIndex == 1) {
            directive = "本张必须是平铺/俯拍场景，与第 1 张立体斜角图形成明显区别，手机与钢化膜左右位置互换且比例一致，严格禁止额外包装或未选配件。";
        }
        if ("主图".equals(imageType)) {
            //directive = directive + " 主图必须无文字、无图标、无角标、无卖点标签、无水印。";
        }
        String structureLock = "";
        String lensStructureLock = requiresLensStructureLock
                ? "不改变孔位数量、位置、大小差异和一体式/分离式形态。"
                : "";
        String scaleLock = "";
        String layoutLock = "";
        String objectAudit = "";
        return abbreviate(prompt + " " + directive + " " + structureLock + " " + lensStructureLock + " " + scaleLock + " " + layoutLock + " " + objectAudit, MAX_SCENE_PROMPT_CHARS);
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
