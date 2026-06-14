package com.iamerp.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);
    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    // Strict whitelist — same as ErpFunctions
    private static final Map<String, Set<String>> ALLOWED_COLUMNS = Map.of(
        "ai_daily_summary", Set.of(
            "company_id", "summary_date",
            "total_sales", "cash_sales", "credit_sales", "total_bills_count",
            "total_collection", "cash_collection", "bank_collection",
            "total_payment", "cash_payment", "bank_payment",
            "total_receivable", "total_payable", "total_discount_given",
            "total_purchase", "cash_purchase", "credit_purchase"
        ),
        "ai_sales", Set.of(
            "company_id", "bill_no", "bill_date", "voucher_date", "party_name",
            "gross_amount", "discount_amount", "sgst_amount", "cgst_amount",
            "igst_amount", "invoice_amount", "taxable_amount", "payment_mode", "created_by"
        ),
        "ai_party_status", Set.of(
            "company_id", "party_name", "party_type", "sub_party_type",
            "contact_number", "email", "address", "outstanding_balance", "balance_type",
            "last_bill_date", "last_bill_amount", "total_business_ytd",
            "credit_limit", "overdue_days", "gstin"
        ),
        "ai_collection_summary", Set.of(
            "company_id", "receipt_date", "party_name", "receipt_mode", "cheque_txn_no", "amount"
        ),
        "ai_payment_summary", Set.of(
            "company_id", "payment_date", "party_name", "payment_mode", "cheque_txn_no", "amount"
        ),
        "ai_bank_balance_summary", Set.of(
            "company_id", "bank_name", "balance_type", "balance"
        ),
        "ai_purchase", Set.of(
            "company_id", "bill_no", "bill_date", "voucher_date", "party_name",
            "gross_amount", "discount_amount", "sgst_amount", "cgst_amount",
            "igst_amount", "invoice_amount", "taxable_amount", "payment_mode"
        )
    );

    // Categorical columns that need exact = match (not LIKE)
    private static final Set<String> EXACT_MATCH_COLUMNS = Set.of(
        "party_type", "sub_party_type", "balance_type",
        "payment_mode", "receipt_mode",
        "bill_no", "summary_date", "bill_date", "receipt_date", "payment_date"
    );

    public ExportController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Direct DB export endpoint — bypasses Gemini entirely.
     * GET /api/export?table=ai_party_status&company_id=HOTAGE+INDIA&filter_col=party_type&filter_val=Supplier
     *
     * filter_col / filter_val: optional single filter
     * If filter_col is in EXACT_MATCH_COLUMNS → uses = instead of LIKE
     * order_by / sort_dir: optional sorting
     * limit: default 5000, max 10000
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportData(
            @RequestParam String table,
            @RequestParam(defaultValue = "HOTAGE INDIA") String company_id,
            @RequestParam(required = false) String filter_col,
            @RequestParam(required = false) String filter_val,
            @RequestParam(required = false) String order_by,
            @RequestParam(defaultValue = "DESC") String sort_dir,
            @RequestParam(defaultValue = "5000") int limit
    ) {
        Map<String, Object> response = new HashMap<>();

        log.info("[Export] table={} company_id='{}' filter_col={} filter_val={} order_by={} limit={}",
                 table, company_id, filter_col, filter_val, order_by, limit);

        // Validate table
        if (!ALLOWED_COLUMNS.containsKey(table)) {
            response.put("success", false);
            response.put("error", "Unknown table: " + table);
            return ResponseEntity.badRequest().body(response);
        }

        // Validate optional filter column
        String safeFilterCol = null;
        if (filter_col != null && !filter_col.isBlank()) {
            safeFilterCol = filter_col.trim().toLowerCase();
            if (!ALLOWED_COLUMNS.get(table).contains(safeFilterCol)) {
                response.put("success", false);
                response.put("error", "Unknown column: " + filter_col);
                return ResponseEntity.badRequest().body(response);
            }
        }

        // Validate order_by
        String orderClause = "";
        if (order_by != null && !order_by.isBlank()) {
            String safeOrderBy = order_by.trim().toLowerCase();
            if (ALLOWED_COLUMNS.get(table).contains(safeOrderBy)) {
                String safeDir = "DESC".equalsIgnoreCase(sort_dir) ? "DESC" : "ASC";
                orderClause = " ORDER BY " + safeOrderBy + " " + safeDir;
            }
        }

        int safeLimit = Math.min(Math.max(limit, 1), 10000);

        try (Connection conn = dataSource.getConnection()) {

            // Build query
            StringBuilder sql = new StringBuilder("SELECT * FROM ")
                .append(table).append(" WHERE company_id = ?");

            List<Object> params = new ArrayList<>();
            params.add(company_id);

            if (safeFilterCol != null && filter_val != null && !filter_val.isBlank()) {
                if (filter_val.matches("\\d{4}-\\d{2}-\\d{2},\\d{4}-\\d{2}-\\d{2}")) {
                    // Date range: "yyyy-MM-dd,yyyy-MM-dd"
                    String[] parts = filter_val.split(",", 2);
                    sql.append(" AND ").append(safeFilterCol).append(" BETWEEN ? AND ?");
                    params.add(parts[0].trim());
                    params.add(parts[1].trim());
                } else if (EXACT_MATCH_COLUMNS.contains(safeFilterCol)) {
                    // Exact match for categorical columns
                    sql.append(" AND ").append(safeFilterCol).append(" = ?");
                    params.add(filter_val.trim());
                } else {
                    // LIKE for name-based columns
                    sql.append(" AND LOWER(").append(safeFilterCol).append(") LIKE LOWER(?)");
                    params.add("%" + filter_val.trim() + "%");
                }
            }

            sql.append(orderClause).append(" LIMIT ").append(safeLimit);

            log.info("[Export] Final SQL: {} | Params: {}", sql, params);

            PreparedStatement ps = conn.prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i).toString());

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) columns.add(meta.getColumnLabel(i));

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

            log.info("[Export] Returned {} rows for company_id='{}'", rows.size(), company_id);

            response.put("success", true);
            response.put("table", table);
            response.put("total_rows", rows.size());
            response.put("columns", columns);
            response.put("data", mapper.convertValue(rows, List.class));
            return ResponseEntity.ok(response);

        } catch (SQLException e) {
            log.error("[Export] DB error: {}", e.getMessage());
            response.put("success", false);
            response.put("error", "Database error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}