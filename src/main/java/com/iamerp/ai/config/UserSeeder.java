package com.iamerp.ai.config;

import com.iamerp.ai.model.User;
import com.iamerp.ai.model.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds two test users on startup if they don't already exist.
 * Passwords are BCrypt-hashed here at runtime — never stored as plaintext.
 *
 *   Company 1: username 'hotage'  password 'hotage@123'  company_id 'HOTAGE INDIA'
 *   Company 2: username 'abc'     password 'abc@123'     company_id 'ABC'
 *
 * Safe to leave in: it only inserts when the username is missing, so it won't
 * overwrite a changed password on restart. Remove or disable in production once
 * real users are created.
 */
@Component
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seed("admin",  "admin@123",  "System Admin", "ADMIN", "ADMIN", "Ayaam ERP Admin");
        seed("hotage", "hotage@123", "Hotage Owner", "Owner", "HOTAGE INDIA", "Hotage India");
        seed("abc",    "abc@123",    "ABC Owner",    "Owner", "ABC",          "ABC Traders");
    }

    private void seed(String username, String rawPassword, String fullName,
                      String role, String companyId, String companyName) {
        if (userRepository.existsByUsername(username)) {
            log.info("[Seeder] User '{}' already exists — skipping.", username);
            return;
        }
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setFullName(fullName);
        u.setRole(role);
        u.setCompanyId(companyId);
        u.setCompanyName(companyName);
        u.setIsActive(true);
        userRepository.save(u);
        log.info("[Seeder] Created test user '{}' for company '{}'.", username, companyId);
    }
}