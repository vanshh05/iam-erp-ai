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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Dealer stock + reservation logic. Completely separate from the Gemini/AI path.
 *
 * Reads and writes the dealer stock table in its OWN schema (ai_ayaam_dealers),
 * and resolves spec-column labels (Color, Chassis Number, …) from the primary
 * schema's ai_stock_spec_map — exactly the same mapping used by stock/dispatch.
 *
 * EVERY query here is FULLY schema-qualified (schema.table). Fully-qualified
 * names ignore the connection's current default catalog, so these queries ride
 * the existing DataSource and completely bypass the primary/backup refresh
 * router — a dealer's reserve can never land mid-swap.
 *
 * Writes are UPDATE-only (reserve / un-reserve). Rows are never inserted or
 * deleted here — those come only from the ERP sync.
 */
@Component
public class DealerFunctions {

    private static final Logger log = LoggerFactory.getLogger(DealerFunctions.class);

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String dealersSchema;   // e.g. ai_ayaam_dealers
    private final String primarySchema;   // e.g. ai_ayaam  (holds ai_stock_spec_map)
    private final String dealersTable;    // ai_ayaam_dealers.ai_dealers_stock_summary (qualified)
    private final String specMapTable;    // ai_ayaam.ai_stock_spec_map (qualified)

    public DealerFunctions(DataSource dataSource,
                           @Value("${DB_DEALERS_SCHEMA:ai_ayaam_dealers}") String dealersSchema,
                           @Value("${DB_PRIMARY_SCHEMA:ai_ayaam}") String primarySchema) {
        this.dataSource = dataSource;
        // Validate schema names (config, not user input, but we interpolate them into SQL).
        this.dealersSchema = safeIdentifier(dealersSchema, "ai_ayaam_dealers");
        this.primarySchema = safeIdentifier(primarySchema, "ai_ayaam");
        this.dealersTable = this.dealersSchema + ".ai_dealers_stock_summary";
        this.specMapTable = this.primarySchema + ".ai_stock_spec_map";
        log.info("[DealerFunctions] dealersTable='{}' specMapTable='{}'", dealersTable, specMapTable);
    }

    // ─── READ: available pool + this dealer's own reservations ────────────────
    public String listStock(String companyId, String partyId) {
        if (companyId == null || companyId.isBlank()) {
            return errorJson("No company resolved for this dealer.");
        }
        try (Connection conn = dataSource.getConnection()) {
            Map<String, String> specMap = loadSpecMap(conn, companyId); // spec_value_N -> label

            ArrayNode available = fetchRows(conn, companyId,
                "item_status = 'Available'", null, specMap);

            ArrayNode myReservations = (partyId == null || partyId.isBlank())
                ? mapper.createArrayNode()
                : fetchRows(conn, companyId, "item_status = 'Reserved' AND party_id = ?", partyId, specMap);

            ObjectNode result = mapper.createObjectNode();
            result.put("success", true);
            result.put("available_count", available.size());
            result.put("reserved_count", myReservations.size());
            result.set("available", available);
            result.set("my_reservations", myReservations);
            return result.toString();

        } catch (SQLException e) {
            log.error("[DealerFunctions] listStock DB error: {}", e.getMessage());
            return errorJson("Database error: " + e.getMessage());
        }
    }

    private ArrayNode fetchRows(Connection conn, String companyId, String statusClause,
                                String partyIdParam, Map<String, String> specMap) throws SQLException {
        String sql = "SELECT item_name, quantity, "
                   + "spec_value_1, spec_value_2, spec_value_3, spec_value_4, spec_value_5, "
                   + "spec_value_6, spec_value_7, spec_value_8, spec_value_9, spec_value_10, "
                   + "spec_value_11, spec_value_12, spec_value_13, spec_value_14, spec_value_15, "
                   + "spec_value_16, spec_value_17, spec_value_18, spec_value_19, spec_value_20, "
                   + "item_status "
                   + "FROM " + dealersTable + " "
                   + "WHERE company_id = ? AND " + statusClause + " "
                   + "ORDER BY item_name LIMIT 5000";

        ArrayNode rows = mapper.createArrayNode();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, companyId);
            if (partyIdParam != null) ps.setString(2, partyIdParam);

            String chassisCol = chassisColumnOf(specMap); // which spec_value_N is the chassis

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("item_name", rs.getString("item_name"));
                row.put("item_status", rs.getString("item_status"));

                // Build the labelled spec object + capture the chassis value (the reserve key)
                ObjectNode specs = mapper.createObjectNode();
                String chassisValue = null;
                for (int i = 1; i <= 20; i++) {
                    String col = "spec_value_" + i;
                    String val = rs.getString(col);
                    if (val == null) continue;
                    val = val.trim();
                    if (val.isEmpty() || "--".equals(val)) continue;
                    String label = specMap.get(col);
                    if (label == null) continue;            // only show mapped specs
                    specs.put(label, val);
                    if (col.equals(chassisCol)) chassisValue = val;
                }
                row.set("specs", specs);
                // chassis_no is the unique identifier the app sends back to reserve/un-reserve
                row.put("chassis_no", chassisValue);
                rows.add(row);
            }
        }
        return rows;
    }

    // ─── WRITE: reserve one available unit (first-tap-wins) ───────────────────
    public String reserve(String companyId, String partyId, String username, String chassisNo) {
        if (partyId == null || partyId.isBlank()) {
            return errorJson("This dealer account has no party id configured. Contact the administrator.");
        }
        if (chassisNo == null || chassisNo.isBlank()) {
            return errorJson("No item selected to reserve.");
        }
        try (Connection conn = dataSource.getConnection()) {
            String chassisCol = chassisColumnOf(loadSpecMap(conn, companyId));
            if (chassisCol == null) {
                return errorJson("Chassis mapping not found for this company. Contact the administrator.");
            }
            // Conditional update — only succeeds if the row is still Available.
            String sql = "UPDATE " + dealersTable + " SET "
                       + "item_status = 'Reserved', is_available = 0, party_id = ?, "
                       + "last_updated_by = ?, last_updated_date = NOW(), status = 'open' "
                       + "WHERE company_id = ? AND " + chassisCol + " = ? AND item_status = 'Available'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, partyId);
                ps.setString(2, username);
                ps.setString(3, companyId);
                ps.setString(4, chassisNo);
                int changed = ps.executeUpdate();
                log.info("[DealerFunctions] reserve company='{}' chassis='{}' by='{}' changed={}",
                         companyId, chassisNo, username, changed);
                if (changed == 1) {
                    return successJson("Item reserved.");
                }
                return errorJson("That item is no longer available — someone may have just reserved it.");
            }
        } catch (SQLException e) {
            log.error("[DealerFunctions] reserve DB error: {}", e.getMessage());
            return errorJson("Database error: " + e.getMessage());
        }
    }

    // ─── WRITE: un-reserve this dealer's own reservation (full revert) ────────
    public String unreserve(String companyId, String partyId, String username, String chassisNo) {
        if (partyId == null || partyId.isBlank()) {
            return errorJson("This dealer account has no party id configured. Contact the administrator.");
        }
        if (chassisNo == null || chassisNo.isBlank()) {
            return errorJson("No item selected to un-reserve.");
        }
        try (Connection conn = dataSource.getConnection()) {
            String chassisCol = chassisColumnOf(loadSpecMap(conn, companyId));
            if (chassisCol == null) {
                return errorJson("Chassis mapping not found for this company. Contact the administrator.");
            }
            // Return the unit to the available pool, but keep who/when as an audit stamp and
            // flag status='open' for the ERP to pick up. Only the dealer's OWN reservation.
            String sql = "UPDATE " + dealersTable + " SET "
                       + "item_status = 'Available', is_available = 1, party_id = NULL, "
                       + "last_updated_by = ?, last_updated_date = NOW(), status = 'open' "
                       + "WHERE company_id = ? AND " + chassisCol + " = ? "
                       + "AND item_status = 'Reserved' AND party_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, companyId);
                ps.setString(3, chassisNo);
                ps.setString(4, partyId);
                int changed = ps.executeUpdate();
                log.info("[DealerFunctions] unreserve company='{}' chassis='{}' party='{}' changed={}",
                         companyId, chassisNo, partyId, changed);
                if (changed == 1) {
                    return successJson("Reservation cancelled.");
                }
                return errorJson("That reservation was not found under your account.");
            }
        } catch (SQLException e) {
            log.error("[DealerFunctions] unreserve DB error: {}", e.getMessage());
            return errorJson("Database error: " + e.getMessage());
        }
    }

    // ─── WRITE: reserve several available units in one request ────────────────
    // Best-effort: each unit is reserved independently. Units that were snatched
    // between load and tap are reported in "failed"; the rest still go through.
    // First-tap-wins is preserved per unit (the same conditional UPDATE).
    public String reserveBulk(String companyId, String partyId, String username, List<String> chassisNos) {
        if (partyId == null || partyId.isBlank()) {
            return errorJson("This dealer account has no party id configured. Contact the administrator.");
        }
        LinkedHashSet<String> unique = dedupe(chassisNos);
        if (unique.isEmpty()) {
            return errorJson("No items selected to reserve.");
        }
        try (Connection conn = dataSource.getConnection()) {
            String chassisCol = chassisColumnOf(loadSpecMap(conn, companyId));
            if (chassisCol == null) {
                return errorJson("Chassis mapping not found for this company. Contact the administrator.");
            }
            String sql = "UPDATE " + dealersTable + " SET "
                       + "item_status = 'Reserved', is_available = 0, party_id = ?, "
                       + "last_updated_by = ?, last_updated_date = NOW(), status = 'open' "
                       + "WHERE company_id = ? AND " + chassisCol + " = ? AND item_status = 'Available'";

            ArrayNode reserved = mapper.createArrayNode();
            ArrayNode failed   = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String chassis : unique) {
                    ps.setString(1, partyId);
                    ps.setString(2, username);
                    ps.setString(3, companyId);
                    ps.setString(4, chassis);
                    if (ps.executeUpdate() == 1) {
                        reserved.add(chassis);
                    } else {
                        failed.add(failRow(chassis, "No longer available"));
                    }
                }
            }
            log.info("[DealerFunctions] reserveBulk company='{}' by='{}' reserved={} failed={}",
                     companyId, username, reserved.size(), failed.size());

            ObjectNode res = mapper.createObjectNode();
            res.put("success", true);
            res.put("reserved_count", reserved.size());
            res.put("failed_count", failed.size());
            res.set("reserved", reserved);
            res.set("failed", failed);
            return res.toString();
        } catch (SQLException e) {
            log.error("[DealerFunctions] reserveBulk DB error: {}", e.getMessage());
            return errorJson("Database error: " + e.getMessage());
        }
    }

    // ─── WRITE: cancel several of this dealer's own reservations in one request ─
    public String unreserveBulk(String companyId, String partyId, String username, List<String> chassisNos) {
        if (partyId == null || partyId.isBlank()) {
            return errorJson("This dealer account has no party id configured. Contact the administrator.");
        }
        LinkedHashSet<String> unique = dedupe(chassisNos);
        if (unique.isEmpty()) {
            return errorJson("No items selected to un-reserve.");
        }
        try (Connection conn = dataSource.getConnection()) {
            String chassisCol = chassisColumnOf(loadSpecMap(conn, companyId));
            if (chassisCol == null) {
                return errorJson("Chassis mapping not found for this company. Contact the administrator.");
            }
            String sql = "UPDATE " + dealersTable + " SET "
                       + "item_status = 'Available', is_available = 1, party_id = NULL, "
                       + "last_updated_by = ?, last_updated_date = NOW(), status = 'open' "
                       + "WHERE company_id = ? AND " + chassisCol + " = ? "
                       + "AND item_status = 'Reserved' AND party_id = ?";

            ArrayNode unreserved = mapper.createArrayNode();
            ArrayNode failed     = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (String chassis : unique) {
                    ps.setString(1, username);
                    ps.setString(2, companyId);
                    ps.setString(3, chassis);
                    ps.setString(4, partyId);
                    if (ps.executeUpdate() == 1) {
                        unreserved.add(chassis);
                    } else {
                        failed.add(failRow(chassis, "Not found under your account"));
                    }
                }
            }
            log.info("[DealerFunctions] unreserveBulk company='{}' party='{}' unreserved={} failed={}",
                     companyId, partyId, unreserved.size(), failed.size());

            ObjectNode res = mapper.createObjectNode();
            res.put("success", true);
            res.put("unreserved_count", unreserved.size());
            res.put("failed_count", failed.size());
            res.set("unreserved", unreserved);
            res.set("failed", failed);
            return res.toString();
        } catch (SQLException e) {
            log.error("[DealerFunctions] unreserveBulk DB error: {}", e.getMessage());
            return errorJson("Database error: " + e.getMessage());
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Trim, drop blanks, and de-duplicate a list of chassis numbers (order kept). */
    private static LinkedHashSet<String> dedupe(List<String> chassisNos) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (chassisNos != null) {
            for (String c : chassisNos) {
                if (c != null && !c.isBlank()) unique.add(c.trim());
            }
        }
        return unique;
    }

    private ObjectNode failRow(String chassis, String message) {
        ObjectNode f = mapper.createObjectNode();
        f.put("chassis_no", chassis);
        f.put("message", message);
        return f;
    }

    /** spec_value_N -> human label, for this company, from the primary schema's spec map. */
    private Map<String, String> loadSpecMap(Connection conn, String companyId) throws SQLException {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = "SELECT spec_column, spec_label FROM " + specMapTable + " WHERE company_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String col = rs.getString("spec_column");
                String label = rs.getString("spec_label");
                if (col != null && label != null && col.matches("spec_value_([1-9]|1[0-9]|20)")) {
                    map.put(col, label.trim());
                }
            }
        }
        return map;
    }

    /** The spec_value_N column whose label is the chassis number, or null if none. */
    private String chassisColumnOf(Map<String, String> specMap) {
        for (Map.Entry<String, String> e : specMap.entrySet()) {
            String label = e.getValue().toLowerCase();
            if (label.contains("chassis")) {
                // key is already validated as spec_value_N in loadSpecMap
                return e.getKey();
            }
        }
        return null;
    }

    /** Only allow simple identifiers for interpolated schema names; fall back to default. */
    private static String safeIdentifier(String s, String fallback) {
        return (s != null && s.matches("[A-Za-z0-9_]+")) ? s : fallback;
    }

    private String successJson(String message) {
        ObjectNode o = mapper.createObjectNode();
        o.put("success", true);
        o.put("message", message);
        return o.toString();
    }

    private String errorJson(String message) {
        ObjectNode o = mapper.createObjectNode();
        o.put("success", false);
        o.put("message", message);
        return o.toString();
    }
}