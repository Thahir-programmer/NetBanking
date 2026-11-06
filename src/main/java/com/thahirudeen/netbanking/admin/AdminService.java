package com.thahirudeen.netbanking.admin;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.account.AccountStatus;
import com.thahirudeen.netbanking.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<AdminAccountResponse> listAccounts(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return accountRepository.findAll(PageRequest.of(Math.max(page, 0), safeSize, Sort.by("id")))
                .map(AdminAccountResponse::from)
                .getContent();
    }

    // Takes the same row lock as transfers: a freeze waits for in-flight transfers,
    // and any transfer that starts afterwards re-checks status and is rejected.
    @Transactional
    public AdminAccountResponse setStatus(Long accountId, AccountStatus status) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        account.setStatus(status);
        return AdminAccountResponse.from(account);
    }
}
