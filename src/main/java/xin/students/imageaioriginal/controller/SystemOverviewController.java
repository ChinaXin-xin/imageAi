package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xin.students.imageaioriginal.model.SystemOverview;
import xin.students.imageaioriginal.service.SystemOverviewService;

@RestController
@RequestMapping("/api/system")
public class SystemOverviewController {

    private final SystemOverviewService systemOverviewService;

    public SystemOverviewController(SystemOverviewService systemOverviewService) {
        this.systemOverviewService = systemOverviewService;
    }

    @GetMapping("/overview")
    public SystemOverview overview() {
        return systemOverviewService.getOverview();
    }
}
