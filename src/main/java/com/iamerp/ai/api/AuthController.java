package com.iamerp.ai.api;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final DataSource dataSource;

    public AuthController(@Qualifier("supabaseDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── Step 1: Verify license key ───────────────────────────────────────────
    @PostMapping("/verify-license")
    public ResponseEntity<Map<String, Object>> verifyLicense(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String licenseKey = (String) request.get("license_key");
            if (licenseKey == null || licenseKey.isBlank()) {
                response.put("success", false);
                response.put("error", "License key is required.");
                return ResponseEntity.badRequest().body(response);
            }

            String sql = "SELECT company_id, company_name, owner_name FROM ai_companies " +
                         "WHERE license_key = ? AND is_active = TRUE";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, licenseKey.trim());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    response.put("success",      true);
                    response.put("company_id",   rs.getString("company_id"));
                    response.put("company_name", rs.getString("company_name"));
                    response.put("owner_name",   rs.getString("owner_name"));
                    return ResponseEntity.ok(response);
                } else {
                    response.put("success", false);
                    response.put("error", "Invalid or expired license key.");
                    return ResponseEntity.status(401).body(response);
                }
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ─── Step 2: User login ───────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String licenseKey = (String) request.get("license_key");
            String username   = (String) request.get("username");
            String password   = (String) request.get("password");

            if (licenseKey == null || username == null || password == null) {
                response.put("success", false);
                response.put("error", "License key, username and password are required.");
                return ResponseEntity.badRequest().body(response);
            }

            String sql = "SELECT u.full_name, u.role, u.company_id, c.company_name " +
                         "FROM ai_company_users u " +
                         "JOIN ai_companies c ON c.company_id = u.company_id " +
                         "WHERE u.username = ? AND u.password_hash = ? " +
                         "AND c.license_key = ? AND u.is_active = TRUE AND c.is_active = TRUE";

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username.trim().toLowerCase());
                ps.setString(2, password);
                ps.setString(3, licenseKey.trim());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    response.put("success",      true);
                    response.put("full_name",    rs.getString("full_name"));
                    response.put("role",         rs.getString("role"));
                    response.put("username",     username);
                    response.put("company_id",   rs.getString("company_id"));
                    response.put("company_name", rs.getString("company_name"));
                    return ResponseEntity.ok(response);
                } else {
                    response.put("success", false);
                    response.put("error", "Invalid username or password.");
                    return ResponseEntity.status(401).body(response);
                }
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Login error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}