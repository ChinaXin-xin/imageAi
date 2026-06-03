package xin.students.imageaioriginal.service;

public record ImageTaskPreviewFile(
        String fileName,
        String contentType,
        byte[] bytes
) {
}
