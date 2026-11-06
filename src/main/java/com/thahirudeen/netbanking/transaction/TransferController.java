package com.thahirudeen.netbanking.transaction;

import com.thahirudeen.netbanking.account.AccountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    // The source account always comes from the token, never from the body (IDOR prevention)
    @PostMapping
    public AccountResponse transfer(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TransferRequest req) {
        return transferService.transfer(Long.parseLong(jwt.getSubject()), req.toAccountNumber(), req.amount());
    }
}
