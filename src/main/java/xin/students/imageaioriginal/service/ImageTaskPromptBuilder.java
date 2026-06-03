package xin.students.imageaioriginal.service;

import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.model.ImageTaskKitSpec;
import xin.students.imageaioriginal.model.ImageTaskPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ImageTaskPromptBuilder {

    private static final int DEFAULT_IMAGE_SIZE = 1536;
    private static final int MAX_ANALYSIS_PROMPT_CHARS = 1800;
    private static final String CUSTOMER_ALLOWED_PRODUCT_TYPES = "手机、钢化膜、高清膜、防窥膜、镜头膜";
    private static final String CUSTOMER_ALLOWED_ACCESSORIES = "任务已上传或已选择的手机膜相关清洁/安装辅助配件";
    private static final String ACCESSORY_REFERENCE_RULE = "所有手机膜相关配件都只能按已上传或已选择的参考图生成：配件参考图是图像结构约束，不是自由创作对象；必须保留参考图的真实外形、颜色、材质、尺寸比例、封边/标签区域、图案和可见文字。可见文字属于配件外观的一部分，如果参考图有字，只复现参考图上可见的字；如果字很小或略模糊，也要保留文字块的位置、颜色对比和行列排版，不要变成光滑空白块、无字小包或泛化替代品；不要凭空加字、改字或套用其他配件的形状。";
    private static final String USAGE_MAIN = "MAIN";
    private static final String USAGE_INTRO = "INTRO";

    private final ExtraAccessoryService extraAccessoryService;

    public ImageTaskPromptBuilder(ExtraAccessoryService extraAccessoryService) {
        this.extraAccessoryService = extraAccessoryService;
    }

    public String buildGenerationPrompt(
            String imageType,
            String basePrompt,
            ImageTaskPayload payload,
            Map<String, String> analysis,
            UploadMaterialContext uploadMaterialContext,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("【最高优先级：结构锁定】\n");
        builder.append("上传参考图 > 深析结果 > 套装数量 > 任务参数 > 模板风格。先还原真实产品结构，再做电商美化。\n");
        builder.append("不得改成通用款；不得统一大小不同的孔位；不得增加、删除、移动或遮挡孔位、缺口、外轮廓和配件。\n\n");

        builder.append("【上传图深析结果】\n");
        Map<String, String> structureAnalysis = structureAnalysis(analysis);
        structureAnalysis.forEach((label, result) -> builder.append("[").append(label).append("]\n")
                .append(abbreviate(normalizeNullable(result), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n"));
        if (structureAnalysis.isEmpty()) {
            builder.append("未提供实拍图结构深析；若已上传实拍图，以上传参考图的真实产品结构为最高优先级。\n");
        }
        builder.append("\n");

        builder.append("【手机膜结构规则】\n");
        builder.append("镜头膜/屏幕膜按上传图的外轮廓、孔位数量、孔位位置、孔位大小生成。若小孔大小不同或结构非对称，必须保留差异；禁止做成等大、等距、分离镜圈或标准圆环。孔洞内不要添加不存在的镜片、金属圈、螺丝、图标或文字。\n\n");
        appendCameraProtectorCriticalRules(builder, payload, analysis);
        appendAllowedObjectsContext(builder, payload, uploadMaterialContext, imageType);

        appendKitLock(builder, payload);

        builder.append("【任务参数】\n");
        builder.append("【平台】").append(normalizeText(payload.platform(), "Amazon")).append("\n");
        builder.append("【尺寸】")
                .append(normalizeImageDimension(payload.customWidth(), DEFAULT_IMAGE_SIZE))
                .append("x")
                .append(normalizeImageDimension(payload.customHeight(), DEFAULT_IMAGE_SIZE))
                .append("\n");
        builder.append("【语言】").append(normalizeText(payload.language(), "英文")).append("\n");
        builder.append("【机型】").append(normalizeText(payload.model(), "根据上传图自动识别")).append("\n");
        builder.append("【手机颜色】").append(normalizeText(payload.phoneColor(), "自动")).append("\n");
        builder.append("【设计风格】").append(normalizeText(payload.style(), "自动")).append("\n");
        builder.append("【布局模式】").append(normalizeText(payload.layout(), "自动")).append("\n");
        appendLogoContext(builder, payload, uploadMaterialContext, imageType);
        appendWallpaperContext(builder, payload, uploadMaterialContext, imageType);
        builder.append("【卖点】").append(joinList(payload.sellingPoints())).append("\n");
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        builder.append("【套装规格】").append(kitSpecText).append("\n");
        String productTypeText = productTypeText(payload);
        if (!"未选择".equals(productTypeText)) {
            builder.append("【产品类型】").append(productTypeText).append("\n");
        }
        appendFilmTypeLock(builder, payload);
        builder.append("\n【").append(imageType).append("画面要求】\n");
        builder.append(normalizeText(basePrompt, "生成跨境电商图片。")).append("\n");
        builder.append("画面要求只控制构图、光影和质感，不得改写真实产品结构。\n");
        appendUploadedTemplateContext(builder, imageType, payload, analysis, uploadMaterialContext);
        appendTargetTemplateContext(builder, imageType, targetTemplate);
        builder.append("【视觉特效】加强玻璃高光、材质反射、柔和阴影和轻微3D纵深，但不能遮挡或改变产品结构。\n");
        builder.append("\n【负面约束】\n");
        builder.append("不要通用款；不要标准化异形镜头膜；不要把不同大小小孔做成同样大小；不要把一体式镜头膜改成分离圆环；不要添加未选配件、额外孔、额外镜片、额外包装、包装袋、包装盒、纸盒、礼盒、收纳袋、非参考图黑/白小袋、卡片、托盘、支架、底座、展示道具、未上传或未选择用于当前类型的Logo、水印或装饰文字（参考图配件自身文字除外）。\n");
        builder.append("\n【生成前自检清单】\n");
        builder.append("1. 镜头膜孔位数量、位置、大小差异是否与上传实拍图和深析结果一致；" +
                "2. 是否没有套用其他手机型号镜头膜结构；3. 是否只出现允许物品；4. 是否没有额外黑/白小袋、包装盒、支架、底座、展示道具；" +
                "5. 若出现清洁/安装辅助配件，是否与参考图形状、颜色、尺寸比例和可见文字一致；" +
                "6. 套装配件数量是否严格正确；7. 至少当前场景的角度、纵深或光影与其他图片不同。\n");
        builder.append("\n【生成要求】结合上传图深析、任务参数和规格生成；必须包含与机型一致的手机或手机模型；套装配件严格按数量出现，未选择的配件不要出现；不要编造不可见细节。");
        return builder.toString();
    }

    public String generationItemPrompt(
            String basePrompt,
            String resultType,
            int index,
            int total,
            ImageScenePromptService.ScenePrompt scene,
            ImageTaskPayload payload
    ) {
        StringBuilder builder = new StringBuilder(basePrompt);
        builder.append("\n\n【当前生成】").append(resultType).append("第 ").append(index).append(" / ").append(total).append(" 张。");
        if (scene != null && scene.prompt() != null && !scene.prompt().isBlank()) {
            builder.append("\n【本张图片场景规划】\n");
            builder.append("场景标题：").append(normalizeText(scene.sceneTitle(), "场景" + index)).append("\n");
            builder.append("场景描述：").append(scene.prompt()).append("\n");
            builder.append("本张图必须与同任务其他图片形成不同场景；只允许改变构图、背景、光影、展示角度或卖点表达，不得改变上传图产品结构、孔位、配件数量和套装规格。");
        }
        appendPerImageSelfAudit(builder, payload, basePrompt, resultType, index);
        return builder.toString();
    }

    public String generationBasePrompt(String value, String fallback, String analysisPrompt) {
        if (looksLikeAnalysisPrompt(value)) {
            return normalizeText(fallback, "生成跨境电商图片。");
        }
        return normalizeText(value, fallback);
    }

    private Map<String, String> structureAnalysis(Map<String, String> analysis) {
        Map<String, String> values = new LinkedHashMap<>();
        if (analysis == null || analysis.isEmpty()) {
            return values;
        }
        analysis.forEach((label, result) -> {
            if (!"排版图".equals(label) && result != null && !result.isBlank()) {
                values.put(label, result);
            }
        });
        return values;
    }

    private void appendCameraProtectorCriticalRules(
            StringBuilder builder,
            ImageTaskPayload payload,
            Map<String, String> analysis
    ) {
        String allAnalysis = analysis == null
                ? ""
                : String.join("\n", analysis.values().stream().filter(value -> value != null && !value.isBlank()).toList());
        if (!hasLensProtector(payload, allAnalysis)) {
            return;
        }
        builder.append("【镜头膜关键结构智能锁定】\n");
        builder.append("镜头膜必须按上传图和深析结果识别当前手机型号，不要套用任意其他品牌或型号的通用镜头膜结构。\n");
        builder.append("必须锁定：一体式片状或分离镜圈形态、外轮廓、异形边缘、缺口/台阶、孔位数量、孔位相对位置、每个孔位大小差异。\n");
        builder.append("如果深析结果写明某些孔位大小不同、排列不对称或外轮廓有特殊凹凸，必须保留这些差异；不能把不同大小孔做成等大，也不能增加/删除/移动孔位。\n");
        builder.append("如果画面较小导致看不清，必须放大镜头膜或用近景展示孔位差异；孔洞保持真实贯穿开孔，不填入额外镜片、黑色圆点或装饰圈。\n\n");
    }

    private void appendAllowedObjectsContext(
            StringBuilder builder,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext,
            String imageType
    ) {
        builder.append("【允许出现物品白名单】\n");
        builder.append("只允许出现：与机型匹配的手机/手机模型、上传实拍图对应的屏幕膜、上传实拍图对应的一体式镜头膜");
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        if (!"未选择".equals(kitSpecText)) {
            builder.append("、套装配件（").append(kitSpecText).append("）");
        }
        if (hasUsableLogoImage(payload, uploadMaterialContext, imageType)) {
            builder.append("、已上传且选择当前类型使用的Logo图");
        }
        if (hasUsableWallpaperImage(payload, uploadMaterialContext, imageType)) {
            builder.append("、已上传且选择当前类型使用的壁纸图");
        }
        builder.append("。\n");
        builder.append("客户明确可用产品范围：").append(CUSTOMER_ALLOWED_PRODUCT_TYPES).append("；可用配件范围：").append(CUSTOMER_ALLOWED_ACCESSORIES).append("。Logo/壁纸仅在上方白名单列出时允许出现；未选择、未上传或不在这个范围内的物品都不要出现。\n");
        builder.append("除上述白名单外，不要生成任何额外包装、包装袋、非参考图黑/白小袋、收纳袋、包装盒、纸盒、礼盒、安装卡、说明书、托盘、支架、底座、展示道具、未选择贴纸或未上传配件。\n");
        appendAccessoryReferenceRule(builder, kitSpecText);
        builder.append("\n");
    }

    private void appendPromptAssetWhitelist(StringBuilder builder, String basePrompt) {
        String normalizedPrompt = normalizeNullable(basePrompt);
        if (normalizedPrompt.contains("【Logo】已上传Logo参考图")
                || normalizedPrompt.contains("已上传Logo参考图")) {
            builder.append("、已上传且当前类型启用的Logo图");
        }
        if (normalizedPrompt.contains("【壁纸】已上传壁纸参考图")
                || normalizedPrompt.contains("已上传壁纸参考图")) {
            builder.append("、已上传且当前类型启用的壁纸图");
        }
    }

    private void appendAccessoryReferenceRule(StringBuilder builder, String kitSpecText) {
        if (hasSelectedAccessory(kitSpecText)) {
            builder.append(ACCESSORY_REFERENCE_RULE).append("\n");
        } else {
            builder.append("未选择或上传清洁/安装辅助配件时，不要生成任何小袋、清洁包、湿巾包、除尘贴、无尘布、定位器、刮板、辅助贴或防滑垫。\n");
        }
    }

    private boolean hasSelectedAccessory(String kitSpecText) {
        String normalized = normalizeNullable(kitSpecText).toLowerCase();
        return !"未选择".equals(normalized)
                && (normalized.contains("酒精")
                || normalized.contains("清洁")
                || normalized.contains("湿巾")
                || normalized.contains("除尘")
                || normalized.contains("无尘")
                || normalized.contains("定位")
                || normalized.contains("刮板")
                || normalized.contains("辅助贴")
                || normalized.contains("防滑")
                || normalized.contains("wet wipes")
                || normalized.contains("wipe"));
    }

    private void appendFilmTypeLock(StringBuilder builder, ImageTaskPayload payload) {
        boolean hasHdFilm = hasHdFilm(payload);
        boolean hasPrivacyFilm = hasPrivacyFilm(payload);
        if (!hasHdFilm && !hasPrivacyFilm) {
            return;
        }
        builder.append("【膜类型锁定】\n");
        if (hasHdFilm) {
            builder.append("高清/钢化膜必须表现为透明、清亮、高透玻璃质感，可以有浅蓝边缘高光；不要生成成褐色、灰黑色、暗色防窥膜。\n");
        }
        if (hasPrivacyFilm) {
            builder.append("防窥膜必须表现为深色、灰黑或轻微褐色防窥质感，从斜角可见暗色防窥效果；不要生成成完全透明的高清膜。\n");
        }
        if (hasHdFilm && hasPrivacyFilm) {
            builder.append("同一套装同时包含高清/钢化膜和防窥膜时，两类膜要按数量分开摆放并用材质颜色区分，不能互相替换或合并。\n");
        }
        builder.append("\n");
    }

    private void appendPerImageFilmTypeAudit(StringBuilder builder, ImageTaskPayload payload) {
        boolean hasHdFilm = hasHdFilm(payload);
        boolean hasPrivacyFilm = hasPrivacyFilm(payload);
        if (!hasHdFilm && !hasPrivacyFilm) {
            return;
        }
        builder.append("膜类型自检：");
        if (hasHdFilm) {
            builder.append("高清/钢化膜保持透明清亮，不要变成褐色或暗色防窥膜；");
        }
        if (hasPrivacyFilm) {
            builder.append("防窥膜保持深色/灰黑/轻微褐色防窥质感，不要变成普通透明膜；");
        }
        builder.append("\n");
    }

    private boolean hasHdFilm(ImageTaskPayload payload) {
        if (Boolean.TRUE.equals(payload.hdEnabled()) && positive(payload.hdQuantity()) > 0) {
            return true;
        }
        return hasKitSpecName(payload, "高清") || hasKitSpecName(payload, "钢化膜");
    }

    private boolean hasPrivacyFilm(ImageTaskPayload payload) {
        if (Boolean.TRUE.equals(payload.privacyEnabled()) && positive(payload.privacyQuantity()) > 0) {
            return true;
        }
        return hasKitSpecName(payload, "防窥");
    }

    private boolean hasKitSpecName(ImageTaskPayload payload, String keyword) {
        if (payload.kitSpecs() == null || payload.kitSpecs().isEmpty()) {
            return false;
        }
        return payload.kitSpecs().stream()
                .filter(spec -> spec != null && positive(spec.quantity()) > 0)
                .map(ImageTaskKitSpec::name)
                .filter(name -> name != null && !name.isBlank())
                .anyMatch(name -> name.contains(keyword));
    }

    private boolean hasLensProtector(ImageTaskPayload payload, String analysisText) {
        String normalizedAnalysis = normalizeNullable(analysisText);
        return normalizedAnalysis.contains("镜头膜")
                || normalizedAnalysis.contains("镜头保护")
                || joinList(payload.sellingPoints()).contains("镜头保护");
    }

    private void appendKitLock(StringBuilder builder, ImageTaskPayload payload) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        if ("未选择".equals(kitSpecText)) {
            return;
        }
        builder.append("【套装规格数量锁定】").append(kitSpecText).append("\n");
        builder.append("套装规格里的每一种配件都必须按数量准确出现：数量为 1 只出现 1 个，数量为 2 只出现 2 个；未选择的配件不要出现。\n");
        appendAccessoryReferenceContext(builder, payload.kitSpecs());
        builder.append("\n");
    }

    private void appendAccessoryReferenceContext(StringBuilder builder, List<ImageTaskKitSpec> specs) {
        List<ExtraAccessoryService.ExtraAccessoryRecord> accessories = accessoryRecords(specs);
        if (accessories.isEmpty()) {
            builder.append("【配件参考图】未找到已保存的配件图片，请仅按套装规格文字生成。\n");
            return;
        }
        builder.append("【配件参考图】已将以下配件图片作为生图参考图传入：")
                .append(String.join("、", accessories.stream().map(ExtraAccessoryService.ExtraAccessoryRecord::name).toList()))
                .append("。生成时必须参考对应配件图片的形状、颜色、材质、封边/标签区域、图案和可见文字，并按套装规格数量准确摆放；可见文字不要省略、改字或替换成空白块。\n");
    }

    private void appendUploadedTemplateContext(
            StringBuilder builder,
            String imageType,
            ImageTaskPayload payload,
            Map<String, String> analysis,
            UploadMaterialContext uploadMaterialContext
    ) {
        if (uploadMaterialContext == null
                || !uploadMaterialContext.hasTemplateImage()
                || !usesUploadAsset(payload.templateUsages(), imageType)) {
            return;
        }
        String styleAnalysis = normalizeNullable(analysis == null ? null : analysis.get("排版图"));
        if (styleAnalysis.isBlank()) {
            builder.append("【").append(imageType).append("上传排版图约束】上传排版图已作为低优先级布局参考图传入；必须参考其构图、背景、光影、空间层次和排版风格，但不得改变上传实拍图产品结构、孔位、外轮廓和配件数量。\n");
            return;
        }
        builder.append("【").append(imageType).append("上传排版图风格】")
                .append(abbreviate(styleAnalysis, MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n");
        builder.append("【").append(imageType).append("上传排版图约束】上传排版图已作为低优先级布局参考图传入；只应用构图、背景、光影、空间层次和排版风格；不要照抄模板中的商品、品牌、文字或图标；不得改变上传实拍图产品结构、孔位、外轮廓和配件数量。\n");
    }

    private void appendTargetTemplateContext(
            StringBuilder builder,
            String imageType,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
        if (targetTemplate == null) {
            return;
        }
        builder.append("【").append(imageType).append("排版模板风格】")
                .append(abbreviate(normalizeNullable(targetTemplate.styleAnalysis()), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n");
        builder.append("【").append(imageType).append("排版模板约束】排版模板图已作为低优先级布局参考图传入；模板只作构图、背景、光影、空间层次和排版风格参考，不得改变上传图孔位、外轮廓、配件数量和产品结构。\n");
    }

    private void appendPerImageSelfAudit(
            StringBuilder builder,
            ImageTaskPayload payload,
            String basePrompt,
            String resultType,
            int index
    ) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        builder.append("\n【本张成品自审与修正】\n");
        builder.append("本张只允许出现：与机型匹配的手机/手机模型、上传实拍图对应的屏幕膜、上传实拍图对应的一体式镜头膜");
        if (!"未选择".equals(kitSpecText)) {
            builder.append("、已选择套装配件（").append(kitSpecText).append("）");
        }
        appendPromptAssetWhitelist(builder, basePrompt);
        builder.append("。\n");
        builder.append("客户物品范围只包含产品（").append(CUSTOMER_ALLOWED_PRODUCT_TYPES).append("）和配件（").append(CUSTOMER_ALLOWED_ACCESSORIES).append("）；没有选择或上传的同类物品也不要生成。\n");
        builder.append("若场景规划、排版模板风格或模型联想引入包装盒、包装袋、收纳袋、非参考图黑/白小袋、托盘、卡片、支架、底座、展示道具、未选择贴纸或未选配件，全部视为错误并不要生成。\n");
        appendAccessoryReferenceRule(builder, kitSpecText);
        if (hasLensProtector(payload, basePrompt)) {
            builder.append("镜头膜结构再次自检：按上传图/深析结果锁定当前机型的外轮廓、孔位数量、孔位位置、孔位大小差异，以及一体式片状或分离镜圈形态；不要套用其他手机型号镜头膜结构。\n");
        }
        appendPerImageFilmTypeAudit(builder, payload);
        builder.append(resultType).append("第 ").append(index).append(" 张生成前先完成自查，结构锁定优先于场景创意和模板风格。");
    }

    private List<ExtraAccessoryService.ExtraAccessoryRecord> accessoryRecords(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .map(this::accessoryRecord)
                .filter(record -> record != null && record.content() != null && record.content().length > 0)
                .toList();
    }

    private ExtraAccessoryService.ExtraAccessoryRecord accessoryRecord(ImageTaskKitSpec spec) {
        if (spec == null || spec.accessoryId() == null || spec.accessoryId() <= 0 || positive(spec.quantity()) <= 0) {
            return null;
        }
        return extraAccessoryService.findRecord(spec.accessoryId());
    }

    private boolean hasUsableLogoImage(ImageTaskPayload payload, UploadMaterialContext uploadMaterialContext, String imageType) {
        return uploadMaterialContext != null
                && uploadMaterialContext.hasLogoImage()
                && usesUploadAsset(payload.logoUsages(), imageType);
    }

    private boolean hasUsableWallpaperImage(ImageTaskPayload payload, UploadMaterialContext uploadMaterialContext, String imageType) {
        return uploadMaterialContext != null
                && uploadMaterialContext.hasWallpaperImage()
                && usesUploadAsset(payload.wallpaperUsages(), imageType);
    }

    private boolean usesUploadAsset(List<String> usages, String imageType) {
        String usageCode = usageCode(imageType);
        if (usageCode.isBlank()) {
            return false;
        }
        return normalizeUsageList(usages).contains(usageCode);
    }

    private String usageCode(String imageType) {
        return switch (imageType == null ? "" : imageType) {
            case "主图" -> USAGE_MAIN;
            case "介绍图" -> USAGE_INTRO;
            default -> "";
        };
    }

    private List<String> normalizeUsageList(List<String> usages) {
        if (usages == null || usages.isEmpty()) {
            return List.of(USAGE_MAIN, USAGE_INTRO);
        }
        List<String> normalized = usages.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toUpperCase())
                .filter(value -> USAGE_MAIN.equals(value) || USAGE_INTRO.equals(value))
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of(USAGE_MAIN, USAGE_INTRO) : normalized;
    }

    private void appendLogoContext(
            StringBuilder builder,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext,
            String imageType
    ) {
        boolean hasLogoImage = hasUsableLogoImage(payload, uploadMaterialContext, imageType);
        String logoName = normalizeNullable(payload.logoName());
        if (logoName.isEmpty() && !hasLogoImage) {
            return;
        }
        builder.append("【Logo】");
        if (!logoName.isEmpty()) {
            builder.append(logoName);
        }
        if (hasLogoImage) {
            if (!logoName.isEmpty()) {
                builder.append("；");
            }
            builder.append("已上传Logo参考图，生成时必须直接按原图外观贴到画面/产品展示中，保留原图可见文字、图形、颜色和比例；不要识别、重绘、改字、换色、生成相似Logo或编造品牌");
        }
        builder.append("\n");
    }

    private void appendWallpaperContext(
            StringBuilder builder,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext,
            String imageType
    ) {
        boolean hasWallpaperImage = hasUsableWallpaperImage(payload, uploadMaterialContext, imageType);
        if (!hasWallpaperImage) {
            return;
        }
        builder.append("【壁纸】已上传壁纸参考图，生成时直接把原图贴到手机屏幕或屏幕展示区域，不要识别、重绘、换图、只借风格或生成相似壁纸");
        builder.append("\n");
    }

    private String productTypeText(ImageTaskPayload payload) {
        int hdQuantity = Boolean.TRUE.equals(payload.hdEnabled()) ? positive(payload.hdQuantity()) : 0;
        int privacyQuantity = Boolean.TRUE.equals(payload.privacyEnabled()) ? positive(payload.privacyQuantity()) : 0;
        List<String> items = new ArrayList<>();
        if (hdQuantity > 0) {
            items.add("高清 x " + hdQuantity);
        }
        if (privacyQuantity > 0) {
            items.add("防窥 x " + privacyQuantity);
        }
        return items.isEmpty() ? "未选择" : String.join("、", items);
    }

    private String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "未选择";
        }
        return String.join("、", values.stream().filter(value -> value != null && !value.isBlank()).toList());
    }

    private String joinKitSpecs(List<ImageTaskKitSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "未选择";
        }
        List<String> items = specs.stream()
                .filter(spec -> spec != null && spec.name() != null && !spec.name().isBlank())
                .filter(spec -> positive(spec.quantity()) > 0)
                .map(spec -> spec.name() + " x " + positive(spec.quantity()))
                .toList();
        return items.isEmpty() ? "未选择" : String.join("、", items);
    }

    private int normalizeImageDimension(Integer value, int fallback) {
        int numericValue = positiveOrDefault(value, fallback);
        if (numericValue < 300) {
            return fallback;
        }
        return Math.max(304, Math.round((float) numericValue / 16) * 16);
    }

    private int positive(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String normalizeNullable(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean looksLikeAnalysisPrompt(String value) {
        String normalized = normalizeNullable(value);
        return normalized.contains("专业电商手机膜产品图分析师")
                || normalized.contains("只分析图片中与手机膜产品相关")
                || normalized.contains("请按从整体到局部的顺序")
                || normalized.contains("输出要求：");
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
