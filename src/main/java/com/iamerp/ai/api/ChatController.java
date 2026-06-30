package com.iamerp.ai.api;

import com.iamerp.ai.gemini.GeminiService;
import com.iamerp.ai.security.CompanyResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GeminiService geminiService;
    private final CompanyResolver companyResolver;

    public ChatController(GeminiService geminiService, CompanyResolver companyResolver) {
        this.geminiService = geminiService;
        this.companyResolver = companyResolver;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String message   = (String) request.get("message");
            String language  = (String) request.getOrDefault("language", "en");

            // company comes from token for owners; admins may select one (sent as company_id)
            String requested = request.get("company_id") == null ? null : request.get("company_id").toString();
            String companyId = companyResolver.resolve(requested);

            if (companyResolver.currentUser() == null) {
                response.put("success", false);
                response.put("error", "Unauthorized");
                return ResponseEntity.status(401).body(response);
            }

            // Admin hasn't picked a company yet
            if (companyId == null) {
                response.put("success", true);
                response.put("reply", "Please select a company from the dropdown to view its data.");
                return ResponseEntity.ok(response);
            }

            if (message == null || message.isBlank()) {
                response.put("success", false);
                response.put("error", "Message cannot be empty");
                return ResponseEntity.badRequest().body(response);
            }

            List<Map<String, Object>> history = new ArrayList<>();
            Object historyObj = request.get("history");
            if (historyObj instanceof List<?> histList) {
                for (Object item : histList) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> turn = new HashMap<>();
                        turn.put("role", map.get("role"));
                        turn.put("text", map.get("text"));
                        history.add(turn);
                    }
                }
            }

            String reply = geminiService.chat(message.trim(), history, language, companyId);

            // Retry once if Gemini returned nothing usable (transient empty completion)
            if (reply == null || reply.trim().isEmpty()) {
                reply = geminiService.chat(message.trim(), history, language, companyId);
            }
            if (reply == null || reply.trim().isEmpty()) {
                reply = "The assistant couldn't generate a response for that. Please rephrase or try again.";
            }

            response.put("success", true);
            response.put("reply", reply);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Internal error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Ayaam ERP AI Assistant");
        response.put("timestamp", new Date().toString());
        return ResponseEntity.ok(response);
    }
}