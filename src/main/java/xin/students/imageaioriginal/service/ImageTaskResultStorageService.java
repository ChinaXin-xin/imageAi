package xin.students.imageaioriginal.service;

import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.config.ImageGenerationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

@Service
public class ImageTaskResultStorageService {

    private final Path outputDirectory;

    public ImageTaskResultStorageService(ImageGenerationProperties imageGenerationProperties) {
        this.outputDirectory = Path.of(imageGenerationProperties.resolvedOutputDirectory()).toAbsolutePath().normalize();
    }

    public StoredResultImage saveGeneratedImage(String taskId, long resultId, ImageGenerationService.GeneratedImage generatedImage) {
        DecodedImage decoded = decodeImageBase64(generatedImage.imageBase64());
        if (decoded.bytes().length == 0) {
            return null;
        }
        String relativePath = safePathPart(taskId) + "/" + resultId + "." + decoded.extension();
        Path target = outputDirectory.resolve(relativePath).normalize();
        if (!target.startsWith(outputDirectory)) {
            throw new IllegalStateException("生成图片保存路径非法：" + relativePath);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, decoded.bytes());
            return new StoredResultImage(relativePath.replace('\\', '/'), decoded.contentType(), decoded.extension(), decoded.bytes().length);
        } catch (IOException ex) {
            throw new IllegalStateException("保存生成图片到本地文件失败：" + target, ex);
        }
    }

    public ImageTaskPreviewFile readStoredImage(String relativePath, String fileName) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path target = outputDirectory.resolve(relativePath).normalize();
        if (!target.startsWith(outputDirectory) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            String contentType = Files.probeContentType(target);
            if (contentType == null || contentType.isBlank()) {
                contentType = contentTypeFromExtension(target.getFileName().toString());
            }
            return new ImageTaskPreviewFile(fileName, contentType, Files.readAllBytes(target));
        } catch (IOException ex) {
            throw new IllegalStateException("读取本地生成图片失败：" + target, ex);
        }
    }

    public void deleteTaskImages(String taskId) {
        Path taskDirectory = outputDirectory.resolve(safePathPart(taskId)).normalize();
        if (!taskDirectory.startsWith(outputDirectory) || !Files.exists(taskDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(taskDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new IllegalStateException("删除本地生成图片失败：" + path, ex);
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("清理本地生成图片目录失败：" + taskDirectory, ex);
        }
    }

    public DecodedImage decodeImageBase64(String value) {
        if (value == null || value.isBlank()) {
            return new DecodedImage(new byte[0], "image/png", "png");
        }
        String contentType = "image/png";
        String payload = value.trim();
        int commaIndex = payload.indexOf(',');
        if (payload.startsWith("data:") && commaIndex > 0) {
            int typeEnd = payload.indexOf(';');
            if (typeEnd > 5) {
                contentType = payload.substring(5, typeEnd);
            }
            payload = payload.substring(commaIndex + 1);
        }
        try {
            return new DecodedImage(Base64.getDecoder().decode(payload), contentType, imageExtension(contentType));
        } catch (IllegalArgumentException ex) {
            return new DecodedImage(new byte[0], contentType, imageExtension(contentType));
        }
    }

    private String contentTypeFromExtension(String fileName) {
        String extension = fileName == null ? "" : fileName.toLowerCase();
        if (extension.endsWith(".jpg") || extension.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (extension.endsWith(".webp")) {
            return "image/webp";
        }
        if (extension.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    private String imageExtension(String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase();
        if (normalized.contains("jpeg") || normalized.contains("jpg")) {
            return "jpg";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        if (normalized.contains("gif")) {
            return "gif";
        }
        return "png";
    }

    private String safePathPart(String value) {
        String normalized = value == null || value.isBlank() ? "task" : value.trim();
        return normalized.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public record StoredResultImage(
            String relativePath,
            String contentType,
            String extension,
            int bytes
    ) {
    }
}
