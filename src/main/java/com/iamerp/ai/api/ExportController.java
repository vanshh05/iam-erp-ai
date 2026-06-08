package com.iamerp.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ExportController {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    // Strict whitelist — same as ErpFunctions
    private static final Map<String, Set<String>> ALLOWED_COLUMNS = Map.of(
        "ai_daily_summary", Set.of(
            "company_id","summary_date","total_sales","total_bills_count",
            "total_collection","total_payment","total_receivable","total_payable",
            "cash_sales","credit_sales","total_discount_given","total_purchase"
        ),
        "ai_sales", Set.of(
            "company_id","bill_no","bill_date","voucher_date","party_name",
            "gross_amount","discount_amount","sgst_amount","cgst_amount",
            "igst_amount","invoice_amount","taxable_amount","payment_mode","created_by"
        ),
        "ai_party_status", Set.of(
            "company_id","party_name","party_type","contact_number","email",
            "address","outstanding_balance","balance_type","last_bill_date",
            "last_bill_amount","total_business_ytd","credit_limit","overdue_days","gstin"
        )
    );

    public ExportController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Direct DB export endpoint — bypasses Gemini entirely.
     * Called by frontend for large data downloads.
     *
     * GET /api/export?table=ai_party_status&company_id=HOTAGE+INDIA&filter_col=party_type&filter_val=Supplier
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportData(
            @RequestParam String table,
            @RequestParam(defaultValue = "HOTAGE INDIA") String company_id,
            @RequestParam(required = false) String filter_col,
            @RequestParam(required = false) String filter_val,
            @RequestParam(required = false) String order_by,
            @RequestParam(defaultValue = "ASC") String sort_dir,
            @RequestParam(defaultValue = "5000") int limit
    ) {
        Map<String, Object> response = new HashMap<>();

        if (!ALLOWED_COLUMNS.containsKey(table)) {
            response.put("success", false);
            response.put("error", "Unknown table: " + table);
            return ResponseEntity.badRequest().body(response);
        }

        // Validate optional filter column
        if (filter_col != null && !filter_col.isBlank()) {
            if (!ALLOWED_COLUMNS.get(table).contains(filter_col.toLowerCase())) {
                response.put("success", false);
                response.put("error", "Unknown column: " + filter_col);
                return ResponseEntity.badRequest().body(response);
            }
        }

        // Validate order_by
        String orderClause = "";
        if (order_by != null && !order_by.isBlank()) {
            if (ALLOWED_COLUMNS.get(table).contains(order_by.toLowerCase())) {
                String safeDir = "DESC".equalsIgnoreCase(sort_dir) ? "DESC" : "ASC";
                orderClause = " ORDER BY " + order_by.toLowerCase() + " " + safeDir;
            }
        }

        int safeLimit = Math.min(Math.max(limit, 1), 10000);

        try (Connection conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(table).append(" WHERE company_id = ?");

            List<Object> params = new ArrayList<>();
            params.add(company_id);

            if (filter_col != null && !filter_col.isBlank() && filter_val != null) {
                sql.append(" AND LOWER(").append(filter_col.toLowerCase()).append(") LIKE LOWER(?)");
                params.add("%" + filter_val + "%");
            }

            sql.append(orderClause).append(" LIMIT ").append(safeLimit);

            PreparedStatement ps = conn.prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i).toString());

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            // Extract column names for header
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));

            // Extract all rows
            ArrayNode rows = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= colCount; i++) {
                    String col  = meta.getColumnLabel(i);
                    String type = meta.getColumnTypeName(i);
                    if (rs.getObject(i) == null) {
                        row.putNull(col);
                    } else if (type.contains("DECIMAL") || type.contains("DOUBLE") || type.contains("FLOAT")) {
                        row.put(col, String.format("%.2f", rs.getDouble(i)));
                    } else if (type.contains("INT")) {
                        row.put(col, rs.getLong(i));
                    } else {
                        row.put(col, rs.getString(i));
                    }
                }
                rows.add(row);
            }

            response.put("success", true);
            response.put("table", table);
            response.put("total_rows", rows.size());
            response.put("columns", columns);
            response.put("data", mapper.convertValue(rows, List.class));
            return ResponseEntity.ok(response);

        } catch (SQLException e) {
            response.put("success", false);
            response.put("error", "Database error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}