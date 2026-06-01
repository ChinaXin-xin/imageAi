package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.model.TargetTemplateView;
import xin.students.imageaioriginal.service.TargetTemplateService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/target-templates")
public class TargetTemplateController {

    private final TargetTemplateService targetTemplateService;

    public TargetTemplateController(TargetTemplateService targetTemplateService) {
        this.targetTemplateService = targetTemplateService;
    }

    @GetMapping
    public List<TargetTemplateView> listTemplates() {
        return targetTemplateService.listTemplates();
    }

    @PostMapping
    public TargetTemplateView createTemplate(
            @RequestParam("templateType") String templateType,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam("file") MultipartFile file
    ) {
        return targetTemplateService.createTemplate(templateType, name, file);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> deleteTemplate(@PathVariable long id) {
        targetTemplateService.deleteTemplate(id);
        return Map.of("deleted", true);
    }
}
