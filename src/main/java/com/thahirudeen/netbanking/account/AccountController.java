package com.thahirudeen.netbanking.account;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/me")
    public AccountResponse me(@AuthenticationPrincipal Jwt jwt) {
        return accountService.getMyAccount(userId(jwt));
    }

    @PostMapping("/deposit")
    public AccountResponse deposit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AmountRequest req) {
        return accountService.deposit(userId(jwt), req.amount());
    }

    @PostMapping("/withdraw")
    public AccountResponse withdraw(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AmountRequest req) {
        return accountService.withdraw(userId(jwt), req.amount());
    }

    // The account is always derived from the signed token, never from the request body (IDOR prevention)
    private Long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }
}
