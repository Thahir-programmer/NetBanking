package com.thahirudeen.netbanking.account;

import com.thahirudeen.netbanking.common.AccountFrozenException;
import com.thahirudeen.netbanking.common.InsufficientFundsException;
import com.thahirudeen.netbanking.common.NotFoundException;
import com.thahirudeen.netbanking.transaction.Transaction;
import com.thahirudeen.netbanking.transaction.TransactionRepository;
import com.thahirudeen.netbanking.transaction.TransactionStatus;
import com.thahirudeen.netbanking.transaction.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public AccountResponse getMyAccount(Long userId) {
        return AccountResponse.from(findOwnAccount(userId));
    }

    // Balance change and the transaction record commit together, or neither does.
    // If another request changed this account first, @Version makes the save fail (409).
    @Transactional
    public AccountResponse deposit(Long userId, BigDecimal amount) {
        Account account = findActiveAccount(userId);
        account.setBalance(account.getBalance().add(amount));
        record(TransactionType.DEPOSIT, amount, null, account);
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse withdraw(Long userId, BigDecimal amount) {
        Account account = findActiveAccount(userId);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }
        account.setBalance(account.getBalance().subtract(amount));
        record(TransactionType.WITHDRAWAL, amount, account, null);
        return AccountResponse.from(account);
    }

    private void record(TransactionType type, BigDecimal amount, Account from, Account to) {
        Transaction tx = new Transaction();
        tx.setType(type);
        tx.setAmount(amount);
        tx.setFromAccount(from);
        tx.setToAccount(to);
        tx.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(tx);
    }

    private Account findOwnAccount(Long userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }

    private Account findActiveAccount(Long userId) {
        Account account = findOwnAccount(userId);
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException();
        }
        return account;
    }
}
