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

    public List<ScenePrompt> planScenes(String imageType, String basePrompt, int count) {
        int normalizedCount = Math.max(0, Math.min(MAX_SCENES, count));
        if (normalizedCount <= 0) {
            return List.of();
        }
        boolean requiresLensStructureLock = requiresLensStructureLock(basePrompt);
        if (normalizedCount == 1) {
            return fallbackScenes(imageType, normalizedCount, requiresLensStructureLock);
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
                2. 每个 prompt 控制在 600 个中文字符以内。
                3. 每张图必须是不同场景、构图、背景、光影、展示角度或卖点表达，避免重复。
                4. 不要改写产品真实结构，不要改变孔位数量/位置/大小差异，不要添加未选配件。
                5. 主图场景保持 Amazon/TEMU 首图质感，必须无文字、无图标、无角标、无卖点标签、无水印；介绍图可以更偏卖点表达和信息层级。
                6. prompt 只写本张图相对基础提示词需要变化的场景规划，不要重复粘贴完整基础提示词。
                7. 多张图必须包含风格和角度变化：至少一张 3D 立体斜角/悬浮分层图，至少一张平铺图，至少一张近景细节图；如果数量不足 3，就优先保证一张立体斜角、一张平铺。
                8. 场景 prompt 只写白名单内物品：手机/手机模型、上传实拍图对应的屏幕膜和镜头膜、已选择套装配件。不要加入任何支架、底座、托盘、展示道具、额外包装、收纳袋、包装盒、卡片、未上传包装或未选择配件；清洁/安装辅助配件只能按参考图形态、颜色、尺寸比例和可见文字出现。
                9. 如果基础提示词包含镜头膜，所有场景都必须提醒按上传图/深析结果保留对应机型的外轮廓、孔位数量、孔位位置和孔位大小差异，不能套用其他机型结构。
                10. 不要在场景 prompt 里写“额外包装、非参考图黑/白小袋、收纳袋、包装盒、卡片、托盘、支架、底座、展示道具”等高风险物品词；含这些词的场景会被丢弃。
                11. 如果是主图，多张之间必须显著裂变：至少包含钢化膜在手机左侧/右侧的摆放变化、俯拍/斜拍角度变化、轮廓光变化；若任务没有指定手机颜色，可以规划不同手机颜色，但不得改变机型。
                12. 如果场景里需要出现清洁/安装辅助配件，必须提醒按参考图复现形状、颜色、尺寸比例和可见文字；不能变成空白小袋、无字小袋或其他未上传配件形态。
                13. 如果基础提示词包含“排版图约束”，所有场景必须沿用排版图的主体区、配件区、信息区、留白、安全边距、网格/分栏和对齐关系；场景只允许在这些区域内改变光影、角度、背景和层次，不能重新散乱摆放。
                14. 所有场景都必须写明比例约束：手机完整入画；屏幕膜/钢化膜/防窥膜与手机屏幕长宽比一致、尺寸接近可覆盖屏幕区域；镜头膜只匹配后摄区域，不能接近半个手机；配件按参考图等比例缩放并整齐摆放。
                15. 近景或细节场景也不能裁掉主商品整体关系；需要局部细节时，用局部放大模块、边缘高光或旁侧特写表达，不要把手机主体裁残。

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
            directive = directive + " 主图必须无文字、无图标、无角标、无卖点标签、无水印。";
        }
        String structureLock = "所有场景都必须保留上传图真实产品结构，不改变孔位数量、位置和大小差异。";
        String lensStructureLock = requiresLensStructureLock
                ? "镜头膜必须按上传图/深析结果锁定对应机型结构，不改变孔位数量、位置、大小差异和一体式/分离式形态。"
                : "";
        String scaleLock = "手机完整入画并保留安全边距；屏幕膜与手机屏幕长宽比一致且尺寸匹配；镜头膜只匹配后摄区域，不能接近半个手机；配件按参考图等比例缩放并整齐对齐。";
        String layoutLock = "如基础提示词包含排版图约束，必须沿用排版图区域、网格/分栏、留白和对齐关系，场景不得重新散乱摆放。";
        String objectAudit = "只出现白名单物品；若场景联想到支架、底座、展示道具或额外包装，全部忽略。";
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
