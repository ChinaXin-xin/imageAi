package xin.students.imageaioriginal.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import xin.students.imageaioriginal.model.CodexQuotaAccount;
import xin.students.imageaioriginal.service.CodexQuotaService;

import java.util.List;

@RestController
@RequestMapping("/api/codex/quota")
public class CodexQuotaController {

    private final CodexQuotaService codexQuotaService;

    public CodexQuotaController(CodexQuotaService codexQuotaService) {
        this.codexQuotaService = codexQuotaService;
    }

    @GetMapping("/accounts")
    public List<CodexQuotaAccount> accounts() {
        return codexQuotaService.getAccounts();
    }
}
