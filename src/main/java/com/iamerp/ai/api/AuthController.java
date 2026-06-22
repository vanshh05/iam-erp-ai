package com.iamerp.ai.api;

import com.iamerp.ai.model.User;
import com.iamerp.ai.model.UserRepository;
import com.iamerp.ai.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Real authentication. Verifies username + password (BCrypt) against ai_users
 * and returns a signed JWT on success. The token carries the company_id, which
 * all subsequent requests are scoped to.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();

        String username = request.getOrDefault("username", "").toString().trim();
        String password = request.getOrDefault("password", "").toString();

        if (username.isEmpty() || password.isEmpty()) {
            response.put("success", false);
            response.put("message", "Username and password are required.");
            return ResponseEntity.status(400).body(response);
        }

        Optional<User> userOpt = userRepository.findByUsername(username);

        // Same generic message whether user not found or wrong password (don't leak which)
        if (userOpt.isEmpty()
                || !Boolean.TRUE.equals(userOpt.get().getIsActive())
                || !passwordEncoder.matches(password, userOpt.get().getPasswordHash())) {
            response.put("success", false);
            response.put("message", "Invalid username or password.");
            return ResponseEntity.status(401).body(response);
        }

        User user = userOpt.get();
        String token = jwtService.generateToken(user.getUsername(), user.getCompanyId(), user.getRole());

        response.put("success",      true);
        response.put("token",        token);
        response.put("full_name",    user.getFullName());
        response.put("role",         user.getRole());
        response.put("username",     user.getUsername());
        response.put("company_id",   user.getCompanyId());
        response.put("company_name", user.getCompanyName());
        return ResponseEntity.ok(response);
    }
}