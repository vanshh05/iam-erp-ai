package com.iamerp.ai.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Sends transactional email via Brevo's HTTP API.
 * Requires env vars:
 *   BREVO_API_KEY     — your Brevo API key
 *   BREVO_SENDER_EMAIL — a verified sender email in your Brevo account
 *   BREVO_SENDER_NAME  — display name (optional)
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Value("${brevo.api.key:}")
    private String apiKey;

    @Value("${brevo.sender.email:}")
    private String senderEmail;

    @Value("${brevo.sender.name:Ayaam ERP}")
    private String senderName;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.brevo.com/v3")
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns true if sent successfully. */
    public boolean sendResetCode(String toEmail, String toName, String code) {
        if (apiKey == null || apiKey.isBlank() || senderEmail == null || senderEmail.isBlank()) {
            log.error("[Email] Brevo not configured (missing BREVO_API_KEY or BREVO_SENDER_EMAIL).");
            return false;
        }

        String html = """
            <div style="font-family:Arial,sans-serif;max-width:480px;margin:auto">
              <h2 style="color:#1565C0">Password Reset Code</h2>
              <p>You requested to reset your Ayaam ERP password. Use the code below:</p>
              <div style="font-size:30px;font-weight:bold;letter-spacing:6px;color:#0D1B2A;
                          background:#EFF6FF;padding:14px;text-align:center;border-radius:10px;margin:18px 0">
                %s
              </div>
              <p style="color:#6B7280;font-size:14px">This code expires in 15 minutes. If you didn't
              request this, you can safely ignore this email.</p>
            </div>
            """.formatted(code);

        try {
            ObjectNode body = mapper.createObjectNode();

            ObjectNode sender = mapper.createObjectNode();
            sender.put("email", senderEmail);
            sender.put("name", senderName);
            body.set("sender", sender);

            ArrayNode to = mapper.createArrayNode();
            ObjectNode recipient = mapper.createObjectNode();
            recipient.put("email", toEmail);
            if (toName != null && !toName.isBlank()) recipient.put("name", toName);
            to.add(recipient);
            body.set("to", to);

            body.put("subject", "Your Ayaam ERP password reset code");
            body.put("htmlContent", html);

            webClient.post()
                    .uri("/smtp/email")
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body.toString())
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(15));

            log.info("[Email] Reset code sent to {}", toEmail);
            return true;
        } catch (Exception e) {
            log.error("[Email] Failed to send to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }
}