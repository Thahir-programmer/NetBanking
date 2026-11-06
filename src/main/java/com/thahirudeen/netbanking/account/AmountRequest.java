package com.thahirudeen.netbanking.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "Amount must be at least 0.01")
        @Digits(integer = 17, fraction = 2, message = "Amount allows at most 2 decimal places")
        BigDecimal amount) {}
