package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends push notifications through Expo's push service
 * (https://exp.host/--/api/v2/push/send). One code path serves both iOS and
 * Android Expo push tokens — no SDK dependency, just the JDK HTTP client.
 *
 * Production (Render) has open outbound internet so it can reach Expo. If the
 * call fails we log and move on; the in-app alert still covers the dealer.
 */
@Service
public class ExpoPushService {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushService.class);
    private static final String EXPO_URL = "https://exp.host/--/api/v2/push/send";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Send one notification to many device tokens. Returns the number of
     * messages the request was accepted for (best-effort).
     */
    public int send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens == null || tokens.isEmpty()) return 0;
        try {
            ArrayNode messages = mapper.createArrayNode();
            for (String t : tokens) {
                if (t == null || t.isBlank()) continue;
                ObjectNode m = mapper.createObjectNode();
                m.put("to", t.trim());
                m.put("title", title);
                m.put("body", body);
                m.put("sound", "default");
                if (data != null && !data.isEmpty()) {
                    ObjectNode d = mapper.createObjectNode();
                    data.forEach(d::put);
                    m.set("data", d);
                }
                messages.add(m);
            }
            if (messages.isEmpty()) return 0;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(EXPO_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(messages.toString()))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) {
                log.info("[ExpoPush] sent {} message(s), status {}", messages.size(), res.statusCode());
                return messages.size();
            }
            log.warn("[ExpoPush] push failed status {} body {}", res.statusCode(), res.body());
            return 0;
        } catch (Exception e) {
            log.error("[ExpoPush] push error: {}", e.getMessage());
            return 0;
        }
    }
}
