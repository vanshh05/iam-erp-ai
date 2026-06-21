package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Handles all dispatch-related queries against ai_dispatch_summary.
 * Spec column labels are shared with stock — reads from ai_stock_spec_map (same table).
 *
 * Key design (mirrors StockFunctions):
 *  1. Always fetch the spec map for the company first — translates spec_value_N → human label
 *  2. Date-aware summary queries for today / this month / all-time
 *  3. Full detail query — returns only populated spec columns, labeled from spec map
 *  4. Search by any spec value (chassis number, motor number, etc.)
 *  5. All SQL is parameterized — no raw string interpolation
 */
@Service
public class DispatchFunctions {

    private static final Logger log = LoggerFactory.getLogger(DispatchFunctions.class);
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    // Fixed columns always present in ai_dispatch_summary
    private static final Set<String> FIXED_COLS = Set.of(
        "company_id", "item_name", "party_name", "invoice_no", "invoice_date", "quantity"
    );

    // All allowed spec columns
    private static final List<String> SPEC_COLS = List.of(
        "spec_value_1","spec_value_2","spec_value_3","spec_value_4","spec_value_5",
        "spec_value_6","spec_value_7","spec_value_8","spec_value_9","spec_value_10",
        "spec_value_11","spec_value_12","spec_value_13","spec_value_14","spec_value_15",
        "spec_value_16","spec_value_17","spec_value_18","spec_value_19","spec_value_20"
    );

    public DispatchFunctions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── 1. GET DISPATCH SPEC MAP ─────────────────────────────────────────────
    public String getDispatchSpecMap(String companyId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT spec_column, spec_label FROM ai_stock_spec_map WHERE company_id = ? ORDER BY spec_column")) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();

            ObjectNode result    = mapper.createObjectNode();
            ObjectNode labelToCol = mapper.createObjectNode();
            ObjectNode colToLabel = mapper.createObjectNode();
            boolean found = false;
            while (rs.next()) {
                String col   = rs.getString("spec_column");
                String label = rs.getString("spec_label");
                labelToCol.put(label.toLowerCase(), col);
                labelToCol.put(label, col);
                colToLabel.put(col, label);
                found = true;
            }
            if (!found) {
                return "{\"error\":\"No dispatch spec map found for company. Please set up ai_stock_spec_map first.\"}";
            }
            result.set("label_to_column", labelToCol);
            result.set("column_to_label", colToLabel);
            result.put("company_id", companyId);
            log.info("[Dispatch] Spec map for {}: {}", companyId, colToLabel);
            return result.toString();
        } catch (SQLException e) {
            return "{\"error\":\"DB error fetching dispatch spec map: " + e.getMessage() + "\"}";
        }
    }

    // ─── 2. DISPATCH SUMMARY — today / this month / all-time ─────────────────
    // period: "today" | "month" | "all"
    // Returns: total vehicles dispatched, unique parties count, breakdown by item
    // period: "today" | "month" | "all"
    // month: 1-12 for a specific month (0 = current month), only used when period="month"
    // year:  e.g. 2026 (0 = current year), only used when period="month"
    public String getDispatchSummary(String companyId, String period, int month, int year) {
        String dateFilter;
        String periodLabel;

        String periodLower = period.toLowerCase();
        if (periodLower.equals("today")) {
            dateFilter  = " AND DATE(invoice_date) = CURDATE()";
            periodLabel = "Today";
        } else if (periodLower.equals("month")) {
            if (month > 0 && year > 0) {
                // Specific month and year requested — e.g. May 2026
                dateFilter  = " AND YEAR(invoice_date) = " + year + " AND MONTH(invoice_date) = " + month;
                periodLabel = java.time.Month.of(month).getDisplayName(
                    java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + year;
            } else if (month > 0) {
                // Specific month, current year
                int currentYear = java.time.LocalDate.now().getYear();
                dateFilter  = " AND YEAR(invoice_date) = " + currentYear + " AND MONTH(invoice_date) = " + month;
                periodLabel = java.time.Month.of(month).getDisplayName(
                    java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + currentYear;
            } else {
                // Default: current calendar month
                dateFilter  = " AND YEAR(invoice_date) = YEAR(CURDATE()) AND MONTH(invoice_date) = MONTH(CURDATE())";
                periodLabel = "This Month";
            }
        } else {
            dateFilter  = ""; // all-time
            periodLabel = "Total (All Time)";
        }

        String sql = "SELECT "
            + "  COUNT(*) AS total_entries, "
            + "  COALESCE(SUM(quantity), 0) AS total_vehicles, "
            + "  COUNT(DISTINCT party_name) AS unique_parties "
            + "FROM ai_dispatch_summary "
            + "WHERE company_id = ?" + dateFilter;

        String sqlByItem = "SELECT "
            + "  item_name, "
            + "  COUNT(*) AS entries, "
            + "  COALESCE(SUM(quantity), 0) AS total_quantity "
            + "FROM ai_dispatch_summary "
            + "WHERE company_id = ?" + dateFilter
            + " GROUP BY item_name ORDER BY total_quantity DESC";

        log.info("[Dispatch] Summary SQL: {}", sql);

        try (Connection conn = dataSource.getConnection()) {

            // Overall totals
            ObjectNode result = mapper.createObjectNode();
            result.put("period", periodLabel);
            result.put("company_id", companyId);

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, companyId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    result.put("total_vehicles_dispatched", rs.getLong("total_vehicles"));
                    result.put("unique_parties_served", rs.getLong("unique_parties"));
                    result.put("total_invoice_entries", rs.getLong("total_entries"));
                }
            }

            // Breakdown by item
            ArrayNode byItem = mapper.createArrayNode();
            try (PreparedStatement ps = conn.prepareStatement(sqlByItem)) {
                ps.setString(1, companyId);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    ObjectNode row = mapper.createObjectNode();
                    row.put("item_name",      rs.getString("item_name"));
                    row.put("total_quantity", rs.getLong("total_quantity"));
                    byItem.add(row);
                }
            }

            if (result.path("total_vehicles_dispatched").asLong(0) == 0) {
                return "No dispatch records found for " + periodLabel.toLowerCase() + ".";
            }

            result.set("breakdown_by_item", byItem);
            return result.toString();

        } catch (SQLException e) {
            log.error("[Dispatch] Summary error: {}", e.getMessage());
            return "Database error: " + e.getMessage();
        }
    }

    // ─── 3. QUERY DISPATCH — full detail or filtered search ──────────────────
    // filters: list of {label, operator, value}
    // Spec columns are shown only if they have data AND are mapped in spec map
    // spec values labeled using human names from spec map
    public String queryDispatch(
            String companyId,
            List<Map<String, String>> filters,
            String groupByLabel,
            String orderByLabel,
            String sortDir,
            int limit
    ) {
        // Step 1: fetch spec map
        Map<String, String> labelToCol = new LinkedHashMap<>();
        Map<String, String> colToLabel = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT spec_column, spec_label FROM ai_stock_spec_map WHERE company_id = ?")) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String col   = rs.getString("spec_column");
                String label = rs.getString("spec_label");
                labelToCol.put(label.toLowerCase(), col);
                labelToCol.put(label, col);
                colToLabel.put(col, label);
            }
        } catch (SQLException e) {
            return "Database error loading dispatch spec map: " + e.getMessage();
        }

        // Step 2: resolve filter columns
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        params.add(companyId);

        if (filters != null) {
            for (Map<String, String> f : filters) {
                String rawLabel = f.getOrDefault("label", "").trim();
                String op       = f.getOrDefault("operator", "=").trim();
                String val      = f.getOrDefault("value", "").trim();
                if (rawLabel.isBlank() || val.isBlank()) continue;

                String col;
                if (FIXED_COLS.contains(rawLabel.toLowerCase())) {
                    col = rawLabel.toLowerCase();
                } else if (SPEC_COLS.contains(rawLabel.toLowerCase())) {
                    col = rawLabel.toLowerCase();
                } else {
                    col = labelToCol.get(rawLabel.toLowerCase());
                    if (col == null) col = labelToCol.get(rawLabel);
                    if (col == null) {
                        return "ERROR: Unknown specification '" + rawLabel + "'. Available: " + labelToCol.keySet();
                    }
                }

                switch (op.toUpperCase()) {
                    case "="    -> { where.append(" AND LOWER(").append(col).append(") = LOWER(?)");    params.add(val); }
                    case "!="   -> { where.append(" AND LOWER(").append(col).append(") != LOWER(?)");   params.add(val); }
                    case "LIKE" -> { where.append(" AND LOWER(").append(col).append(") LIKE LOWER(?)"); params.add("%" + val + "%"); }
                    case ">","<",">=","<=" -> { where.append(" AND ").append(col).append(" ").append(op).append(" ?"); params.add(val); }
                    case "IN"   -> {
                        String[] vals = val.split(",");
                        String ph = String.join(",", Collections.nCopies(vals.length, "?"));
                        where.append(" AND LOWER(").append(col).append(") IN (").append(ph).append(")");
                        for (String v : vals) params.add(v.trim().toLowerCase());
                    }
                    default -> log.warn("[Dispatch] Unknown operator: {}", op);
                }
            }
        }

        // Step 3: GROUP BY
        String groupCol = null;
        if (groupByLabel != null && !groupByLabel.isBlank()) {
            if (FIXED_COLS.contains(groupByLabel.toLowerCase())) {
                groupCol = groupByLabel.toLowerCase();
            } else {
                groupCol = labelToCol.getOrDefault(groupByLabel.toLowerCase(),
                           labelToCol.getOrDefault(groupByLabel, null));
            }
        }
        String groupClause = (groupCol != null) ? " GROUP BY " + groupCol : "";

        // Step 4: ORDER BY — safe against only_full_group_by
        String orderClause = "";
        String safeSort = "DESC".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        if (orderByLabel != null && !orderByLabel.isBlank()) {
            String ob = orderByLabel.trim().toLowerCase();
            String orderCol;
            if (groupCol != null) {
                orderCol = switch (ob) {
                    case "quantity", "total_quantity" -> "total_quantity";
                    case "entries", "count", "item_count" -> "entries";
                    default -> (ob.equals(groupCol) ? groupCol : "total_quantity");
                };
            } else if (FIXED_COLS.contains(ob)) {
                orderCol = ob;
            } else {
                orderCol = labelToCol.getOrDefault(ob, labelToCol.getOrDefault(orderByLabel, null));
            }
            if (orderCol != null) orderClause = " ORDER BY " + orderCol + " " + safeSort;
        }

        int safeLimit = Math.min(Math.max(limit <= 0 ? 500 : limit, 1), 2000);

        // Step 5: SELECT
        // When grouping: group col + aggregates only
        // When not grouping: fixed cols + only mapped AND populated spec cols
        String selectClause;
        if (groupCol != null) {
            selectClause = groupCol + ", SUM(quantity) AS total_quantity, COUNT(*) AS entries, COUNT(DISTINCT party_name) AS unique_parties";
        } else {
            // Include only mapped spec columns (only those in spec map for this company)
            List<String> selectCols = new ArrayList<>(List.of("item_name", "party_name", "invoice_no", "invoice_date", "quantity"));
            for (String specCol : SPEC_COLS) {
                if (colToLabel.containsKey(specCol)) selectCols.add(specCol);
            }
            selectClause = String.join(", ", selectCols);
        }

        String sql = "SELECT " + selectClause + " FROM ai_dispatch_summary"
                   + where + groupClause + orderClause + " LIMIT " + safeLimit;

        log.info("[Dispatch] SQL: {} | Params: {}", sql, params);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i).toString());

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            ArrayNode rows = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= colCount; i++) {
                    String colName    = meta.getColumnLabel(i);
                    String displayName = colToLabel.getOrDefault(colName, colName);
                    String val        = rs.getString(i);
                    // Skip empty/null/placeholder spec values — don't clutter output
                    if (val == null || val.isBlank() || val.equals("--")) continue;
                    if (rs.getObject(i) == null) continue;
                    String type = meta.getColumnTypeName(i);
                    if (type.contains("DECIMAL") || type.contains("DOUBLE"))
                        row.put(displayName, String.format("%.2f", rs.getDouble(i)));
                    else if (type.contains("INT") || type.contains("BIGINT"))
                        row.put(displayName, rs.getLong(i));
                    else row.put(displayName, val);
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return "No dispatch records found matching your criteria.";

            ObjectNode result = mapper.createObjectNode();
            result.put("table", "ai_dispatch_summary");
            result.put("row_count", rows.size());
            if (!colToLabel.isEmpty()) result.set("spec_map", mapper.valueToTree(colToLabel));
            result.set("data", rows);
            return result.toString();

        } catch (SQLException e) {
            log.error("[Dispatch] Query error: {}", e.getMessage());
            return "Database error: " + e.getMessage();
        }
    }

    // ─── 4. SEARCH BY SPEC VALUE — find dispatch records by any spec value ────
    // e.g. "find chassis number X in dispatched vehicles"
    public String searchDispatchBySpecValue(String companyId, String searchValue) {
        Map<String, String> colToLabel = new LinkedHashMap<>();
        List<String> mappedCols = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT spec_column, spec_label FROM ai_stock_spec_map WHERE company_id = ? ORDER BY spec_column")) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String col   = rs.getString("spec_column");
                String label = rs.getString("spec_label");
                colToLabel.put(col, label);
                mappedCols.add(col);
            }
        } catch (SQLException e) {
            return "DB error: " + e.getMessage();
        }

        if (mappedCols.isEmpty()) return "No dispatch spec map configured for this company.";

        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder orClauses = new StringBuilder();
        for (String col : mappedCols) {
            if (orClauses.length() > 0) orClauses.append(" OR ");
            orClauses.append("LOWER(").append(col).append(") LIKE LOWER(?)");
            params.add("%" + searchValue + "%");
        }

        String specColList = String.join(", ", mappedCols);
        String sql = "SELECT item_name, party_name, invoice_no, invoice_date, quantity, " + specColList
                   + " FROM ai_dispatch_summary WHERE company_id = ? AND (" + orClauses + ") LIMIT 200";

        log.info("[Dispatch] Search '{}' across cols: {}", searchValue, mappedCols);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i).toString());
            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            ArrayNode rows = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    String display = colToLabel.getOrDefault(colName, colName);
                    String val     = rs.getString(i);
                    if (val == null || val.isBlank() || val.equals("--")) continue;
                    row.put(display, val);
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return "No dispatch records found containing '" + searchValue + "'.";

            ObjectNode result = mapper.createObjectNode();
            result.put("search_value", searchValue);
            result.put("row_count", rows.size());
            result.set("data", rows);
            return result.toString();

        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }
}