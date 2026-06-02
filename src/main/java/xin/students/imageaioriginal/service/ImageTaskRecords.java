package xin.students.imageaioriginal.service;

import xin.students.imageaioriginal.model.ImageTaskPayload;
import xin.students.imageaioriginal.model.StoredUploadImage;

import java.util.List;
import java.util.Map;

record StoredTaskFile(
        String fileGroup,
        String fileName,
        String contentType,
        byte[] bytes,
        int sortOrder
) {
}

record Thumbnail(
        byte[] bytes,
        String contentType,
        String fileName
) {
}

record GenerationJob(
        long resultId,
        String resultType,
        int index,
        String prompt,
        TargetTemplateService.TargetTemplateRecord targetTemplate
) {
}

record ResultRecord(
        long id,
        String taskId,
        String resultType,
        int itemIndex,
        Long parentResultId,
        int versionIndex,
        String status,
        String prompt,
        String imageUrl,
        String imageBase64,
        String editSuggestion,
        String errorMessage
) {
}

record DecodedImage(
        byte[] bytes,
        String contentType,
        String extension
) {
}

record TaskRecord(
        String id,
        String productName,
        String status,
        String payloadJson,
        ImageTaskPayload payload,
        Map<String, String> analysis,
        String finalMainPrompt,
        String finalIntroPrompt,
        byte[] thumbnail,
        String thumbnailContentType,
        String thumbnailFileName,
        int realPhotoCount,
        int packageImageCount,
        int templateCount,
        String errorMessage,
        String createdAt,
        String updatedAt,
        String startedAt,
        String completedAt
) {
}

record ResultStats(
        int completed,
        int total
) {
}

record GenerationReferences(
        List<StoredUploadImage> mainImages,
        List<StoredUploadImage> introImages
) {
}

record UploadMaterialContext(
        boolean hasTemplateImage,
        boolean hasLogoImage,
        boolean hasWallpaperImage
) {
    static UploadMaterialContext unknown() {
        return new UploadMaterialContext(true, true, true);
    }
}
