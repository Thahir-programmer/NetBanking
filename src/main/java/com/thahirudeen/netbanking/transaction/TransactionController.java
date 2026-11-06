package com.thahirudeen.netbanking.transaction;

import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/accounts/me/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    // History of the caller's own account only: the account comes from the token (IDOR prevention)
    @GetMapping
    @Transactional(readOnly = true)
    public List<TransactionResponse> history(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        Long accountId = accountRepository.findIdByUserId(Long.parseLong(jwt.getSubject()))
                .orElseThrow(() -> new NotFoundException("Account not found"));
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return transactionRepository
                .findHistory(accountId, PageRequest.of(Math.max(page, 0), safeSize))
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
