package com.iamerp.ai.api;

import com.iamerp.ai.functions.DealerNotifyFunctions;
import com.iamerp.ai.model.User;
import com.iamerp.ai.security.CompanyResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dealer notification endpoints — plain REST, no Gemini.
 *
 *  GET  /api/dealer/notifications        the dealer's open (unseen) notifications
 *  POST /api/dealer/notifications/ack    mark them seen (open -> close); empty body = all
 *  POST /api/dealer/push-token           register this device's Expo push token
 *  POST /api/dealer/notify/poke          ERP (or you, for testing) triggers an immediate
 *                                        push run. NOT dealer-authed — guarded by a shared
 *                                        secret header (X-Notify-Secret).
 *
 * The first three are locked to ROLE_DEALER by SecurityConfig. The poke path is
 * permitAll there and checks the secret here instead.
 */
@RestController
@RequestMapping("/api/dealer")
public class DealerNotifyController {

    private final DealerNotifyFunctions notify;
    private final CompanyResolver companyResolver;

    @Value("${DEALER_NOTIFY_POKE_SECRET:changeme}")
    private String pokeSecret;

    public DealerNotifyController(DealerNotifyFunctions notify, CompanyResolver companyResolver) {
        this.notify = notify;
        this.companyResolver = companyResolver;
    }

    @GetMapping(value = "/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> list() {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        return ResponseEntity.ok(notify.listOpen(u.getCompanyId(), u.getPartyId()));
    }

    @PostMapping(value = "/notifications/ack", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> ack(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        List<String> chassis = chassisListOf(body);
        return ResponseEntity.ok(notify.ack(u.getCompanyId(), u.getPartyId(), u.getUsername(), chassis));
    }

    @PostMapping(value = "/push-token", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> pushToken(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        String token = str(body, "token");
        String platform = str(body, "platform");
        return ResponseEntity.ok(notify.saveToken(u.getPartyId(), u.getCompanyId(), u.getUsername(), token, platform));
    }

    @PostMapping(value = "/notify/poke", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> poke(@RequestHeader(value = "X-Notify-Secret", required = false) String secret) {
        if (pokeSecret == null || secret == null || !pokeSecret.equals(secret)) {
            return ResponseEntity.status(401).body("{\"success\":false,\"message\":\"Invalid secret\"}");
        }
        int pushed = notify.process();
        return ResponseEntity.ok("{\"success\":true,\"pushed\":" + pushed + "}");
    }

    // ── helpers ──
    private static String str(Map<String, Object> m, String k) {
        if (m == null) return "";
        Object v = m.get(k);
        return v == null ? "" : v.toString().trim();
    }

    private static List<String> chassisListOf(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        Object v = body.get("chassis_nos");
        if (v == null) v = body.get("chassis");
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().trim().isEmpty()) out.add(o.toString().trim());
        } else if (v != null && !v.toString().trim().isEmpty()) {
            out.add(v.toString().trim());
        }
        return out;
    }

    private static ResponseEntity<String> unauthorized() {
        return ResponseEntity.status(401).body("{\"success\":false,\"message\":\"Unauthorized\"}");
    }
}
