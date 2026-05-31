package xin.students.imageaioriginal.model;

public record StoredUploadImage(
        String fileName,
        String contentType,
        byte[] bytes
) {
    public long size() {
        return bytes == null ? 0 : bytes.length;
    }
}
