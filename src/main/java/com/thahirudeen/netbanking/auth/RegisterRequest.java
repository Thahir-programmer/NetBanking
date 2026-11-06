package com.thahirudeen.netbanking.auth;

import jakarta.validation.constraints.*;

public record RegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password) {}
