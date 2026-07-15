package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

@Service
public class ErpFunctions {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ErpFunctions.class);

    // Whitelist of allowed tables and their allowed columns
    // This prevents SQL injection AND stops Gemini from hallucinating column names
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
            "igst_amount", "invoice_amount", "taxable_amount", "payment_mode"
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

    private static final Set<String> ALLOWED_SORT_DIRECTIONS = Set.of("ASC", "DESC");

    // Columns that hold dates (possibly with a time component). Comparisons against these
    // are wrapped in DATE() so a plain yyyy-MM-dd value matches regardless of stored time.
    private static final Set<String> DATE_COLUMNS = Set.of(
        "bill_date", "voucher_date", "summary_date",
        "receipt_date", "payment_date", "last_bill_date"
    );

    public ErpFunctions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private String fmt(double amount) {
        return String.format("%.2f", amount);
    }

    private boolean isValidColumn(String table, String column) {
        Set<String> cols = ALLOWED_COLUMNS.get(table);
        return cols != null && cols.contains(column.toLowerCase());
    }

    private boolean isValidTable(String table) {
        return ALLOWED_COLUMNS.containsKey(table);
    }

    // ─── 1. UNIVERSAL QUERY — main workhorse ─────────────────────────────────
    // Gemini provides table name, filters, sort — we execute safely
    //
    // filters: list of {column, operator, value}  e.g. {bill_date, =, 2026-06-01}
    // group_by: column to group results by (optional)
    // order_by: column to sort by (optional)
    // sort_dir: ASC or DESC (default DESC)
    // limit: max rows to return (default 200, max 2000)

    public String queryTable(
            String tableName,
            List<Map<String, String>> filters,
            String selectCols,     // comma-separated column names, or "all"
            String groupBy,
            String orderBy,
            String sortDir,
            int limit,
            String companyId
    ) {
        // Validate table
        if (!isValidTable(tableName)) {
            return "ERROR: Unknown table '" + tableName + "'. Allowed: ai_daily_summary, ai_sales, ai_party_status";
        }

        // Validate and build SELECT
        String selectClause;
        if (selectCols == null || selectCols.isBlank() || "all".equalsIgnoreCase(selectCols)) {
            selectClause = "*";
        } else {
            List<String> cols = new ArrayList<>();
            for (String col : selectCols.split(",")) {
                String c = col.trim().toLowerCase();
                // The model often writes aggregates with an alias — "sum(invoice_amount) as
                // total_sales". Split a trailing alias off, validate the expression, and keep
                // the alias in the SQL (it is harmless and helps the model read results).
                String alias = "";
                java.util.regex.Matcher am = java.util.regex.Pattern
                        .compile("^(.*?)\\s+as\\s+([a-z_][a-z0-9_]*)$").matcher(c);
                if (am.matches()) {
                    c = am.group(1).trim();
                    alias = " as " + am.group(2);
                }
                // COUNT(*) is the standard row-count form and is explicitly instructed by the
                // dashboard prompts. It has no inner column to validate.
                if (c.matches("count\\(\\s*\\*\\s*\\)")) {
                    cols.add("count(*)" + alias);
                }
                // Allow aggregate functions like SUM(col), COUNT(col), COUNT(DISTINCT col)
                else if (c.matches("(sum|count|avg|min|max)\\((distinct\\s+)?[a-z_]+\\)")) {
                    // Extract inner column name — handles COUNT(DISTINCT col) and COUNT(col)
                    String inner = c.replaceAll("^(?:sum|count|avg|min|max)\\((?:distinct\\s+)?([a-z_]+)\\)$", "$1");
                    if (!isValidColumn(tableName, inner)) {
                        return "ERROR: Column '" + inner + "' not allowed in table '" + tableName + "'";
                    }
                    cols.add(c + alias);
                } else if (isValidColumn(tableName, c)) {
                    cols.add(c);
                } else {
                    return "ERROR: Column '" + c + "' not found in table '" + tableName + "'. Use get_table_schema to see available columns.";
                }
            }
            selectClause = String.join(", ", cols);
        }

        // Validate limit
        int safeLimit = Math.min(Math.max(limit <= 0 ? 2000 : limit, 1), 2000);

        // Validate sort
        String safeSortDir = ALLOWED_SORT_DIRECTIONS.contains(
                sortDir != null ? sortDir.toUpperCase() : "") ? sortDir.toUpperCase() : "DESC";
        String orderClause = "";
        if (orderBy != null && !orderBy.isBlank()) {
            // Allow aggregate functions in order by too
            String ob = orderBy.trim().toLowerCase();
            boolean validOrderBy = isValidColumn(tableName, ob)
                || ob.matches("count\\(\\s*\\*\\s*\\)")
                || ob.matches("(sum|count|avg|min|max)\\((distinct\\s+)?[a-z_]+\\)");
            if (!validOrderBy) {
                return "ERROR: Cannot sort by '" + orderBy + "' — column not found in '" + tableName + "'";
            }
            orderClause = " ORDER BY " + ob + " " + safeSortDir;
        }

        // Validate group_by
        String groupClause = "";
        if (groupBy != null && !groupBy.isBlank()) {
            String gb = groupBy.trim().toLowerCase();
            if (!isValidColumn(tableName, gb)) {
                return "ERROR: Cannot group by '" + groupBy + "' — column not in table";
            }
            groupClause = " GROUP BY " + gb;
        }

        // Build WHERE clause from filters
        // Always enforce company_id first
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        params.add(companyId);

        if (filters != null) {
            for (Map<String, String> f : filters) {
                String col = f.getOrDefault("column", "").trim().toLowerCase();
                String op  = f.getOrDefault("operator", "").trim();
                String val = f.getOrDefault("value", "").trim();

                if (col.isBlank() || op.isBlank() || val.isBlank()) continue;
                if (!isValidColumn(tableName, col)) {
                    return "ERROR: Filter column '" + col + "' not found in '" + tableName + "'";
                }

                // Date columns may be stored as DATETIME/TIMESTAMP. When comparing against a
                // plain yyyy-MM-dd value, wrap the column in DATE() so the time component is
                // ignored — otherwise bill_date = '2026-06-25' misses rows stored at 14:30:00.
                boolean isDateCol = DATE_COLUMNS.contains(col);
                String colExpr = isDateCol ? "DATE(" + col + ")" : col;

                switch (op) {
                    case "=", "!=", ">", "<", ">=", "<=" -> {
                        where.append(" AND ").append(colExpr).append(" ").append(op).append(" ?");
                        params.add(val);
                    }
                    case "LIKE", "like" -> {
                        where.append(" AND LOWER(").append(col).append(") LIKE LOWER(?)");
                        params.add("%" + val + "%");
                    }
                    case "BETWEEN", "between" -> {
                        // val should be "start,end"
                        String[] parts = val.split(",", 2);
                        if (parts.length != 2) {
                            return "ERROR: BETWEEN filter on '" + col + "' needs two comma-separated values"
                                 + " (got '" + val + "').";
                        }
                        where.append(" AND ").append(colExpr).append(" BETWEEN ? AND ?");
                        params.add(parts[0].trim());
                        params.add(parts[1].trim());
                    }
                    case "IN", "in" -> {
                        // val should be comma-separated values
                        String[] inVals = val.split(",");
                        String placeholders = String.join(",", Collections.nCopies(inVals.length, "?"));
                        where.append(" AND ").append(colExpr).append(" IN (").append(placeholders).append(")");
                        for (String iv : inVals) params.add(iv.trim());
                    }
                    // An unrecognised operator must NEVER be silently ignored: dropping the filter
                    // would run the query unfiltered and report, say, full-year totals as if they
                    // were this month's. Fail loudly instead.
                    default -> {
                        return "ERROR: Unsupported filter operator '" + op + "' on column '" + col
                             + "'. Allowed: =, !=, >, <, >=, <=, LIKE, BETWEEN, IN.";
                    }
                }
            }
        }

        String sql = "SELECT " + selectClause + " FROM " + tableName
                   + where + groupClause + orderClause + " LIMIT " + safeLimit;

        log.info("[ErpFunctions] SQL: {} | Params: {}", sql, params);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i).toString());
            }

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            ArrayNode rows = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    String colType = meta.getColumnTypeName(i);
                    if (rs.getObject(i) == null) {
                        row.putNull(colName);
                    } else if (colType.contains("DECIMAL") || colType.contains("DOUBLE") || colType.contains("FLOAT")) {
                        row.put(colName, fmt(rs.getDouble(i)));
                    } else if (colType.contains("INT")) {
                        row.put(colName, rs.getLong(i));
                    } else {
                        row.put(colName, rs.getString(i));
                    }
                }
                rows.add(row);
            }

            if (rows.isEmpty()) {
                String noun = switch (tableName) {
                    case "ai_sales"               -> "sales records";
                    case "ai_purchase"            -> "purchase records";
                    case "ai_collection_summary"  -> "collection records";
                    case "ai_payment_summary"     -> "payment records";
                    case "ai_party_status"        -> "parties";
                    case "ai_bank_balance_summary"-> "bank balance records";
                    case "ai_daily_summary"       -> "daily summary records";
                    default                       -> "records";
                };
                return "No " + noun + " found matching the requested criteria. The module is active, "
                     + "but there is no data for what was asked (e.g. that date, party, or filter).";
            }

            ObjectNode result = mapper.createObjectNode();
            result.put("table", tableName);
            result.put("row_count", rows.size());
            result.set("data", rows);
            return result.toString();

        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }


    // ─── PARTY GENERAL LEDGER ─────────────────────────────────────────────────
    // Returns the full general ledger for ONE named party, in statement order:
    // seq_no drives the ordering (1 = Opening Balance ... last = totals row) but
    // is intentionally NOT included in the output — the report must not show it.
    // A party name is REQUIRED: this ledger is never listed for all parties.
    public String getPartyLedger(String partyName, String companyId) {
        if (partyName == null || partyName.trim().isEmpty()) {
            return "ERROR: A specific party name is required to fetch a general ledger. "
                 + "Ask the user which party's ledger they want.";
        }

        String sql = "SELECT party_name, particulars, voucher_date, voucher_type, voucher_no, "
                   + "invoice_no, debit, credit, balance "
                   + "FROM ai_party_ledger "
                   + "WHERE company_id = ? AND LOWER(party_name) LIKE LOWER(?) "
                   + "ORDER BY party_name, seq_no ASC LIMIT 2000";

        log.info("[ErpFunctions] SQL: {} | Params: [{}, %{}%]", sql, companyId, partyName.trim());

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, companyId);
            ps.setString(2, "%" + partyName.trim() + "%");

            ResultSet rs = ps.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            ArrayNode rows = mapper.createArrayNode();
            java.util.Set<String> partiesSeen = new java.util.LinkedHashSet<>();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= colCount; i++) {
                    String colName = meta.getColumnLabel(i);
                    String colType = meta.getColumnTypeName(i);
                    if (rs.getObject(i) == null) {
                        row.putNull(colName);
                    } else if (colType.contains("DECIMAL") || colType.contains("DOUBLE") || colType.contains("FLOAT")) {
                        row.put(colName, fmt(rs.getDouble(i)));
                    } else if (colType.contains("INT")) {
                        row.put(colName, rs.getLong(i));
                    } else {
                        row.put(colName, rs.getString(i));
                    }
                }
                partiesSeen.add(rs.getString("party_name"));
                rows.add(row);
            }

            if (rows.isEmpty()) {
                // Nothing matched the LIKE — the name is probably misspelled. Find the
                // closest real party names so the user can pick instead of hitting a dead end.
                List<String> close = closestLedgerParties(conn, companyId, partyName.trim());
                if (!close.isEmpty()) {
                    ObjectNode noMatch = mapper.createObjectNode();
                    noMatch.put("table", "ai_party_ledger");
                    noMatch.put("no_match", true);
                    noMatch.put("searched", partyName.trim());
                    ArrayNode arr = mapper.createArrayNode();
                    close.forEach(arr::add);
                    noMatch.set("closest_matches", arr);
                    return noMatch.toString();
                }
                return "No ledger entries found for a party matching '" + partyName.trim()
                     + "', and no similarly-named party exists in the ledger. "
                     + "Ask the user to check the party name.";
            }

            // The LIKE match may catch several parties (e.g. two branches with similar
            // names). Report them so the model can ask the user to disambiguate.
            ObjectNode result = mapper.createObjectNode();
            result.put("table", "ai_party_ledger");
            result.put("row_count", rows.size());
            result.put("matched_parties", String.join(" | ", partiesSeen));
            result.put("distinct_party_count", partiesSeen.size());
            result.set("data", rows);
            return result.toString();

        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }

    // Fuzzy party-name suggestions: compares the searched name against every distinct
    // party in the ledger and returns the closest few. Token-based: each word of the
    // search is scored against each word of the candidate (substring hit = perfect,
    // otherwise Levenshtein similarity), so 'sidhi vinayak' finds 'Shree Siddhi
    // Vinayak Educational & Welfare Trust A/c (02303)'.
    private List<String> closestLedgerParties(Connection conn, String companyId, String searched)
            throws SQLException {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT party_name FROM ai_party_ledger WHERE company_id = ? LIMIT 3000")) {
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) names.add(rs.getString(1));
        }

        String[] qTokens = searched.toLowerCase().split("[^a-z0-9]+");
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (String name : names) {
            String[] nTokens = name.toLowerCase().split("[^a-z0-9]+");
            double total = 0; int counted = 0;
            for (String qt : qTokens) {
                if (qt.isBlank() || qt.length() < 2) continue;
                double best = 0;
                for (String nt : nTokens) {
                    // Skip tiny candidate tokens ('s', 'o', 'a', 'c' from "S/o"/"A/c"):
                    // single letters are substrings of almost every word and previously
                    // gave every "X S/o Y A/c" party a near-perfect score.
                    if (nt.isBlank() || nt.length() < 2) continue;
                    double sim;
                    if (nt.equals(qt)) {
                        sim = 1.0;
                    } else if ((nt.contains(qt) || qt.contains(nt))
                               && Math.min(nt.length(), qt.length()) >= 3) {
                        // containment only counts when the shorter token is a real word
                        sim = 1.0;
                    } else {
                        int d = levenshtein(qt, nt);
                        sim = 1.0 - ((double) d / Math.max(qt.length(), nt.length()));
                    }
                    if (sim > best) best = sim;
                }
                total += best; counted++;
            }
            if (counted == 0) continue;
            double score = total / counted;
            if (score >= 0.65) scored.add(Map.entry(name, score));
        }
        scored.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(5, scored.size()); i++) out.add(scored.get(i).getKey());
        return out;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = curr; curr = t;
        }
        return prev[b.length()];
    }

    // ─── 2. GET TABLE SCHEMA — lets Gemini know exact column names ───────────
    public String getTableSchema(String tableName) {
        if (tableName == null || tableName.isBlank() || "all".equalsIgnoreCase(tableName)) {
            ObjectNode all = mapper.createObjectNode();
            for (Map.Entry<String, Set<String>> entry : ALLOWED_COLUMNS.entrySet()) {
                ArrayNode cols = mapper.createArrayNode();
                new TreeSet<>(entry.getValue()).forEach(cols::add);
                all.set(entry.getKey(), cols);
            }
            return all.toString();
        }
        if (!isValidTable(tableName)) {
            return "ERROR: Unknown table '" + tableName + "'";
        }
        ArrayNode cols = mapper.createArrayNode();
        new TreeSet<>(ALLOWED_COLUMNS.get(tableName)).forEach(cols::add);
        ObjectNode result = mapper.createObjectNode();
        result.set(tableName, cols);
        return result.toString();
    }

    // ─── 3. PARTY SEARCH — convenience wrapper around queryTable ─────────────
    public String searchParty(String partyName, String companyId) {
        List<Map<String, String>> filters = new ArrayList<>();
        if (partyName != null && !partyName.isBlank()) {
            filters.add(Map.of("column", "party_name", "operator", "LIKE", "value", partyName));
        }
        return queryTable("ai_party_status", filters, "all", null, "outstanding_balance", "DESC", 20, companyId);
    }

    // ─── 4. OVERDUE PARTIES — convenience wrapper ────────────────────────────
    public String getOverdueParties(int minOverdueDays, String companyId) {
        List<Map<String, String>> filters = List.of(
            Map.of("column", "overdue_days",  "operator", ">=", "value", String.valueOf(minOverdueDays)),
            Map.of("column", "balance_type",  "operator", "=",  "value", "Debit")
        );
        return queryTable("ai_party_status", filters,
            "party_name,outstanding_balance,balance_type,overdue_days,last_bill_date,contact_number",
            null, "overdue_days", "DESC", 30, companyId);
    }

    // ─── 5. TODAY SUMMARY — convenience wrapper ──────────────────────────────
    public String getTodaySummary(String companyId) {
        List<Map<String, String>> filters = List.of(
            Map.of("column", "summary_date", "operator", "=", "value", LocalDate.now().toString())
        );
        return queryTable("ai_daily_summary", filters, "all", null, null, "DESC", 1, companyId);
    }
}