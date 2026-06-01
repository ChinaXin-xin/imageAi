package xin.students.imageaioriginal.model;

public record ExtraAccessoryView(
        Long id,
        String name,
        String fileName,
        String contentType,
        Long fileSize,
        String preview,
        String createdAt,
        String updatedAt
) {
}
