package com.iamerp.ai.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Auth is temporarily disabled.
 * Login returns a default test user for all requests.
 * Company login and user login will be re-implemented later.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    @PostMapping("/verify-license")
    public ResponseEntity<Map<String, Object>> verifyLicense(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        response.put("success",      true);
        response.put("company_id",   "COMP001");
        response.put("company_name", "Ayaam ERP");
        response.put("owner_name",   "Owner");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        response.put("success",      true);
        response.put("full_name",    "Business Owner");
        response.put("role",         "Owner");
        response.put("username",     "owner");
        response.put("company_id",   "COMP001");
        response.put("company_name", "Ayaam ERP");
        return ResponseEntity.ok(response);
    }
}