package com.thahirudeen.netbanking.config;

import com.thahirudeen.netbanking.user.Role;
import com.thahirudeen.netbanking.user.User;
import com.thahirudeen.netbanking.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates the first ADMIN at startup from environment variables.
// There is deliberately no HTTP endpoint that can create an admin.
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.email:}")
    private String adminEmail;

    @Value("${app.admin.password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info("ADMIN_EMAIL / ADMIN_PASSWORD not set: no admin seeded");
            return;
        }
        if (adminPassword.length() < 8) {
            log.warn("ADMIN_PASSWORD is shorter than 8 characters: no admin seeded");
            return;
        }
        String email = adminEmail.trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            log.info("Admin seed skipped: {} already exists", email);
            return;
        }
        User admin = new User();
        admin.setName("Admin");
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        log.info("Seeded admin user {}", email);
    }
}
