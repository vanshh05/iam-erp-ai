package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dealer cancellation notifications. Completely separate from the Gemini/AI path.
 *
 * The ERP inserts a row into ai_dealers_notify_summary (status='open', notified=0)
 * when someone on the CRM/ERP side cancels a dealer's reservation. This class:
 *   - process()   : finds open + un-notified rows, sends a push to the dealer's
 *                   devices, and marks them notified=1  (run by the poll + poke).
 *   - listOpen()  : the dealer's open notifications, enriched with the item name
 *                   and colour looked up from ai_dealers_stock_summary by chassis.
 *   - ack()       : flips the dealer's open rows to status='close' once seen in-app.
 *   - saveToken() : stores a device's Expo push token.
 *
 * Every query is FULLY schema-qualified, so it rides the existing DataSource and
 * bypasses the primary/backup refresh router — exactly like DealerFunctions.
 */
@Component
public class DealerNotifyFunctions {

    private static final Logger log = LoggerFactory.getLogger(DealerNotifyFunctions.class);

    private final DataSource dataSource;
    private final ExpoPushService expoPush;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String notifyTable;   // ai_ayaam_dealers.ai_dealers_notify_summary
    private final String tokensTable;   // ai_ayaam_dealers.ai_dealer_push_tokens
    private final String stockTable;    // ai_ayaam_dealers.ai_dealers_stock_summary
    private final String specMapTable;  // ai_ayaam.ai_stock_spec_map

    public DealerNotifyFunctions(DataSource dataSource,
                                 ExpoPushService expoPush,
                                 @Value("${DB_DEALERS_SCHEMA:ai_ayaam_dealers}") String dealersSchema,
                                 @Value("${DB_PRIMARY_SCHEMA:ai_ayaam}") String primarySchema) {
        this.dataSource = dataSource;
        this.expoPush = expoPush;
        String ds = safeIdentifier(dealersSchema, "ai_ayaam_dealers");
        String ps = safeIdentifier(primarySchema, "ai_ayaam");
        this.notifyTable = ds + ".ai_dealers_notify_summary";
        this.tokensTable = ds + ".ai_dealer_push_tokens";
        this.stockTable = ds + ".ai_dealers_stock_summary";
        this.specMapTable = ps + ".ai_stock_spec_map";
        log.info("[DealerNotify] notifyTable='{}' tokensTable='{}'", notifyTable, tokensTable);
    }

    // ─── Send pushes for open + un-notified rows (poll + poke) ────────────────
    public int process() {
        int pushed = 0;
        try (Connection conn = dataSource.getConnection()) {
            // Collect first, then act — avoids nested statements on one streaming result.
            List<String[]> rows = new ArrayList<>();
            String sel = "SELECT company_id, chassis_no, party_id, created_by FROM " + notifyTable
                       + " WHERE LOWER(status) = 'open' AND (notified IS NULL OR notified = 0)";
            try (PreparedStatement ps = conn.prepareStatement(sel);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{
                        rs.getString("company_id"), rs.getString("chassis_no"),
                        rs.getString("party_id"), rs.getString("created_by")
                    });
                }
            }

            for (String[] r : rows) {
                String companyId = r[0];
                String rawChassis = r[1];
                String chassisNo = rawChassis == null ? "" : rawChassis.trim();
                String partyId = r[2];
                String cancelledBy = (r[3] == null || r[3].isBlank()) ? "the company" : r[3];
                if (companyId == null || chassisNo.isEmpty() || partyId == null || partyId.isBlank()) continue;

                // Friendly label from the stock table (item name + colour).
                String[] item = enrich(conn, companyId, chassisNo);
                String label = (item[0] != null && !item[0].isBlank()) ? item[0] : ("Chassis " + chassisNo);
                if (item[1] != null && !item[1].isBlank()) label = label + " (" + item[1] + ")";

                // Push carries NO remarks — just the tagline.
                String title = "Reservation cancelled";
                String body = "Your reservation for " + label + " was cancelled by " + cancelledBy + ".";

                List<String> tokens = tokensFor(conn, partyId);
                if (!tokens.isEmpty()) {
                    Map<String, String> data = new LinkedHashMap<>();
                    data.put("type", "reservation_cancelled");
                    data.put("chassis_no", chassisNo);
                    pushed += expoPush.send(tokens, title, body, data);
                }

                // Mark notified even if the dealer has no device registered, so we don't
                // retry forever; the in-app alert still covers them (status stays 'open').
                try (PreparedStatement up = conn.prepareStatement(
                        "UPDATE " + notifyTable + " SET notified = 1 "
                      + "WHERE company_id = ? AND chassis_no = ? AND party_id = ? "
                      + "AND LOWER(status) = 'open' AND (notified IS NULL OR notified = 0)")) {
                    up.setString(1, companyId);
                    up.setString(2, rawChassis);   // exact stored value
                    up.setString(3, partyId);
                    up.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.error("[DealerNotify] process error: {}", e.getMessage());
        }
        return pushed;
    }

    // ─── The dealer's open notifications (for the in-app alert) ───────────────
    public String listOpen(String companyId, String partyId) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode arr = mapper.createArrayNode();
        if (partyId == null || partyId.isBlank() || companyId == null) {
            result.put("success", true);
            result.put("count", 0);
            result.set("notifications", arr);
            return result.toString();
        }
        try (Connection conn = dataSource.getConnection()) {
            List<String[]> rows = new ArrayList<>();
            String sel = "SELECT chassis_no, created_by, created_date, remarks FROM " + notifyTable
                       + " WHERE company_id = ? AND party_id = ? AND LOWER(status) = 'open' "
                       + "ORDER BY created_date DESC";
            try (PreparedStatement ps = conn.prepareStatement(sel)) {
                ps.setString(1, companyId);
                ps.setString(2, partyId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[]{
                            rs.getString("chassis_no"), rs.getString("created_by"),
                            rs.getString("created_date"), rs.getString("remarks")
                        });
                    }
                }
            }
            for (String[] r : rows) {
                String chassisNo = r[0] == null ? "" : r[0].trim();
                String[] item = enrich(conn, companyId, chassisNo);
                ObjectNode n = mapper.createObjectNode();
                n.put("chassis_no", r[0]);
                n.put("item_name", item[0]);
                n.put("color", item[1]);
                n.put("cancelled_by", r[1]);
                n.put("created_date", r[2]);
                n.put("remarks", (r[3] == null || r[3].isBlank()) ? null : r[3]);
                arr.add(n);
            }
            result.put("success", true);
            result.put("count", arr.size());
            result.set("notifications", arr);
        } catch (SQLException e) {
            log.error("[DealerNotify] listOpen error: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "Database error: " + e.getMessage());
            result.set("notifications", arr);
        }
        return result.toString();
    }

    // ─── Mark the dealer's notifications seen (open -> close) ──────────────────
    public String ack(String companyId, String partyId, String username, List<String> chassisNos) {
        ObjectNode result = mapper.createObjectNode();
        if (partyId == null || partyId.isBlank() || companyId == null) {
            result.put("success", true);
            result.put("closed", 0);
            return result.toString();
        }
        try (Connection conn = dataSource.getConnection()) {
            int closed = 0;
            if (chassisNos != null && !chassisNos.isEmpty()) {
                String sql = "UPDATE " + notifyTable + " SET status = 'Close', "
                           + "last_updated_by = ?, last_updated_date = NOW() "
                           + "WHERE company_id = ? AND party_id = ? AND LOWER(status) = 'open' AND chassis_no = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (String ch : chassisNos) {
                        if (ch == null || ch.isBlank()) continue;
                        ps.setString(1, username);
                        ps.setString(2, companyId);
                        ps.setString(3, partyId);
                        ps.setString(4, ch.trim());
                        closed += ps.executeUpdate();
                    }
                }
            } else {
                // No list given — close ALL open for this dealer (the app's "dismiss" path).
                String sql = "UPDATE " + notifyTable + " SET status = 'Close', "
                           + "last_updated_by = ?, last_updated_date = NOW() "
                           + "WHERE company_id = ? AND party_id = ? AND LOWER(status) = 'open'";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, username);
                    ps.setString(2, companyId);
                    ps.setString(3, partyId);
                    closed = ps.executeUpdate();
                }
            }
            result.put("success", true);
            result.put("closed", closed);
        } catch (SQLException e) {
            log.error("[DealerNotify] ack error: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "Database error: " + e.getMessage());
        }
        return result.toString();
    }

    // ─── Store a device push token ────────────────────────────────────────────
    public String saveToken(String partyId, String companyId, String username, String token, String platform) {
        ObjectNode result = mapper.createObjectNode();
        if (token == null || token.isBlank() || partyId == null || partyId.isBlank()) {
            result.put("success", false);
            result.put("message", "token and party id are required.");
            return result.toString();
        }
        try (Connection conn = dataSource.getConnection()) {
            String sql = "INSERT INTO " + tokensTable
                       + " (party_id, company_id, username, token, platform, updated_date) "
                       + "VALUES (?, ?, ?, ?, ?, NOW()) "
                       + "ON DUPLICATE KEY UPDATE party_id = VALUES(party_id), company_id = VALUES(company_id), "
                       + "username = VALUES(username), platform = VALUES(platform), updated_date = NOW()";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, partyId);
                ps.setString(2, companyId);
                ps.setString(3, username);
                ps.setString(4, token.trim());
                ps.setString(5, platform);
                ps.executeUpdate();
            }
            result.put("success", true);
        } catch (SQLException e) {
            log.error("[DealerNotify] saveToken error: {}", e.getMessage());
            result.put("success", false);
            result.put("message", "Database error: " + e.getMessage());
        }
        return result.toString();
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Look up [item_name, colour] from the stock table by company + chassis. */
    private String[] enrich(Connection conn, String companyId, String chassisNo) {
        String name = null, color = null;
        try {
            Map<String, String> specMap = loadSpecMap(conn, companyId);
            String chassisCol = chassisColumnOf(specMap);
            String colorCol = colorColumnOf(specMap);
            if (chassisCol == null) return new String[]{null, null};
            String sql = "SELECT item_name" + (colorCol != null ? ", " + colorCol : "")
                       + " FROM " + stockTable + " WHERE company_id = ? AND " + chassisMatch(chassisCol) + " LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, companyId);
                ps.setString(2, chassisNo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        name = rs.getString("item_name");
                        if (colorCol != null) {
                            String c = rs.getString(colorCol);
                            if (c != null && !c.trim().isEmpty() && !"--".equals(c.trim())) color = c.trim();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[DealerNotify] enrich failed for {}/{}: {}", companyId, chassisNo, e.getMessage());
        }
        return new String[]{name, color};
    }

    private List<String> tokensFor(Connection conn, String partyId) throws SQLException {
        List<String> tokens = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT token FROM " + tokensTable + " WHERE party_id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("token");
                    if (t != null && !t.isBlank()) tokens.add(t);
                }
            }
        }
        return tokens;
    }

    /** spec_value_N -> human label for this company, from the primary schema spec map. */
    private Map<String, String> loadSpecMap(Connection conn, String companyId) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT spec_column, spec_label FROM " + specMapTable + " WHERE company_id = ?")) {
            ps.setString(1, companyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("spec_column");
                    String label = rs.getString("spec_label");
                    if (col != null && label != null && col.matches("spec_value_([1-9]|1[0-9]|20)")) {
                        map.put(col, label.trim());
                    }
                }
            }
        }
        return map;
    }

    private String chassisColumnOf(Map<String, String> specMap) {
        for (Map.Entry<String, String> e : specMap.entrySet()) {
            if (e.getValue().toLowerCase().contains("chassis")) return e.getKey();
        }
        return null;
    }

    private String colorColumnOf(Map<String, String> specMap) {
        for (Map.Entry<String, String> e : specMap.entrySet()) {
            String l = e.getValue().toLowerCase();
            if (l.contains("colour") || l.contains("color")) return e.getKey();
        }
        return null;
    }

    /** Whitespace-tolerant chassis match (mirrors the stock-table reserve path). */
    private static String chassisMatch(String chassisCol) {
        return "TRIM(BOTH '\\t' FROM TRIM(BOTH '\\r' FROM TRIM(BOTH '\\n' FROM TRIM(" + chassisCol + ")))) = ?";
    }

    private static String safeIdentifier(String s, String fallback) {
        return (s != null && s.matches("[A-Za-z0-9_]+")) ? s : fallback;
    }
}