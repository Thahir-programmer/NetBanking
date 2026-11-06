package com.thahirudeen.netbanking.account;

import java.math.BigDecimal;

public record AccountResponse(String accountNumber, BigDecimal balance, AccountStatus status) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(a.getAccountNumber(), a.getBalance(), a.getStatus());
    }
}
