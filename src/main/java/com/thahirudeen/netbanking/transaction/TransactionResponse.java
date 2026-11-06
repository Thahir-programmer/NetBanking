package com.thahirudeen.netbanking.transaction;

import java.math.BigDecimal;
import java.time.Instant;

// fromAccount is null for deposits, toAccount is null for withdrawals
public record TransactionResponse(
        Long id,
        TransactionType type,
        BigDecimal amount,
        String fromAccount,
        String toAccount,
        TransactionStatus status,
        Instant createdAt) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getType(),
                t.getAmount(),
                t.getFromAccount() == null ? null : t.getFromAccount().getAccountNumber(),
                t.getToAccount() == null ? null : t.getToAccount().getAccountNumber(),
                t.getStatus(),
                t.getCreatedAt());
    }
}
