package com.iamerp.ai.api;

import com.iamerp.ai.model.User;
import com.iamerp.ai.model.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Admin-only endpoints. The whole /api/admin/** path is locked to ROLE_ADMIN
 * in SecurityConfig, so every method here can assume the caller is an admin.
 *
 *  POST   /api/admin/users              create a user
 *  GET    /api/admin/users              list all users (no password hashes)
 *  PUT    /api/admin/users/{id}         edit a user (optional password reset)
 *  PATCH  /api/admin/users/{id}/status  enable/disable a user
 *  GET    /api/admin/companies          distinct company_ids found in the data
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;

    public AdminController(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           DataSource dataSource) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dataSource = dataSource;
    }

    // ─── Create user ──────────────────────────────────────────────────────────
    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();

        String username    = str(body, "username");
        String password    = str(body, "password");
        String fullName    = str(body, "full_name");
        String companyId   = str(body, "company_id");
        String companyName = str(body, "company_name");
        String role        = body.get("role") == null ? "Owner" : str(body, "role");

        if (username.isEmpty() || password.isEmpty() || companyId.isEmpty()) {
            res.put("success", false);
            res.put("message", "username, password and company_id are required.");
            return ResponseEntity.badRequest().body(res);
        }
        if (userRepository.existsByUsername(username)) {
            res.put("success", false);
            res.put("message", "Username already exists.");
            return ResponseEntity.status(409).body(res);
        }

        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setFullName(fullName);
        u.setRole(role);
        u.setCompanyId(companyId);
        u.setCompanyName(companyName);
        u.setIsActive(true);
        userRepository.save(u);

        res.put("success", true);
        res.put("message", "User created.");
        res.put("user", toView(u));
        return ResponseEntity.ok(res);
    }

    // ─── List users ───────────────────────────────────────────────────────────
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers() {
        List<Map<String, Object>> users = new ArrayList<>();
        for (User u : userRepository.findAll()) users.add(toView(u));
        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("users", users);
        return ResponseEntity.ok(res);
    }

    // ─── Edit user ────────────────────────────────────────────────────────────
    @PutMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> editUser(@PathVariable Long id,
                                                        @RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();
        Optional<User> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            res.put("success", false);
            res.put("message", "User not found.");
            return ResponseEntity.status(404).body(res);
        }
        User u = opt.get();

        if (body.containsKey("full_name"))    u.setFullName(str(body, "full_name"));
        if (body.containsKey("role"))         u.setRole(str(body, "role"));
        if (body.containsKey("company_id"))   u.setCompanyId(str(body, "company_id"));
        if (body.containsKey("company_name")) u.setCompanyName(str(body, "company_name"));
        // Optional password reset
        if (body.containsKey("password")) {
            String pw = str(body, "password");
            if (!pw.isEmpty()) u.setPasswordHash(passwordEncoder.encode(pw));
        }
        userRepository.save(u);

        res.put("success", true);
        res.put("message", "User updated.");
        res.put("user", toView(u));
        return ResponseEntity.ok(res);
    }

    // ─── Enable / disable user ────────────────────────────────────────────────
    @PatchMapping("/users/{id}/status")
    public ResponseEntity<Map<String, Object>> setStatus(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> body) {
        Map<String, Object> res = new HashMap<>();
        Optional<User> opt = userRepository.findById(id);
        if (opt.isEmpty()) {
            res.put("success", false);
            res.put("message", "User not found.");
            return ResponseEntity.status(404).body(res);
        }
        Object active = body.get("is_active");
        if (!(active instanceof Boolean)) {
            res.put("success", false);
            res.put("message", "is_active (true/false) is required.");
            return ResponseEntity.badRequest().body(res);
        }
        User u = opt.get();
        u.setIsActive((Boolean) active);
        userRepository.save(u);

        res.put("success", true);
        res.put("message", (Boolean) active ? "User enabled." : "User disabled.");
        res.put("user", toView(u));
        return ResponseEntity.ok(res);
    }

    // ─── Distinct companies from the actual data ──────────────────────────────
    // Pulls distinct company_id from the data tables (union), so the admin can
    // inspect any company present in the data even before a user exists for it.
    @GetMapping("/companies")
    public ResponseEntity<Map<String, Object>> companies() {
        Map<String, Object> res = new HashMap<>();
        Set<String> companies = new TreeSet<>();

        // Tables to union distinct company_id from
        List<String> tables = List.of(
            "ai_daily_summary", "ai_sales", "ai_purchase", "ai_party_status",
            "ai_collection_summary", "ai_payment_summary", "ai_bank_balance_summary",
            "ai_stock_summary", "ai_dispatch_summary"
        );

        try (Connection conn = dataSource.getConnection()) {
            for (String table : tables) {
                String sql = "SELECT DISTINCT company_id FROM " + table
                           + " WHERE company_id IS NOT NULL AND company_id <> ''";
                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String c = rs.getString("company_id");
                        if (c != null && !c.isBlank()) companies.add(c.trim());
                    }
                } catch (SQLException tableErr) {
                    // Table may not exist for every deployment — skip silently
                }
            }
        } catch (SQLException e) {
            res.put("success", false);
            res.put("message", "DB error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(res);
        }

        res.put("success", true);
        res.put("companies", new ArrayList<>(companies));
        return ResponseEntity.ok(res);
    }

    // ─── helpers ──
    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString().trim();
    }

    private static Map<String, Object> toView(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("full_name", u.getFullName());
        m.put("role", u.getRole());
        m.put("company_id", u.getCompanyId());
        m.put("company_name", u.getCompanyName());
        m.put("is_active", u.getIsActive());
        return m; // deliberately NO password hash
    }
}