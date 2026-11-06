package com.thahirudeen.netbanking.transaction;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.account.AccountResponse;
import com.thahirudeen.netbanking.account.AccountStatus;
import com.thahirudeen.netbanking.common.AccountFrozenException;
import com.thahirudeen.netbanking.common.InsufficientFundsException;
import com.thahirudeen.netbanking.common.InvalidTransferException;
import com.thahirudeen.netbanking.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    // Debit and credit are one database transaction: any exception rolls back both.
    @Transactional
    public AccountResponse transfer(Long userId, String toAccountNumber, BigDecimal amount) {
        // Ids only: loading full entities here would put stale copies in the session
        Long myId = accountRepository.findIdByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
        Long targetId = accountRepository.findIdByAccountNumber(toAccountNumber)
                .orElseThrow(() -> new NotFoundException("Destination account not found"));

        if (myId.equals(targetId)) {
            throw new InvalidTransferException("Cannot transfer to the same account");
        }

        // Always lock the lower id first. A->B and B->A then queue up instead of deadlocking.
        Long firstId = Math.min(myId, targetId);
        Long secondId = Math.max(myId, targetId);
        Account first = lock(firstId);
        Account second = lock(secondId);
        Account from = first.getId().equals(myId) ? first : second;
        Account to = from == first ? second : first;

        // Re-check on the locked, fresh rows: state may have changed while we waited
        if (from.getStatus() == AccountStatus.FROZEN || to.getStatus() == AccountStatus.FROZEN) {
            throw new AccountFrozenException();
        }
        if (from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }

        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));

        Transaction tx = new Transaction();
        tx.setType(TransactionType.TRANSFER);
        tx.setAmount(amount);
        tx.setFromAccount(from);
        tx.setToAccount(to);
        tx.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(tx);

        return AccountResponse.from(from);
    }

    private Account lock(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }
}
