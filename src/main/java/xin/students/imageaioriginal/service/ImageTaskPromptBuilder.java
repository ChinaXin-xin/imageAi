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
    private static final String SCALE_LOCK_RULE = "以手机机身/屏幕为全图比例尺：屏幕膜/钢化膜/防窥膜必须与当前手机屏幕长宽比一致，视觉尺寸接近可覆盖屏幕区域，不要变成只有手机一半大小、过宽、过窄或随机矩形；镜头膜只匹配后摄镜头区域，尺寸明显小于整机机身，不要放大到接近半个手机；清洁/安装辅助配件按参考图等比例缩放，不能比手机膜或手机主体更抢画面。";
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
        builder.append("# 【最高优先级：结构与真实安装关系锁定】\n");
        builder.append("如果生成摄像头，禁止漏生成摄像头（超级重点！！）；同一张图内如果是手机的正反面，厚度和长宽高要保持一致，手机膜宽高要与手机大小保持一致（超级重点！！！）。\n");
        builder.append("上传参考图 > 深析结果 > 套装数量 > 任务参数 > 模板风格。先还原真实产品结构和真实安装关系，再做电商美化。\n");
        builder.append("手机型号必须与任务参数一致；手机、屏幕膜、镜头膜和配件必须保持真实比例，统一透视（手机膜的透明度）、统一光照和合理空间关系。\n");
        builder.append("镜头膜必须匹配后摄模组尺寸和位置；若与手机同画面出现，如果下面提示词中没有特殊要求，默认直接精准安装到后摄镜头模组上。镜头膜不是替代摄像头；保持真实安装关系，不得放大、缩小、偏移到无关区域。\n");

        builder.append("后摄模组的摄像头数量、孔位布局、大小关系和整体位置必须与目标机型一致；不得少摄像头、漏生成后摄模组。\n");
        builder.append("不得改成通用款；不得统一大小不同的孔位；\n");
        builder.append("排版必须规整清晰，主体区、配件区和留白关系稳定；不得散乱摆放、遮挡主体、裁切手机或破坏安全边距，显示手机必须显示全！\n\n");

        builder.append("镜头膜上的所有圆孔必须是镂空贯穿开孔，不是透明膜片、不是灰色圆片、" +
                "不是实心圆、孔内不能有任何额外薄膜、必须先生成完整手机原生后摄模组，" +
                "安装后孔内必须清楚露出下方真实摄像头玻璃、" +
                "闪光灯和传感器，不得漏掉镜头膜下方对应的摄像头模组，也不得把孔位画成空洞、黑点或无内容圆孔。\n");
        builder.append("排版必须规整清晰，主体区、配件区和留白关系稳定；不得散乱摆放、遮挡主体、裁切手机或破坏安全边距。\n\n");

        builder.append("# 【上传图深析结果】\n");
        Map<String, String> structureAnalysis = structureAnalysis(analysis);
        structureAnalysis.forEach((label, result) -> builder.append("[").append(label).append("]\n")
                .append(abbreviate(normalizeNullable(result), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n"));
        if (structureAnalysis.isEmpty()) {
            builder.append("未提供实拍图结构深析；若已上传实拍图，以上传参考图的真实产品结构为最高优先级。\n");
        }
        builder.append("\n");

        // builder.append("【手机膜结构规则】\n");
        // builder.append("镜头膜/屏幕膜按上传图的外轮廓、孔位数量、孔位位置、孔位大小生成。若小孔大小不同或结构非对称，必须保留差异；禁止做成等大、等距、分离镜圈或标准圆环。孔洞内不要添加不存在的镜片、金属圈、螺丝、图标或文字。\n\n");
        appendCameraProtectorCriticalRules(builder, payload, analysis);
        appendReferenceRoleAndScaleLayoutLock(builder, imageType, payload, uploadMaterialContext);
        appendAllowedObjectsContext(builder, payload, uploadMaterialContext, imageType);

        appendKitLock(builder, payload);
        boolean hasUploadedTemplate = hasUploadedTemplateForType(payload, uploadMaterialContext, imageType);

        builder.append("# 【任务参数】\n");
        builder.append("【平台】").append(normalizeText(payload.platform(), "Amazon")).append("\n");
        builder.append("【尺寸】").append(imageSizeText(payload, imageType, hasUploadedTemplate)).append("\n");
        builder.append("【语言】").append(normalizeText(payload.language(), "英文")).append("\n");
        builder.append("【机型】").append(normalizeText(payload.model(), "根据上传图自动识别")).append("\n");
        builder.append("【手机颜色】").append(normalizeText(payload.phoneColor(), "自动")).append("\n");
        builder.append("【设计风格】").append(normalizeText(payload.style(), "自动")).append("\n");
        builder.append("【布局模式】").append(normalizeText(payload.layout(), "自动")).append("\n");
        appendWallpaperContext(builder, payload, uploadMaterialContext, imageType);
        builder.append("【卖点】").append(joinList(payload.sellingPoints())).append("\n");
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        builder.append("【套装规格】").append(kitSpecText).append("\n");
        String productTypeText = productTypeText(payload);
        if (!"未选择".equals(productTypeText)) {
            builder.append("【产品类型】").append(productTypeText).append("\n");
        }
        appendFilmTypeLock(builder, payload);
        if (hasUploadedTemplate) {
            appendTemplateFillPrompt(builder, imageType);
        } else {
            builder.append("\n# 【").append(imageType).append("画面要求】\n");
            builder.append(normalizeText(basePrompt, "生成跨境电商图片。")).append("\n");
            // 参考风格图风格紧跟当前类型的主图/介绍图提示词，避免后面的固定约束把用户选择的风格稀释。
            appendTargetTemplateContext(builder, imageType, targetTemplate);
            builder.append("画面要求只控制构图、光影和质感，不得改写真实产品结构。\n");
        }
        appendUploadedTemplateContext(builder, imageType, payload, uploadMaterialContext);
        builder.append("\n# 【负面约束】\n");
        builder.append("不要添加未选配件、额外孔、额外镜片、额外包装、包装袋、包装盒、纸盒、礼盒、收纳袋、非参考图黑/白小袋、卡片、托盘、支架、底座、展示道具、水印或装饰文字（参考图配件自身文字除外）。\n");
        /*builder.append("\n# 【生成前自检清单】\n");
        builder.append("1. 镜头膜孔位数量、位置、大小差异是否与上传实拍图和深析结果一致；" +
                "2. 是否没有套用其他手机型号镜头膜结构；3. 是否只出现允许物品；4. 是否没有额外黑/白小袋、包装盒、支架、底座、展示道具；" +
                "5. 若出现清洁/安装辅助配件，是否与参考图形状、颜色、尺寸比例和可见文字一致；" +
                "6. 套装配件数量是否严格正确；7. 手机是否完整入画，屏幕膜是否与手机屏幕比例一致，镜头膜是否只匹配后摄区域；" +
                "8. 如启用排版图，是否按排版图区域、对齐关系和安全边距填图；\n");
        builder.append("\n【生成要求】结合上传图深析、任务参数和规格生成；必须包含与机型一致的手机或手机模型；套装配件严格按数量出现，未选择的配件不要出现；不要编造不可见细节。");*/
        return builder.toString();
    }

    public String generationItemPrompt(
            String finalPrompt,
            String resultType,
            int index,
            int total,
            ImageScenePromptService.ScenePrompt scene,
            ImageTaskPayload payload
    ) {
        return generationItemPrompt(finalPrompt, resultType, index, total, scene, payload, false);
    }

    public String generationItemPrompt(
            String finalPrompt,
            String resultType,
            int index,
            int total,
            ImageScenePromptService.ScenePrompt scene,
            ImageTaskPayload payload,
            boolean hasUploadedTemplate
    ) {
        // 先保留主图/介绍图各自完整的“最终生图提示词”，再按当前类型是否启用排版图追加本张专用约束。
        StringBuilder builder = new StringBuilder(finalPrompt);
        builder.append("\n\n【当前生成】").append(resultType).append("第 ").append(index).append(" / ").append(total).append(" 张。");
        if (hasUploadedTemplate) {
            appendPerImageTemplateFillPrompt(builder, resultType, index);
        } else if (scene != null && scene.prompt() != null && !scene.prompt().isBlank()) {
            // 场景规划追加在原最终提示词之后，只控制当前图片的构图、背景、光影、角度或卖点表达。
            builder.append("\n# 【本张图片场景规划】\n");
            //builder.append("场景标题：场景").append(index).append("\n");
            builder.append("场景描述：").append(scene.prompt()).append("\n");
            builder.append("");
        }
        appendPerImageAccessoryDisplayRule(builder, payload, resultType, index, total, hasUploadedTemplate);
        appendPerImageSelfAudit(builder, payload, finalPrompt, resultType, index);
        return builder.toString();
    }

    private void appendPerImageTemplateFillPrompt(StringBuilder builder, String resultType, int index) {
        builder.append("\n# 【本张图片排版图填充生成要求】\n");
        builder.append("本张不使用原有场景规划；基于排版图填充上传素材并生成完整电商图。\n");
        builder.append("排版图/样板图是本次生图接口参考图中的最后一张输入图片，必须把最后一张图片作为版式模板：严格遵循其中的画面结构、主体位置、配件位置、留白比例、构图关系、对齐方式、层级和安全边距。\n");
        builder.append("将前面输入的用户上传产品图、实拍结构、配件图、壁纸图等素材合理填充到排版图对应区域；只替换为本任务素材，不照抄排版图中的示例商品、品牌、文字、图标或装饰元素。\n");
        builder.append("如排版图与原始画面排版要求、场景规划或参考风格存在冲突，以排版图为最高版式优先级；但不得改变实拍图产品结构、孔位、真实比例、配件数量和套装规格。\n");
        builder.append(resultType).append("第 ").append(index).append(" 张必须按最后一张排版图完成素材填充，不要重新散乱摆放。\n");
    }

    private void appendPerImageAccessoryDisplayRule(
            StringBuilder builder,
            ImageTaskPayload payload,
            String resultType,
            int index,
            int total,
            boolean hasUploadedTemplate
    ) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        boolean hasKit = !"未选择".equals(kitSpecText);
        if ("主图".equals(resultType) && total > 1 && index == 1) {
            builder.append("\n# 【本张配件展示规则】\n");
            builder.append("本张必须是套装合集图：手机、膜片/镜头膜");
            if (hasKit) {
                builder.append("和所有已选择套装配件（").append(kitSpecText).append("）");
            } else {
                builder.append("和任务中已选择/上传的相关配件");
            }
            builder.append("全部同框整齐展示，按真实比例分区摆放，主体完整入画；不得遗漏已选择配件，不得加入未选择配件。\n");
            return;
        }
        if (!hasUploadedTemplate) {
            builder.append("\n# 【本张配件展示规则】\n");
            builder.append("本张可根据当前场景卖点，只从已选择配件中挑选相关配件展示，不要求把所有配件一次性展示完；");
            builder.append("若展示某个已选配件，必须按参考图外观、真实比例和该场景需要的数量准确出现；未选择配件禁止出现。\n");
        }
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
        builder.append("# 【镜头膜安装关系锁定】\n");
        builder.append("""
                镜头膜属于手机后摄模组专用保护件，不是独立展示物；
                不得远离后摄模组单独漂浮展示，也不得出现在手机正面、中部、底部、另一侧或任何与后摄区域无关的位置。
                """);
        builder.append("""
                若场景明确要求悬浮安装展示：镜头膜必须位于后摄模组正上方，与后摄模组中心基本重合；
                镜头膜与后摄模组保持平行，开孔方向一致，不得旋转、镜像、倒置或明显偏移；边界偏差不得超过一个镜头孔直径。
                如果“主图/介绍图 画面要求”或则“主图/介绍图 参考风格图风格中没有要求镜头膜的位置，默认必须安装到手机镜头上，而且要注意生成的手机背面一定不能把某个镜头漏掉！”
                """);
        builder.append("若场景明确要求分开展示：镜头膜必须位于后摄区域旁侧，并保持真实安装逻辑；不得移动到手机另一侧。\n\n");

    }

    private void appendReferenceRoleAndScaleLayoutLock(
            StringBuilder builder,
            String imageType,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext
    ) {
        boolean hasRealPhoto = uploadMaterialContext == null || uploadMaterialContext.hasRealPhotoImage();
        boolean hasTemplate = uploadMaterialContext != null
                && uploadMaterialContext.hasTemplateImage()
                && usesUploadAsset(payload.templateUsages(), imageType);
        boolean hasWallpaper = hasUsableWallpaperImage(payload, uploadMaterialContext, imageType);
        String kitSpecText = joinKitSpecs(payload.kitSpecs());

        builder.append("# 【参考图角色、比例与版式硬约束】\n");

        if (hasRealPhoto) {
            builder.append("实拍图是产品结构最高参考：产品外轮廓、孔位数量、孔位位置、孔位大小、膜片透明度、配件真实形态和相对尺寸，均以实拍图和深析结果为准。\n");
        }

        if (hasTemplate) {
            builder.append("排版图仅作为当前").append(imageType).append("的版式骨架参考，不作为商品、风格或文字内容参考。\n");
            builder.append("必须尽量复用排版图的主体区、配件区、信息区、留白、安全边距、网格/分栏、对齐关系、前后层级和裁切方式。\n");
            builder.append("不得照抄排版图中的示例商品、品牌、文字、图标或装饰元素；只能在排版骨架内替换为本任务的手机、膜片和已选配件。\n");
            builder.append("若排版图与实拍图产品结构冲突，必须优先保证实拍图结构正确；若排版图与产品比例冲突，必须优先保证手机、屏幕膜、镜头膜和配件的真实比例。\n");
        } else {
            builder.append("未启用排版图时，必须使用规整电商陈列：主体居中或左右分区，配件按一行、一列或网格对齐；禁止自由散落、随机堆叠、杂乱遮挡或无规则摆放。\n");
        }

        if (hasWallpaper) {
            builder.append("壁纸图只允许作为手机屏幕或屏幕展示区域的贴图使用，不得作为背景风格、场景风格或装饰元素重绘。\n");
        }

        if (!"未选择".equals(kitSpecText)) {
            builder.append("配件图是配件外观参考：已选配件必须按参考图的形状、颜色、材质、标签区域、图案和可见文字等比例缩放后摆放，不得替换为通用配件。\n");
        }

        builder.append(SCALE_LOCK_RULE).append("\n");
        builder.append("手机主体必须完整入画并保留安全边距；主图和套装平铺图不得裁掉手机顶部、底部、边框或膜片边缘。\n");
        builder.append("介绍图如需局部细节，可以放大局部，但必须保留清楚的产品关系，不能因此改变产品结构、真实比例或配件数量。\n");
        builder.append("若场景规划、风格特效或排版图与本段规则冲突，必须优先保证：产品结构正确、比例正确、数量正确、主体完整入画。\n\n");
    }

    private void appendAllowedObjectsContext(
            StringBuilder builder,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext,
            String imageType
    ) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
/*        builder.append("# 【允许出现物品白名单】\n");
        boolean hasKit = !"未选择".equals(kitSpecText);
        boolean hasWallpaper = hasUsableWallpaperImage(payload, uploadMaterialContext, imageType);
        builder.append("仅允许出现以下物品：与机型匹配的手机/手机模型、上传实拍图对应的屏幕膜、上传实拍图对应的镜头膜");
        if (hasKit) {
            builder.append("、套装配件（").append(kitSpecText).append("）");
        }
        if (hasWallpaper) {
            builder.append("、已上传且选择当前类型使用的壁纸图");
        }
        builder.append("。\n");*/
        /*builder.append("白名单只限制物品种类；产品结构、孔位、比例、数量和版式仍按前文结构锁定、比例锁定、套装规格和排版图规则执行。\n");
        builder.append("客户允许的产品范围仅限：").append(CUSTOMER_ALLOWED_PRODUCT_TYPES).append("；允许的配件范围仅限：").append(CUSTOMER_ALLOWED_ACCESSORIES).append("。\n");
        builder.append("未在白名单中列出的物品一律禁止出现，包括但不限于：额外包装、包装袋、非参考图黑/白小袋、收纳袋、包装盒、纸盒、礼盒、安装卡、说明书、托盘、支架、底座、展示道具、未选择贴纸、未上传配件、额外手机膜或额外配件。\n");*/
        appendAccessoryReferenceRule(builder, kitSpecText);
        builder.append("\n");
    }

    private void appendPromptAssetWhitelist(StringBuilder builder, String basePrompt) {
        String normalizedPrompt = normalizeNullable(basePrompt);
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
        builder.append("# 【套装规格数量锁定】\n").append(kitSpecText).append("\n");
        builder.append("套装规格是本任务允许使用的配件白名单和全配件合集数量锁定：主图数量大于 1 时，主图第 1 张必须把所有已选择配件按数量准确展示；未启用排版图的其它主图或介绍图可按场景卖点只选择部分已选配件，不要求每张都全量展示；无论部分或全部展示，未选择的配件都不要出现。\n");
        appendAccessoryReferenceContext(builder, payload.kitSpecs());
        builder.append("\n");
    }

    private void appendAccessoryReferenceContext(StringBuilder builder, List<ImageTaskKitSpec> specs) {
        List<ExtraAccessoryService.ExtraAccessoryRecord> accessories = accessoryRecords(specs);
        if (accessories.isEmpty()) {
            builder.append("# 【配件参考图】\n 未找到已保存的配件图片，请仅按套装规格文字生成。\n");
            return;
        }
        builder.append("【配件参考图】已将以下配件图片作为生图参考图传入：")
                .append(String.join("、", accessories.stream().map(ExtraAccessoryService.ExtraAccessoryRecord::name).toList()))
                .append("。生成时必须参考对应配件图片的形状、颜色、材质、封边/标签区域、图案和可见文字；当本张场景展示某个配件时必须保持其真实外观和数量要求，全配件合集图必须按套装规格数量准确摆放；可见文字不要省略、改字或替换成空白块。\n");
    }

    private void appendUploadedTemplateContext(
            StringBuilder builder,
            String imageType,
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext
    ) {
        if (!hasUploadedTemplateForType(payload, uploadMaterialContext, imageType)) {
            return;
        }
        builder.append("【").append(imageType).append("排版图约束】排版图已作为版式/布局参考图随本张生图请求传入；如当前")
                .append(imageType)
                .append("启用排版图，必须直接按排版图的主体区、配件区、信息区、留白、安全边距、网格/分栏、对齐关系、层级和裁切关系填入本任务产品、手机与已选配件。不要分析、重绘、照抄排版图中的示例商品、品牌、文字或图标；不得改变上传实拍图产品结构、孔位、外轮廓、产品比例和配件数量；如排版图与原始主图/介绍图画面要求、场景规划或参考风格图存在版式冲突，以排版图的画面结构、主体位置、配件位置、留白比例和构图关系为最高优先级。\n");
    }

    private void appendTemplateFillPrompt(StringBuilder builder, String imageType) {
        builder.append("\n# 【").append(imageType).append("排版图填充生成要求】\n");
        builder.append("基于排版图填充上传素材并生成完整电商图：严格遵循排版图中的画面结构、主体位置、配件位置、留白比例、构图关系、对齐方式、网格/分栏、前后层级和安全边距。\n");
        builder.append("将用户上传的实拍产品图、手机膜/镜头膜结构、已选配件图和壁纸图等素材合理填充到排版图对应区域；只替换为本任务素材，不照抄排版图中的示例商品、品牌、文字、图标或装饰元素。\n");
        builder.append("排版图控制版式和构图，不控制产品结构；产品外轮廓、孔位、数量、比例和配件真实外观仍以上传实拍图、深析结果和已选配件参考图为准。\n");
        builder.append("如排版图与原始主图/介绍图画面要求或场景规划存在冲突，以排版图为最高版式优先级，必须按排版图区域填充素材并生成完整、规整、成品级电商图。\n");
    }

    private void appendTargetTemplateContext(
            StringBuilder builder,
            String imageType,
            TargetTemplateService.TargetTemplateRecord targetTemplate
    ) {
        if (targetTemplate == null) {
            return;
        }
        builder.append("【").append(imageType).append("参考风格图风格】")
                .append(abbreviate(normalizeNullable(targetTemplate.styleAnalysis()), MAX_ANALYSIS_PROMPT_CHARS))
                .append("\n");
        builder.append("【").append(imageType).append("参考风格图约束】仅应用上方参考风格图风格文字；只作构图氛围、背景质感、光影、空间层次和视觉风格参考，不得改变上传图孔位、外轮廓、配件数量、产品比例、排版图版式和产品结构。\n");
    }

    private void appendPerImageSelfAudit(
            StringBuilder builder,
            ImageTaskPayload payload,
            String finalPrompt,
            String resultType,
            int index
    ) {
        String kitSpecText = joinKitSpecs(payload.kitSpecs());
        builder.append("\n【本张成品自审与修正】\n");
        builder.append("本张只允许出现：与机型匹配的手机/手机模型、上传实拍图对应的屏幕膜、上传实拍图对应的一体式镜头膜");
        if (!"未选择".equals(kitSpecText)) {
            builder.append("、已选择套装配件（").append(kitSpecText).append("）");
        }
        appendPromptAssetWhitelist(builder, finalPrompt);
        builder.append("。\n");
        builder.append("客户物品范围只包含产品（").append(CUSTOMER_ALLOWED_PRODUCT_TYPES).append("）和配件（").append(CUSTOMER_ALLOWED_ACCESSORIES).append("）；没有选择或上传的同类物品也不要生成。\n");
        builder.append("若场景规划、参考风格图风格或模型联想引入包装盒、包装袋、收纳袋、非参考图黑/白小袋、托盘、卡片、支架、底座、展示道具、未选择贴纸或未选配件，全部视为错误并不要生成。\n");
        appendAccessoryReferenceRule(builder, kitSpecText);
        if (hasLensProtector(payload, finalPrompt)) {
            builder.append("镜头膜结构再次自检：按上传图/深析结果锁定当前机型的外轮廓、孔位数量、孔位位置、孔位大小差异，以及一体式片状或分离镜圈形态；不要套用其他手机型号镜头膜结构。\n");
        }
        appendPerImageFilmTypeAudit(builder, payload);
        builder.append("比例与版式再次自检：手机主体完整入画并保留安全边距；屏幕膜/钢化膜/防窥膜与手机屏幕长宽比和覆盖尺寸一致；镜头膜只匹配后摄区域，不能接近半个手机；配件按参考图等比例缩放并整齐放入配件区；如启用排版图，必须按排版图区域和对齐关系填图，不能散乱摆放。\n");
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

    private boolean hasUsableWallpaperImage(ImageTaskPayload payload, UploadMaterialContext uploadMaterialContext, String imageType) {
        return uploadMaterialContext != null
                && uploadMaterialContext.hasWallpaperImage()
                && usesUploadAsset(payload.wallpaperUsages(), imageType);
    }

    private String imageSizeText(ImageTaskPayload payload, String imageType, boolean hasUploadedTemplate) {
        if (hasUploadedTemplate) {
            return "自动比例（按排版图实际宽高比输出）";
        }
        boolean intro = "介绍图".equals(imageType);
        Integer width = intro ? payload.introCustomWidth() : payload.mainCustomWidth();
        Integer height = intro ? payload.introCustomHeight() : payload.mainCustomHeight();
        int normalizedWidth = normalizeImageDimension(width == null ? payload.customWidth() : width, DEFAULT_IMAGE_SIZE);
        int normalizedHeight = normalizeImageDimension(height == null ? payload.customHeight() : height, DEFAULT_IMAGE_SIZE);
        return normalizedWidth + "x" + normalizedHeight;
    }

    private boolean hasUploadedTemplateForType(
            ImageTaskPayload payload,
            UploadMaterialContext uploadMaterialContext,
            String imageType
    ) {
        return uploadMaterialContext != null
                && uploadMaterialContext.hasTemplateImage()
                && usesUploadAsset(payload.templateUsages(), imageType);
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
