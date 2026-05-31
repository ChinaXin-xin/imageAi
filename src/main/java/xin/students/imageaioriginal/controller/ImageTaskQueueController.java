package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.model.ImageTaskDetail;
import xin.students.imageaioriginal.model.ImageTaskSummary;
import xin.students.imageaioriginal.service.ImageTaskQueueService;

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

    @PostMapping
    public ImageTaskDetail createTask(
            @RequestParam("payload") String payload,
            @RequestParam(value = "realPhotoFiles", required = false) List<MultipartFile> realPhotoFiles,
            @RequestParam(value = "packageImageFiles", required = false) List<MultipartFile> packageImageFiles,
            @RequestParam(value = "templateFiles", required = false) List<MultipartFile> templateFiles
    ) {
        return imageTaskQueueService.createTask(payload, realPhotoFiles, packageImageFiles, templateFiles);
    }

    @PostMapping("/{taskId}/retry")
    public ImageTaskDetail retryTask(@PathVariable String taskId) {
        return imageTaskQueueService.retryTask(taskId);
    }
}
