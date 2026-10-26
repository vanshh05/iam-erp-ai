package com.iamerp.ai.api;

import com.iamerp.ai.functions.DealerFunctions;
import com.iamerp.ai.model.User;
import com.iamerp.ai.security.CompanyResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dealer endpoints — plain REST, NO Gemini. The whole /api/dealer/** path is
 * locked to ROLE_DEALER in SecurityConfig, so every method here can assume the
 * caller is a dealer.
 *
 * company_id and party_id ALWAYS come from the verified JWT-backed user, never
 * from the request body — a dealer cannot reserve for another company or spoof
 * another dealer's identity. The client only ever sends the chassis number of
 * the unit it wants.
 *
 *  GET  /api/dealer/stock       available pool + this dealer's own reservations
 *  POST /api/dealer/reserve     { "chassis_no": "..." }  reserve one unit
 *  POST /api/dealer/unreserve   { "chassis_no": "..." }  cancel own reservation
 */
@RestController
@RequestMapping("/api/dealer")
public class DealerController {

    private final DealerFunctions dealerFunctions;
    private final CompanyResolver companyResolver;

    public DealerController(DealerFunctions dealerFunctions, CompanyResolver companyResolver) {
        this.dealerFunctions = dealerFunctions;
        this.companyResolver = companyResolver;
    }

    @GetMapping(value = "/stock", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> stock() {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        String json = dealerFunctions.listStock(u.getCompanyId(), u.getPartyId());
        return ResponseEntity.ok(json);
    }

    @PostMapping(value = "/reserve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reserve(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        String chassis = chassisOf(body);
        String json = dealerFunctions.reserve(u.getCompanyId(), u.getPartyId(), u.getUsername(), chassis);
        return ResponseEntity.ok(json);
    }

    @PostMapping(value = "/unreserve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> unreserve(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        String chassis = chassisOf(body);
        String json = dealerFunctions.unreserve(u.getCompanyId(), u.getPartyId(), u.getUsername(), chassis);
        return ResponseEntity.ok(json);
    }

    // ─── Bulk reserve: body { "chassis_nos": ["...", "..."] } ─────────────────
    @PostMapping(value = "/reserve-bulk", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> reserveBulk(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        List<String> chassis = chassisListOf(body);
        String json = dealerFunctions.reserveBulk(u.getCompanyId(), u.getPartyId(), u.getUsername(), chassis);
        return ResponseEntity.ok(json);
    }

    // ─── Bulk un-reserve: body { "chassis_nos": ["...", "..."] } ──────────────
    @PostMapping(value = "/unreserve-bulk", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> unreserveBulk(@RequestBody(required = false) Map<String, Object> body) {
        User u = companyResolver.currentUser();
        if (u == null) return unauthorized();
        List<String> chassis = chassisListOf(body);
        String json = dealerFunctions.unreserveBulk(u.getCompanyId(), u.getPartyId(), u.getUsername(), chassis);
        return ResponseEntity.ok(json);
    }

    // ── helpers ──
    private static String chassisOf(Map<String, Object> body) {
        if (body == null) return "";
        Object v = body.get("chassis_no");
        if (v == null) v = body.get("chassis");   // accept either key
        return v == null ? "" : v.toString().trim();
    }

    /** Pull a list of chassis numbers from the body ("chassis_nos" array, or a single "chassis"). */
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