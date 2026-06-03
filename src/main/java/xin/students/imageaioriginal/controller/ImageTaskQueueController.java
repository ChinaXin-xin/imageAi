package xin.students.imageaioriginal.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.model.ImageEditRequest;
import xin.students.imageaioriginal.model.ImageTaskDetail;
import xin.students.imageaioriginal.model.ImageTaskSummary;
import xin.students.imageaioriginal.model.TaskDownloadRequest;
import xin.students.imageaioriginal.service.ImageTaskDownloadFile;
import xin.students.imageaioriginal.service.ImageTaskPreviewFile;
import xin.students.imageaioriginal.service.ImageTaskQueueService;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class ImageTaskQueueController {

    private final ImageTaskQueueService imageTaskQueueService;

    public ImageTaskQueueController(ImageTaskQueueService imageTaskQueueService) {
        this.imageTaskQueueService = imageTaskQueueService;
    }

    @GetMapping
    public List<ImageTaskSummary> listTasks() {
        return imageTaskQueueService.listTasks();
    }

    @GetMapping("/{taskId}")
    public ImageTaskDetail getTask(@PathVariable String taskId) {
        return imageTaskQueueService.getTask(taskId);
    }

    @GetMapping("/{taskId}/files/{fileId}/preview")
    public ResponseEntity<byte[]> taskFilePreview(@PathVariable String taskId, @PathVariable long fileId) {
        ImageTaskPreviewFile file = imageTaskQueueService.taskFilePreview(taskId, fileId);
        return ResponseEntity.ok()
                .contentType(imageMediaType(file.contentType()))
                .body(file.bytes());
    }

    @PostMapping
    public ImageTaskDetail createTask(
            @RequestParam("payload") String payload,
            @RequestParam(value = "realPhotoFiles", required = false) List<MultipartFile> realPhotoFiles,
            @RequestParam(value = "templateFiles", required = false) List<MultipartFile> templateFiles,
            @RequestParam(value = "logoFiles", required = false) List<MultipartFile> logoFiles,
            @RequestParam(value = "wallpaperFiles", required = false) List<MultipartFile> wallpaperFiles
    ) {
        return imageTaskQueueService.createTask(payload, realPhotoFiles, templateFiles, logoFiles, wallpaperFiles);
    }

    @PostMapping("/{taskId}/retry")
    public ImageTaskDetail retryTask(@PathVariable String taskId) {
        return imageTaskQueueService.retryTask(taskId);
    }

    @PostMapping("/{taskId}/pause")
    public ImageTaskDetail pauseTask(@PathVariable String taskId) {
        return imageTaskQueueService.pauseTask(taskId);
    }

    @PostMapping("/{taskId}/resume")
    public ImageTaskDetail resumeTask(@PathVariable String taskId) {
        return imageTaskQueueService.resumeTask(taskId);
    }

    @PostMapping("/{taskId}/results/{resultId}/edit")
    public ImageTaskDetail editResult(
            @PathVariable String taskId,
            @PathVariable long resultId,
            @RequestBody ImageEditRequest request
    ) {
        return imageTaskQueueService.editResult(taskId, resultId, request.suggestion());
    }

    @GetMapping("/{taskId}/download")
    public ResponseEntity<byte[]> downloadTaskImages(@PathVariable String taskId) {
        return zipResponse(imageTaskQueueService.downloadTaskImages(List.of(taskId)));
    }

    @PostMapping("/download")
    public ResponseEntity<byte[]> downloadTaskImages(@RequestBody TaskDownloadRequest request) {
        return zipResponse(imageTaskQueueService.downloadTaskImages(request.taskIds()));
    }

    @DeleteMapping("/{taskId}")
    public void deleteTask(@PathVariable String taskId) {
        imageTaskQueueService.deleteTask(taskId);
    }

    private ResponseEntity<byte[]> zipResponse(ImageTaskDownloadFile file) {
        String encodedName = java.net.URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.bytes());
    }

    private MediaType imageMediaType(String contentType) {
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ex) {
            return MediaType.IMAGE_JPEG;
        }
    }
}
