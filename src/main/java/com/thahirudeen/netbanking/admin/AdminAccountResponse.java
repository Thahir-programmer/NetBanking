package com.thahirudeen.netbanking.admin;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountStatus;

import java.math.BigDecimal;

public record AdminAccountResponse(
        Long id,
        String accountNumber,
        String ownerEmail,
        BigDecimal balance,
        AccountStatus status) {

    public static AdminAccountResponse from(Account a) {
        return new AdminAccountResponse(
                a.getId(), a.getAccountNumber(), a.getUser().getEmail(), a.getBalance(), a.getStatus());
    }
}
