package com.thahirudeen.netbanking.common;

public class AccountFrozenException extends RuntimeException {
    public AccountFrozenException() {
        super("Account is frozen");
    }
}
