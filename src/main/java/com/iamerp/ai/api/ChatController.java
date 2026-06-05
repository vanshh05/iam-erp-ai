package com.iamerp.ai.api;

import com.iamerp.ai.gemini.GeminiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final GeminiService geminiService;

    public ChatController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String message   = (String) request.get("message");
            String language  = (String) request.getOrDefault("language", "en");
            String companyId = (String) request.getOrDefault("company_id", "COMP001");

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

            String reply = method(message, history, language, companyId);
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

    private String method(String message, List<Map<String, Object>> history, String language, String companyId) {
        String reply = geminiService.chat(message.trim(), history, language, companyId);
        return reply;
    }
}