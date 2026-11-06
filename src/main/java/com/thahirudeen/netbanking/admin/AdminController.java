package com.thahirudeen.netbanking.admin;

import com.thahirudeen.netbanking.account.AccountStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /api/admin/** is restricted to ROLE_ADMIN in SecurityConfig
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/accounts")
    public List<AdminAccountResponse> accounts(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return adminService.listAccounts(page, size);
    }

    @PostMapping("/accounts/{id}/freeze")
    public AdminAccountResponse freeze(@PathVariable Long id) {
        return adminService.setStatus(id, AccountStatus.FROZEN);
    }

    @PostMapping("/accounts/{id}/unfreeze")
    public AdminAccountResponse unfreeze(@PathVariable Long id) {
        return adminService.setStatus(id, AccountStatus.ACTIVE);
    }
}
