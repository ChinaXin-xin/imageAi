package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import xin.students.imageaioriginal.model.ExtraAccessoryView;
import xin.students.imageaioriginal.service.ExtraAccessoryService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/extra-accessories")
public class ExtraAccessoryController {

    private final ExtraAccessoryService extraAccessoryService;

    public ExtraAccessoryController(ExtraAccessoryService extraAccessoryService) {
        this.extraAccessoryService = extraAccessoryService;
    }

    @GetMapping
    public List<ExtraAccessoryView> listAccessories() {
        return extraAccessoryService.listAccessories();
    }

    @PostMapping
    public ExtraAccessoryView createAccessory(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam("file") MultipartFile file
    ) {
        return extraAccessoryService.createAccessory(name, file);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> deleteAccessory(@PathVariable long id) {
        extraAccessoryService.deleteAccessory(id);
        return Map.of("deleted", true);
    }
}
