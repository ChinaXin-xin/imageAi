package xin.students.imageaioriginal.model;

import java.util.List;

public record ImageTaskPayload(
        String productName,
        String model,
        String platform,
        String ratio,
        Integer customWidth,
        Integer customHeight,
        String mainRatio,
        Integer mainCustomWidth,
        Integer mainCustomHeight,
        String introRatio,
        Integer introCustomWidth,
        Integer introCustomHeight,
        String phoneColor,
        String customColor,
        String wallpaperName,
        String style,
        String layout,
        List<String> sellingPoints,
        Boolean hdEnabled,
        Boolean privacyEnabled,
        Integer hdQuantity,
        Integer privacyQuantity,
        Integer mainImageCount,
        Integer introImageCount,
        String language,
        String mainPrompt,
        String introPrompt,
        String scenePrompt,
        Long mainTargetTemplateId,
        Long introTargetTemplateId,
        List<String> templateUsages,
        List<String> wallpaperUsages,
        List<ImageTaskKitSpec> kitSpecs
) {
}
