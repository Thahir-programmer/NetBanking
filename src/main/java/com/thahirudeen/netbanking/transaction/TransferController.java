package com.thahirudeen.netbanking.transaction;

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

    // The source account always comes from the token, never from the body (IDOR prevention).
    // The client generates a unique Idempotency-Key per logical transfer and reuses it on retries.
    @PostMapping
    public TransferResponse transfer(@AuthenticationPrincipal Jwt jwt,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody TransferRequest req) {
        return transferService.transfer(Long.parseLong(jwt.getSubject()),
                req.toAccountNumber(), req.amount(), idempotencyKey);
    }
}
