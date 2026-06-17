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
 * Handles all stock-related queries against ai_stock_summary and ai_stock_spec_map.
 *
 * Key design:
 *  1. Always fetch the spec map for the company first (cached per request)
 *  2. Translate human labels ("Color") → actual column names ("spec_value_1")
 *  3. Build safe parameterized SQL — never raw string interpolation
 *  4. Return labeled results (replace spec_value_N headers with human labels)
 */
@Service
public class StockFunctions {

    private static final Logger log = LoggerFactory.getLogger(StockFunctions.class);
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    // Fixed columns always present in ai_stock_summary
    private static final Set<String> FIXED_COLS = Set.of(
        "company_id", "item_name", "quantity"
    );

    // All allowed spec columns
    private static final List<String> SPEC_COLS = List.of(
        "spec_value_1","spec_value_2","spec_value_3","spec_value_4","spec_value_5",
        "spec_value_6","spec_value_7","spec_value_8","spec_value_9","spec_value_10",
        "spec_value_11","spec_value_12","spec_value_13","spec_value_14","spec_value_15",
        "spec_value_16","spec_value_17","spec_value_18","spec_value_19","spec_value_20"
    );

    public StockFunctions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── 1. GET SPEC MAP — returns label→column mapping for a company ─────────
    public String getSpecMap(String companyId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT spec_column, spec_label FROM ai_stock_spec_map WHERE company_id = ? ORDER BY spec_column")) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();

            ObjectNode result = mapper.createObjectNode();
            ObjectNode labelToCol = mapper.createObjectNode(); // Color → spec_value_1
            ObjectNode colToLabel = mapper.createObjectNode(); // spec_value_1 → Color
            boolean found = false;
            while (rs.next()) {
                String col   = rs.getString("spec_column");
                String label = rs.getString("spec_label");
                labelToCol.put(label.toLowerCase(), col);
                labelToCol.put(label, col);           // also exact case
                colToLabel.put(col, label);
                found = true;
            }
            if (!found) {
                return "{\"error\":\"No spec map found for company. Please set up ai_stock_spec_map first.\"}";
            }
            result.set("label_to_column", labelToCol);
            result.set("column_to_label", colToLabel);
            result.put("company_id", companyId);
            log.info("[Stock] Spec map for {}: {}", companyId, colToLabel);
            return result.toString();
        } catch (SQLException e) {
            return "{\"error\":\"DB error fetching spec map: " + e.getMessage() + "\"}";
        }
    }

    // ─── 2. QUERY STOCK — main workhorse ─────────────────────────────────────
    // filters: list of {label_or_column, operator, value}
    //   e.g. [{label:"Color", op:"=", value:"RED"}, {label:"item_name", op:"LIKE", value:"Badshah"}]
    // groupByLabel: e.g. "Color" — groups by that spec column
    // orderByLabel: e.g. "quantity"
    // Returns results with spec_value_N replaced by human labels in output

    public String queryStock(
            String companyId,
            List<Map<String, String>> filters,
            String groupByLabel,
            String orderByLabel,
            String sortDir,
            int limit
    ) {
        // Step 1: fetch spec map
        Map<String, String> labelToCol = new LinkedHashMap<>(); // "color" → "spec_value_1"
        Map<String, String> colToLabel = new LinkedHashMap<>(); // "spec_value_1" → "Color"
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
            return "Database error loading spec map: " + e.getMessage();
        }

        if (labelToCol.isEmpty()) {
            return "ERROR: No spec map configured for this company. Cannot determine which columns represent which specifications.";
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

                // Resolve: is this a fixed column or a spec label?
                String col;
                if (FIXED_COLS.contains(rawLabel.toLowerCase())) {
                    col = rawLabel.toLowerCase();
                } else if (SPEC_COLS.contains(rawLabel.toLowerCase())) {
                    col = rawLabel.toLowerCase(); // direct spec_value_N reference
                } else {
                    // Try label lookup
                    col = labelToCol.get(rawLabel.toLowerCase());
                    if (col == null) col = labelToCol.get(rawLabel);
                    if (col == null) {
                        return "ERROR: Unknown specification '" + rawLabel + "'. Available specs: " + labelToCol.keySet();
                    }
                }

                switch (op.toUpperCase()) {
                    case "=" -> { where.append(" AND LOWER(").append(col).append(") = LOWER(?)"); params.add(val); }
                    case "!=" -> { where.append(" AND LOWER(").append(col).append(") != LOWER(?)"); params.add(val); }
                    case "LIKE" -> { where.append(" AND LOWER(").append(col).append(") LIKE LOWER(?)"); params.add("%" + val + "%"); }
                    case ">","<",">=","<=" -> { where.append(" AND ").append(col).append(" ").append(op).append(" ?"); params.add(val); }
                    case "IN" -> {
                        String[] vals = val.split(",");
                        String ph = String.join(",", Collections.nCopies(vals.length, "?"));
                        where.append(" AND LOWER(").append(col).append(") IN (").append(ph).append(")");
                        for (String v : vals) params.add(v.trim().toLowerCase());
                    }
                    default -> log.warn("[Stock] Unknown operator: {}", op);
                }
            }
        }

        // Step 3: GROUP BY — resolve once, reused below for SELECT and ORDER BY
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

        // Step 4: ORDER BY
        // When grouping, SELECT only exposes [groupCol, total_quantity, item_count] — ordering by
        // the raw "quantity" column here would violate sql_mode=only_full_group_by, so map
        // quantity/count requests to the aggregate aliases instead of the raw column.
        String orderClause = "";
        String safeSort = "DESC".equalsIgnoreCase(sortDir) ? "DESC" : "ASC";
        if (orderByLabel != null && !orderByLabel.isBlank()) {
            String ob = orderByLabel.trim().toLowerCase();
            String orderCol;
            if (groupCol != null) {
                if (ob.equals("quantity") || ob.startsWith("sum(")) {
                    orderCol = "total_quantity";
                } else if (ob.equals("item_count") || ob.startsWith("count(")) {
                    orderCol = "item_count";
                } else if (ob.equals(groupByLabel.toLowerCase()) || ob.equals(groupCol)) {
                    orderCol = groupCol;
                } else {
                    orderCol = "total_quantity"; // sensible default for a grouped summary
                }
            } else if (FIXED_COLS.contains(ob)) {
                orderCol = ob;
            } else if (ob.startsWith("sum(") || ob.startsWith("count(")) {
                orderCol = ob;
            } else {
                orderCol = labelToCol.getOrDefault(ob, labelToCol.getOrDefault(orderByLabel, null));
            }
            if (orderCol != null) orderClause = " ORDER BY " + orderCol + " " + safeSort;
        }

        int safeLimit = Math.min(Math.max(limit <= 0 ? 200 : limit, 1), 2000);

        // Step 5: SELECT — full row detail (item_name, quantity, every mapped spec column)
        // when not grouping; just the group column plus aggregates when grouping.
        String selectClause;
        if (groupCol != null) {
            selectClause = groupCol + ", SUM(quantity) as total_quantity, COUNT(*) as item_count";
        } else {
            List<String> selectCols = new ArrayList<>();
            selectCols.add("item_name");
            selectCols.add("quantity");
            for (String specCol : SPEC_COLS) {
                if (colToLabel.containsKey(specCol)) selectCols.add(specCol);
            }
            selectClause = String.join(", ", selectCols);
        }

        String sql = "SELECT " + selectClause + " FROM ai_stock_summary"
                   + where + groupClause + orderClause + " LIMIT " + safeLimit;

        log.info("[Stock] SQL: {} | Params: {}", sql, params);

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
                    // Replace spec_value_N with human label in output
                    String displayName = colToLabel.getOrDefault(colName, colName);
                    if (rs.getObject(i) == null) row.putNull(displayName);
                    else {
                        String type = meta.getColumnTypeName(i);
                        if (type.contains("DECIMAL") || type.contains("DOUBLE"))
                            row.put(displayName, String.format("%.2f", rs.getDouble(i)));
                        else if (type.contains("INT") || type.contains("BIGINT"))
                            row.put(displayName, rs.getLong(i));
                        else row.put(displayName, rs.getString(i));
                    }
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return "No stock found matching your criteria.";

            ObjectNode result = mapper.createObjectNode();
            result.put("table", "ai_stock_summary");
            result.put("row_count", rows.size());
            result.set("spec_map", mapper.valueToTree(colToLabel));
            result.set("data", rows);
            return result.toString();

        } catch (SQLException e) {
            log.error("[Stock] Query error: {}", e.getMessage());
            return "Database error: " + e.getMessage();
        }
    }

    // ─── 3. STOCK SUMMARY — count by item and spec ───────────────────────────
    // e.g. "how many red EV.Badshah Smart in stock"
    public String getStockSummary(String companyId, String itemFilter) {
        List<Map<String, String>> filters = new ArrayList<>();
        if (itemFilter != null && !itemFilter.isBlank()) {
            filters.add(Map.of("label", "item_name", "operator", "LIKE", "value", itemFilter));
        }
        return queryStock(companyId, filters, null, "quantity", "DESC", 200);
    }

    // ─── 4. SEARCH SPEC VALUE — find items by any spec value ─────────────────
    // e.g. "find all RED items" — searches across ALL spec columns for this company
    public String searchBySpecValue(String companyId, String searchValue) {
        // Get spec map first
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

        if (mappedCols.isEmpty()) return "No spec map configured for this company.";

        // Build WHERE searching only MAPPED spec columns (not all 20)
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder orClauses = new StringBuilder();
        for (String col : mappedCols) {
            if (orClauses.length() > 0) orClauses.append(" OR ");
            orClauses.append("LOWER(").append(col).append(") LIKE LOWER(?)");
            params.add("%" + searchValue + "%");
        }

        String sql = "SELECT item_name, quantity, " + String.join(", ", mappedCols)
                   + " FROM ai_stock_summary WHERE company_id = ? AND (" + orClauses + ") LIMIT 200";

        log.info("[Stock] Search '{}' across cols: {}", searchValue, mappedCols);

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
                    if (rs.getObject(i) == null) row.putNull(display);
                    else row.put(display, rs.getString(i));
                }
                rows.add(row);
            }

            if (rows.isEmpty()) return "No stock items found containing '" + searchValue + "'.";

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