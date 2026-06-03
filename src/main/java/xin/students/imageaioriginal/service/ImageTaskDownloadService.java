package xin.students.imageaioriginal.service;

import org.springframework.stereotype.Service;
import xin.students.imageaioriginal.model.StoredUploadImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ImageTaskDownloadService {

    private final ImageTaskRepository imageTaskRepository;

    public ImageTaskDownloadService(ImageTaskRepository imageTaskRepository) {
        this.imageTaskRepository = imageTaskRepository;
    }

    public ImageTaskDownloadFile downloadTaskImages(List<String> taskIds) {
        List<String> normalizedTaskIds = uniqueTaskIds(taskIds);
        if (normalizedTaskIds.isEmpty()) {
            throw new IllegalArgumentException("请先选择需要下载的已完成任务");
        }
        boolean multipleTasks = normalizedTaskIds.size() > 1;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            Set<String> usedFolders = new HashSet<>();
            int entryCount = 0;
            for (String id : normalizedTaskIds) {
                TaskRecord task = requireTask(id);
                String folder = multipleTasks ? uniqueFolderName(task.productName(), task.id(), usedFolders) + "/" : "";
                for (ResultRecord result : imageTaskRepository.completedResults(id)) {
                    entryCount += addResultToZip(zipOutput, folder, result);
                }
            }
            if (entryCount == 0) {
                throw new IllegalStateException("所选任务暂无可下载的已完成图片");
            }
            zipOutput.finish();
            String zipName = multipleTasks
                    ? "生图任务结果.zip"
                    : sanitizeFileName(requireTask(normalizedTaskIds.get(0)).productName()) + ".zip";
            return new ImageTaskDownloadFile(zipName, output.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException("打包下载图片失败", ex);
        }
    }

    public StoredUploadImage resultReferenceImage(ResultRecord result) {
        ImageTaskPreviewFile image = imageTaskRepository.resultImage(result.taskId(), result.id());
        return new StoredUploadImage(
                result.resultType() + "-" + result.itemIndex() + "-v" + result.versionIndex() + "." + imageExtension(image.contentType()),
                image.contentType(),
                image.bytes()
        );
    }

    private TaskRecord requireTask(String taskId) {
        TaskRecord record = imageTaskRepository.findTask(taskId);
        if (record == null) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        return record;
    }

    private List<String> uniqueTaskIds(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String taskId : taskIds) {
            if (taskId == null || taskId.isBlank()) {
                continue;
            }
            String normalized = taskId.trim();
            if (seen.add(normalized)) {
                values.add(normalized);
            }
        }
        return values;
    }

    private int addResultToZip(ZipOutputStream zipOutput, String folder, ResultRecord result) throws IOException {
        String baseName = sanitizeFileName(result.resultType() + "-" + result.itemIndex() + "-v" + result.versionIndex());
        try {
            ImageTaskPreviewFile image = imageTaskRepository.resultImage(result.taskId(), result.id());
            zipOutput.putNextEntry(new ZipEntry(folder + baseName + "." + imageExtension(image.contentType())));
            zipOutput.write(image.bytes());
            zipOutput.closeEntry();
            return 1;
        } catch (RuntimeException ex) {
            if (result.imageUrl() == null || result.imageUrl().isBlank()) {
                return 0;
            }
            zipOutput.putNextEntry(new ZipEntry(folder + baseName + "-image-url.txt"));
            zipOutput.write(result.imageUrl().getBytes(StandardCharsets.UTF_8));
            zipOutput.closeEntry();
            return 1;
        }
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

    private String uniqueFolderName(String productName, String taskId, Set<String> usedFolders) {
        String baseName = sanitizeFileName(productName);
        String candidate = baseName;
        if (!usedFolders.add(candidate)) {
            String suffix = taskId == null || taskId.length() < 8 ? normalizeText(taskId, "task") : taskId.substring(0, 8);
            candidate = sanitizeFileName(baseName + "-" + suffix);
            int index = 2;
            while (!usedFolders.add(candidate)) {
                candidate = sanitizeFileName(baseName + "-" + suffix + "-" + index);
                index++;
            }
        }
        return candidate;
    }

    private String sanitizeFileName(String value) {
        String normalized = normalizeText(value, "image-task").trim();
        if (normalized.isBlank()) {
            normalized = "image-task";
        }
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? "image-task" : abbreviate(normalized, 120);
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
