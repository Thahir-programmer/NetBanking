package com.thahirudeen.netbanking.auth;

import com.thahirudeen.netbanking.account.Account;
import com.thahirudeen.netbanking.account.AccountNumberGenerator;
import com.thahirudeen.netbanking.account.AccountRepository;
import com.thahirudeen.netbanking.common.ConflictException;
import com.thahirudeen.netbanking.common.InvalidCredentialsException;
import com.thahirudeen.netbanking.config.JwtService;
import com.thahirudeen.netbanking.user.User;
import com.thahirudeen.netbanking.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    // User and Account are created together or not at all
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        User user = new User();
        user.setName(req.name().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        userRepository.save(user);

        Account account = new Account();
        account.setAccountNumber(accountNumberGenerator.generate());
        account.setUser(user);
        accountRepository.save(account);

        return new AuthResponse(jwtService.generate(user), account.getAccountNumber());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email().trim().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // same error as unknown email, so attackers cannot probe which emails exist
            throw new InvalidCredentialsException();
        }
        String accountNumber = accountRepository.findByUserId(user.getId())
                .map(Account::getAccountNumber).orElse(null);
        return new AuthResponse(jwtService.generate(user), accountNumber);
    }
}
