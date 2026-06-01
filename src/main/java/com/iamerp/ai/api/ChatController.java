package com.iamerp.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iamerp.ai.gemini.GeminiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller exposing the chat API.
 * POST /api/chat  — main endpoint the frontend calls
 * GET  /api/health — health check
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(GeminiService geminiService) {
        this.geminiService = geminiService;
    }

    // ─── Chat endpoint ────────────────────────────────────────────────────────

    /**
     * Request body:
     * {
     *   "message": "What is today's sale?",
     *   "history": [
     *     { "role": "user", "text": "..." },
     *     { "role": "model", "text": "..." }
     *   ]
     * }
     *
     * Response:
     * {
     *   "reply": "Today's total sales are ₹1,24,500 across 38 bills...",
     *   "success": true
     * }
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String message = (String) request.get("message");
            String language = (String) request.getOrDefault("language", "en");
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

            String reply = geminiService.chat(message.trim(), history, language);

            response.put("success", true);
            response.put("reply", reply);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Internal error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // ─── Health check ─────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "IAM ERP AI Assistant");
        response.put("timestamp", new Date().toString());
        return ResponseEntity.ok(response);
    }
}