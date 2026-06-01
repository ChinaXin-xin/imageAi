package xin.students.imageaioriginal.model;

public record TargetTemplateView(
        Long id,
        String templateType,
        String templateTypeText,
        String name,
        String fileName,
        String contentType,
        Long fileSize,
        String preview,
        String styleAnalysis,
        String model,
        String createdAt,
        String updatedAt
) {
}
