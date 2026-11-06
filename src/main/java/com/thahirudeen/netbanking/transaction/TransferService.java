package com.thahirudeen.netbanking.transaction;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.account.AccountStatus;
import com.thahirudeen.netbanking.common.AccountFrozenException;
import com.thahirudeen.netbanking.common.ConflictException;
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

    private static final int MAX_KEY_LENGTH = 100;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    // Debit and credit are one database transaction: any exception rolls back both.
    @Transactional
    public TransferResponse transfer(Long userId, String toAccountNumber, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidTransferException("Idempotency-Key header is required (max " + MAX_KEY_LENGTH + " chars)");
        }

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

        // The key is scoped to the sender, so two users can use the same key value.
        // Checked AFTER taking the sender's lock: concurrent requests with the same key
        // queue on that lock, so the second one reliably sees the first one's committed row.
        String referenceId = from.getId() + ":" + idempotencyKey;
        var existing = transactionRepository.findByReferenceId(referenceId);
        if (existing.isPresent()) {
            return replay(existing.get(), to, amount);
        }

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
        tx.setReferenceId(referenceId);
        transactionRepository.save(tx);

        return TransferResponse.from(tx);
    }

    // Same key, same request: return the original result and move no money.
    // Same key, different request: that is a client bug, so reject it.
    private TransferResponse replay(Transaction original, Account to, BigDecimal amount) {
        boolean sameRequest = original.getToAccount().getId().equals(to.getId())
                && original.getAmount().compareTo(amount) == 0;
        if (!sameRequest) {
            throw new ConflictException("Idempotency-Key was already used for a different request");
        }
        return TransferResponse.from(original);
    }

    private Account lock(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }
}
