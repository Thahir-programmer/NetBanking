package com.thahirudeen.netbanking.account;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
@RequiredArgsConstructor
public class AccountNumberGenerator {

    private final AccountRepository accountRepository;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        String number;
        do {
            StringBuilder sb = new StringBuilder("10"); // bank prefix
            for (int i = 0; i < 10; i++) sb.append(random.nextInt(10));
            number = sb.toString();
        } while (accountRepository.existsByAccountNumber(number));
        return number;
    }
}
