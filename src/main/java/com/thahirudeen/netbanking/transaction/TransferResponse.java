package com.thahirudeen.netbanking.transaction;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        Long transactionId,
        String fromAccount,
        String toAccount,
        BigDecimal amount,
        TransactionStatus status,
        Instant createdAt) {

    public static TransferResponse from(Transaction t) {
        return new TransferResponse(
                t.getId(),
                t.getFromAccount().getAccountNumber(),
                t.getToAccount().getAccountNumber(),
                t.getAmount(),
                t.getStatus(),
                t.getCreatedAt());
    }
}
