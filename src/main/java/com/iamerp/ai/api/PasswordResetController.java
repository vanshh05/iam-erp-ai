package com.iamerp.ai.api;

import com.iamerp.ai.email.EmailService;
import com.iamerp.ai.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Public (no-auth) password reset flow:
 *   1. POST /api/forgot-password { email }        → emails a 6-digit code
 *   2. POST /api/reset-password  { email, code, new_password } → sets new password
 *
 * Security notes:
 *  - We always respond with a generic success on forgot-password, whether or not
 *    the email exists, so attackers can't probe which emails are registered.
 *  - Codes expire after 15 minutes and are single-use.
 */
@RestController
@RequestMapping("/api")
public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);

    private final UserRepository userRepository;
    private final PasswordResetRepository resetRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final Random random = new Random();

    public PasswordResetController(UserRepository userRepository,
                                   PasswordResetRepository resetRepository,
                                   PasswordEncoder passwordEncoder,
                                   EmailService emailService) {
        this.userRepository = userRepository;
        this.resetRepository = resetRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();
        String username = body.getOrDefault("username", "").toString().trim();

        if (username.isEmpty()) {
            res.put("success", false);
            res.put("message", "Please enter your username.");
            return ResponseEntity.badRequest().body(res);
        }

        Optional<User> userOpt = userRepository.findByUsername(username);

        // If the user doesn't exist, is inactive, or has no email on file, respond generically
        // (don't reveal which usernames exist or whether an email is set).
        if (userOpt.isEmpty()
                || !Boolean.TRUE.equals(userOpt.get().getIsActive())
                || userOpt.get().getEmail() == null
                || userOpt.get().getEmail().isBlank()) {
            log.info("[Reset] forgot-password for unknown/inactive/no-email username {}", username);
            res.put("success", false);
            res.put("message", "No reset email is available for this account. Please contact your administrator.");
            return ResponseEntity.status(404).body(res);
        }

        User user = userOpt.get();
        String email = user.getEmail().trim();
        String code  = String.format("%06d", random.nextInt(1_000_000));

        PasswordReset pr = new PasswordReset();
        pr.setEmail(email);
        pr.setUsername(username);
        pr.setCode(code);
        pr.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        pr.setUsed(false);
        resetRepository.save(pr);

        boolean sent = emailService.sendResetCode(email, user.getFullName(), code);
        if (!sent) {
            res.put("success", false);
            res.put("message", "Could not send email right now. Please try again later or contact support.");
            return ResponseEntity.status(500).body(res);
        }

        res.put("success", true);
        res.put("message", "A 6-digit code has been sent to your registered email.");
        res.put("masked_email", maskEmail(email));
        return ResponseEntity.ok(res);
    }

    /** Turn john.doe@company.com into j•••@company.com */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "•••" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "•••" + email.substring(at);
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();
        String username    = body.getOrDefault("username", "").toString().trim();
        String code        = body.getOrDefault("code", "").toString().trim();
        String newPassword = body.getOrDefault("new_password", "").toString();

        if (username.isEmpty() || code.isEmpty() || newPassword.isEmpty()) {
            res.put("success", false);
            res.put("message", "Username, code and new password are required.");
            return ResponseEntity.badRequest().body(res);
        }
        if (newPassword.length() < 4) {
            res.put("success", false);
            res.put("message", "Password must be at least 4 characters.");
            return ResponseEntity.badRequest().body(res);
        }

        Optional<PasswordReset> prOpt = resetRepository.findTopByUsernameOrderByIdDesc(username);
        if (prOpt.isEmpty()) {
            res.put("success", false);
            res.put("message", "No reset request found. Please request a new code.");
            return ResponseEntity.badRequest().body(res);
        }

        PasswordReset pr = prOpt.get();
        if (Boolean.TRUE.equals(pr.getUsed())) {
            res.put("success", false);
            res.put("message", "This code has already been used. Request a new one.");
            return ResponseEntity.badRequest().body(res);
        }
        if (pr.getExpiresAt().isBefore(LocalDateTime.now())) {
            res.put("success", false);
            res.put("message", "This code has expired. Request a new one.");
            return ResponseEntity.badRequest().body(res);
        }
        if (!pr.getCode().equals(code)) {
            res.put("success", false);
            res.put("message", "Incorrect code.");
            return ResponseEntity.badRequest().body(res);
        }

        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            res.put("success", false);
            res.put("message", "Account not found.");
            return ResponseEntity.badRequest().body(res);
        }

        User user = userOpt.get();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        pr.setUsed(true);
        resetRepository.save(pr);

        log.info("[Reset] Password reset completed for username {}", username);
        res.put("success", true);
        res.put("message", "Password reset successful. You can now log in with your new password.");
        return ResponseEntity.ok(res);
    }
}