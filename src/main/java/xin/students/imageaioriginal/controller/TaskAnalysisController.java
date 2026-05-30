package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.model.UploadImageAnalysis;
import xin.students.imageaioriginal.service.UploadImageAnalysisService;

import java.util.List;

@RestController
@RequestMapping("/api/task")
public class TaskAnalysisController {

    private final UploadImageAnalysisService uploadImageAnalysisService;

    public TaskAnalysisController(UploadImageAnalysisService uploadImageAnalysisService) {
        this.uploadImageAnalysisService = uploadImageAnalysisService;
    }

    @PostMapping("/analyze-upload")
    public UploadImageAnalysis analyzeUpload(
            @RequestParam String type,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return uploadImageAnalysisService.analyze(type, prompt, files);
    }
}
