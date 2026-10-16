package com.iamerp.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iamerp.ai.config.CompanyContext;
import com.iamerp.ai.security.CompanyResolver;
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
    private static final Map<String, Set<String>> ALLOWED_COLUMNS = Map.ofEntries(
        Map.entry("ai_daily_summary", Set.of(
            "company_id", "summary_date",
            "total_sales", "cash_sales", "credit_sales", "total_bills_count",
            "total_collection", "cash_collection", "bank_collection",
            "total_payment", "cash_payment", "bank_payment",
            "total_receivable", "total_payable", "total_discount_given",
            "total_purchase", "cash_purchase", "credit_purchase"
        )),
        Map.entry("ai_sales", Set.of(
            "company_id", "bill_no", "bill_date", "voucher_date", "party_name",
            "gross_amount", "discount_amount", "sgst_amount", "cgst_amount",
            "igst_amount", "invoice_amount", "taxable_amount", "payment_mode", "created_by"
        )),
        Map.entry("ai_party_status", Set.of(
            "company_id", "party_name", "party_type", "sub_party_type",
            "contact_number", "email", "address", "outstanding_balance", "balance_type",
            "last_bill_date", "last_bill_amount", "total_business_ytd",
            "credit_limit", "overdue_days", "gstin"
        )),
        Map.entry("ai_collection_summary", Set.of(
            "company_id", "receipt_date", "party_name", "receipt_mode", "cheque_txn_no", "amount"
        )),
        Map.entry("ai_payment_summary", Set.of(
            "company_id", "payment_date", "party_name", "payment_mode", "cheque_txn_no", "amount"
        )),
        Map.entry("ai_bank_balance_summary", Set.of(
            "company_id", "bank_name", "balance_type", "balance"
        )),
        Map.entry("ai_purchase", Set.of(
            "company_id", "bill_no", "bill_date", "voucher_date", "party_name",
            "gross_amount", "discount_amount", "sgst_amount", "cgst_amount",
            "igst_amount", "invoice_amount", "taxable_amount", "payment_mode"
        )),
        Map.entry("ai_dispatch_summary", Set.of(
            "company_id", "item_name", "party_name", "invoice_no", "invoice_date", "quantity",
            "spec_value_1", "spec_value_2", "spec_value_3", "spec_value_4", "spec_value_5", "spec_value_6", "spec_value_7", "spec_value_8", "spec_value_9", "spec_value_10", "spec_value_11", "spec_value_12", "spec_value_13", "spec_value_14", "spec_value_15", "spec_value_16", "spec_value_17", "spec_value_18", "spec_value_19", "spec_value_20"
        )),
        Map.entry("ai_stock_summary", Set.of(
            "company_id", "item_name", "quantity",
            "spec_value_1", "spec_value_2", "spec_value_3", "spec_value_4", "spec_value_5", "spec_value_6", "spec_value_7", "spec_value_8", "spec_value_9", "spec_value_10", "spec_value_11", "spec_value_12", "spec_value_13", "spec_value_14", "spec_value_15", "spec_value_16", "spec_value_17", "spec_value_18", "spec_value_19", "spec_value_20"
        )),
        Map.entry("ai_customer_info", Set.of(
            "company_id", "customer_id", "customer_code", "customer_date",
            "company_name", "customer_name", "user_name", "source_of_info",
            "designation", "product_cateogory", "gst_number", "mobile_no",
            "email_id", "address", "city", "state", "customer_status", "customer_comments"
        )),
        Map.entry("ai_lead_info", Set.of(
            "company_id", "customer_id", "lead_id", "lead_code", "lead_date",
            "priority", "follow_up_user", "follow_up_date", "meeting_date",
            "meeting_agenda", "lead_status", "product_name",
            "is_price", "is_product", "is_other",
            "follow_up_user_remarks", "customer_comments",
            "worked_by", "worked_updated_date"
        ))
    );

    // Categorical columns that need exact = match (not LIKE)
    private static final Set<String> EXACT_MATCH_COLUMNS = Set.of(
        "party_type", "sub_party_type", "balance_type",
        "payment_mode", "receipt_mode",
        "bill_no", "summary_date", "bill_date", "receipt_date", "payment_date",
        "city", "state", "customer_status", "priority", "lead_status", "lead_code",
        "customer_date", "lead_date", "follow_up_date", "meeting_date",
        "worked_updated_date"
    );

    public ExportController(DataSource dataSource, CompanyResolver companyResolver) {
        this.dataSource = dataSource;
        this.companyResolver = companyResolver;
    }

    private final CompanyResolver companyResolver;

    /**
     * Direct DB export endpoint — bypasses Gemini entirely.
     * GET /api/export?table=ai_party_status&filter_col=party_type&filter_val=Supplier
     * company_id is taken from the authenticated user's token.
     *
     * filter_col / filter_val: optional single filter
     * If filter_col is in EXACT_MATCH_COLUMNS → uses = instead of LIKE
     * order_by / sort_dir: optional sorting
     * limit: default 5000, max 10000
     */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportData(
            @RequestParam String table,
            @RequestParam(required = false) String company_id,  // honored only for admins
            @RequestParam(required = false) String filter_col,
            @RequestParam(required = false) String filter_val,
            @RequestParam(required = false) String order_by,
            @RequestParam(defaultValue = "DESC") String sort_dir,
            @RequestParam(defaultValue = "5000") int limit,
            @RequestParam(required = false) String select_cols  // optional: comma-separated columns to SELECT
    ) {
        Map<String, Object> response = new HashMap<>();

        if (companyResolver.currentUser() == null) {
            response.put("success", false);
            response.put("error", "Unauthorized");
            return ResponseEntity.status(401).body(response);
        }

        String company = companyResolver.resolve(company_id);
        if (company == null) {
            response.put("success", false);
            response.put("error", "No company selected.");
            return ResponseEntity.badRequest().body(response);
        }

        log.info("[Export] table={} company_id='{}' filter_col={} filter_val={} order_by={} select_cols={} limit={}",
                 table, company, filter_col, filter_val, order_by, select_cols, limit);

        // Build SELECT clause — use explicit columns if provided, else SELECT *
        String selectClause = "*";
        if (select_cols != null && !select_cols.isBlank()) {
            List<String> validCols = new ArrayList<>();
            for (String col : select_cols.split(",")) {
                String c = col.trim().toLowerCase();
                if (ALLOWED_COLUMNS.get(table) != null && ALLOWED_COLUMNS.get(table).contains(c)) {
                    validCols.add(c);
                }
            }
            if (!validCols.isEmpty()) {
                selectClause = "company_id," + String.join(",", validCols); // always include company_id for reference
            }
        }

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

        CompanyContext.set(company); // route to backup schema if this company is mid-refresh
        try (Connection conn = dataSource.getConnection()) {

            // Build query
            StringBuilder sql = new StringBuilder("SELECT ").append(selectClause)
                .append(" FROM ").append(table).append(" WHERE company_id = ?");

            List<Object> params = new ArrayList<>();
            params.add(company);

            if (safeFilterCol != null && filter_val != null && !filter_val.isBlank()) {
                java.util.Set<String> dateCols = java.util.Set.of(
                    "bill_date", "voucher_date", "summary_date",
                    "receipt_date", "payment_date", "last_bill_date", "invoice_date");
                boolean isDateCol = dateCols.contains(safeFilterCol);
                String colExpr = isDateCol ? "DATE(" + safeFilterCol + ")" : safeFilterCol;

                if (filter_val.matches("\\d{4}-\\d{2}-\\d{2},\\d{4}-\\d{2}-\\d{2}")) {
                    // Date range: "yyyy-MM-dd,yyyy-MM-dd"
                    String[] parts = filter_val.split(",", 2);
                    sql.append(" AND ").append(colExpr).append(" BETWEEN ? AND ?");
                    params.add(parts[0].trim());
                    params.add(parts[1].trim());
                } else if (isDateCol) {
                    // Single date — match on the date part only (ignore time component)
                    sql.append(" AND ").append(colExpr).append(" = ?");
                    params.add(filter_val.trim());
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

            log.info("[Export] Returned {} rows for company_id='{}'", rows.size(), company);

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
        } finally {
            CompanyContext.clear();
        }
    }
}