package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xin.students.imageaioriginal.model.DefaultPromptSettings;
import xin.students.imageaioriginal.service.DefaultPromptSettingsService;

@RestController
@RequestMapping("/api/default-settings")
public class DefaultSettingsController {

    private final DefaultPromptSettingsService defaultPromptSettingsService;

    public DefaultSettingsController(DefaultPromptSettingsService defaultPromptSettingsService) {
        this.defaultPromptSettingsService = defaultPromptSettingsService;
    }

    @GetMapping("/prompts")
    public DefaultPromptSettings getPrompts() {
        return defaultPromptSettingsService.getSettings();
    }

    @PutMapping("/prompts")
    public DefaultPromptSettings savePrompts(@RequestBody DefaultPromptSettings settings) {
        return defaultPromptSettingsService.saveSettings(settings);
    }
}
