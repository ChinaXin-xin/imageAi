package xin.students.imageaioriginal.model;

public record ImageTaskFileView(
        Long id,
        String group,
        String groupName,
        String fileName,
        String contentType,
        Long fileSize,
        String preview
) {
}
