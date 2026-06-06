package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

@Service
public class ErpFunctions {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public ErpFunctions(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── Helper: format Indian currency ──────────────────────────────────────
    private String formatAmount(double amount) {
        return String.format("%.2f", amount);
    }

    // ─── 0. Date helpers ─────────────────────────────────────────────────────

    public String getPreviousMonthSales(String companyId) {
        LocalDate today = LocalDate.now();
        String from = today.minusMonths(1).withDayOfMonth(1).toString();
        String to   = today.minusMonths(1).withDayOfMonth(
                      today.minusMonths(1).lengthOfMonth()).toString();
        return getCumulativeSales(from, to, companyId);
    }

    // ─── 1. Party Status ──────────────────────────────────────────────────────

    public String getPartyStatus(String partyName, String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT party_name, party_type, contact_number, outstanding_balance, " +
                         "balance_type, last_bill_date, last_bill_amount, total_business_ytd, " +
                         "credit_limit, overdue_days, gstin " +
                         "FROM ai_party_status " +
                         "WHERE company_id = ? AND LOWER(party_name) LIKE LOWER(?) " +
                         "ORDER BY party_name LIMIT 10";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, companyId);
            ps.setString(2, "%" + partyName.trim() + "%");
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("party_name",         rs.getString("party_name"));
                row.put("party_type",          rs.getString("party_type"));
                row.put("contact",             rs.getString("contact_number"));
                row.put("outstanding_balance", formatAmount(rs.getDouble("outstanding_balance")));
                row.put("balance_type",        rs.getString("balance_type"));
                row.put("last_bill_date",      rs.getString("last_bill_date"));
                row.put("last_bill_amount",    formatAmount(rs.getDouble("last_bill_amount")));
                row.put("total_business_ytd",  formatAmount(rs.getDouble("total_business_ytd")));
                row.put("credit_limit",        formatAmount(rs.getDouble("credit_limit")));
                row.put("overdue_days",        rs.getInt("overdue_days"));
                row.put("gstin",               rs.getString("gstin"));
                results.add(row);
            }
            if (results.isEmpty()) return "No party found matching: " + partyName;
            return results.toString();
        } catch (SQLException e) { return "Error fetching party: " + e.getMessage(); }
    }

    public String getOverdueParties(int minOverdueDays, String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT party_name, outstanding_balance, balance_type, overdue_days, " +
                         "last_bill_date, contact_number " +
                         "FROM ai_party_status " +
                         "WHERE company_id = ? AND overdue_days >= ? AND balance_type = 'DR' " +
                         "ORDER BY overdue_days DESC, outstanding_balance DESC LIMIT 20";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, companyId);
            ps.setInt(2, minOverdueDays);
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("party",        rs.getString("party_name"));
                row.put("outstanding",  formatAmount(rs.getDouble("outstanding_balance")));
                row.put("overdue_days", rs.getInt("overdue_days"));
                row.put("last_bill",    rs.getString("last_bill_date"));
                row.put("contact",      rs.getString("contact_number"));
                results.add(row);
            }
            if (results.isEmpty()) return "No overdue parties found.";
            return results.toString();
        } catch (SQLException e) { return "Error: " + e.getMessage(); }
    }

    // ─── 2. Sales ─────────────────────────────────────────────────────────────

    public String getTodaySales(String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            // Try ai_daily_summary first for today
            String sql = "SELECT summary_date, total_sales, total_bills_count, " +
                         "cash_sales, credit_sales, total_discount_given, " +
                         "total_collection, total_receivable, total_payable, total_purchase " +
                         "FROM ai_daily_summary " +
                         "WHERE company_id = ? AND summary_date = CURDATE()";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, companyId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ObjectNode result = mapper.createObjectNode();
                result.put("date",             LocalDate.now().toString());
                result.put("total_sales",      formatAmount(rs.getDouble("total_sales")));
                result.put("total_bills",      rs.getInt("total_bills_count"));
                result.put("cash_sales",       formatAmount(rs.getDouble("cash_sales")));
                result.put("credit_sales",     formatAmount(rs.getDouble("credit_sales")));
                result.put("total_collection", formatAmount(rs.getDouble("total_collection")));
                result.put("total_receivable", formatAmount(rs.getDouble("total_receivable")));
                result.put("total_payable",    formatAmount(rs.getDouble("total_payable")));
                result.put("total_purchase",   formatAmount(rs.getDouble("total_purchase")));
                result.put("discount_given",   formatAmount(rs.getDouble("total_discount_given")));
                return result.toString();
            }
            // Fallback: aggregate directly from ai_sales
            String fallback = "SELECT COUNT(*) as bill_count, " +
                              "SUM(invoice_amount) as total_sales, " +
                              "SUM(gross_amount) as gross, " +
                              "SUM(discount_amount) as discount, " +
                              "SUM(sgst_amount + cgst_amount + igst_amount) as total_tax, " +
                              "SUM(taxable_amount) as taxable " +
                              "FROM ai_sales " +
                              "WHERE company_id = ? AND bill_date = CURDATE()";
            PreparedStatement ps2 = conn.prepareStatement(fallback);
            ps2.setString(1, companyId);
            ResultSet rs2 = ps2.executeQuery();
            if (rs2.next() && rs2.getInt("bill_count") > 0) {
                ObjectNode result = mapper.createObjectNode();
                result.put("date",        LocalDate.now().toString());
                result.put("total_sales", formatAmount(rs2.getDouble("total_sales")));
                result.put("total_bills", rs2.getInt("bill_count"));
                result.put("gross",       formatAmount(rs2.getDouble("gross")));
                result.put("discount",    formatAmount(rs2.getDouble("discount")));
                result.put("total_tax",   formatAmount(rs2.getDouble("total_tax")));
                result.put("taxable",     formatAmount(rs2.getDouble("taxable")));
                return result.toString();
            }
            return "No sales data found for today (" + LocalDate.now() + ").";
        } catch (SQLException e) { return "Error fetching today sales: " + e.getMessage(); }
    }

    public String getCumulativeSales(String fromDate, String toDate, String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            LocalDate today = LocalDate.now();
            String from = (fromDate != null && !fromDate.isBlank()) ? fromDate
                          : today.withDayOfMonth(1).toString();
            String to   = (toDate   != null && !toDate.isBlank())   ? toDate
                          : today.toString();

            // Try ai_daily_summary first
            String sql = "SELECT SUM(total_sales) as total_sales, SUM(total_bills_count) as total_bills, " +
                         "SUM(cash_sales) as cash_sales, SUM(credit_sales) as credit_sales, " +
                         "SUM(total_collection) as total_collection, " +
                         "SUM(total_discount_given) as discount, SUM(total_purchase) as total_purchase, " +
                         "COUNT(*) as days_count " +
                         "FROM ai_daily_summary " +
                         "WHERE company_id = ? AND summary_date BETWEEN ? AND ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, companyId);
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getDouble("total_sales") > 0) {
                ObjectNode result = mapper.createObjectNode();
                result.put("from",             from);
                result.put("to",               to);
                result.put("total_sales",      formatAmount(rs.getDouble("total_sales")));
                result.put("total_bills",      rs.getInt("total_bills"));
                result.put("cash_sales",       formatAmount(rs.getDouble("cash_sales")));
                result.put("credit_sales",     formatAmount(rs.getDouble("credit_sales")));
                result.put("total_collection", formatAmount(rs.getDouble("total_collection")));
                result.put("total_purchase",   formatAmount(rs.getDouble("total_purchase")));
                result.put("total_discount",   formatAmount(rs.getDouble("discount")));
                result.put("days_active",      rs.getInt("days_count"));
                return result.toString();
            }

            // Fallback: aggregate from ai_sales directly
            String fallback = "SELECT COUNT(*) as bill_count, " +
                              "SUM(invoice_amount) as total_sales, " +
                              "SUM(gross_amount) as gross, " +
                              "SUM(discount_amount) as discount, " +
                              "SUM(sgst_amount + cgst_amount + igst_amount) as total_tax, " +
                              "SUM(taxable_amount) as taxable " +
                              "FROM ai_sales " +
                              "WHERE company_id = ? AND bill_date BETWEEN ? AND ?";
            PreparedStatement ps2 = conn.prepareStatement(fallback);
            ps2.setString(1, companyId);
            ps2.setDate(2, Date.valueOf(from));
            ps2.setDate(3, Date.valueOf(to));
            ResultSet rs2 = ps2.executeQuery();
            if (rs2.next() && rs2.getInt("bill_count") > 0) {
                ObjectNode result = mapper.createObjectNode();
                result.put("from",        from);
                result.put("to",          to);
                result.put("total_sales", formatAmount(rs2.getDouble("total_sales")));
                result.put("total_bills", rs2.getInt("bill_count"));
                result.put("gross",       formatAmount(rs2.getDouble("gross")));
                result.put("discount",    formatAmount(rs2.getDouble("discount")));
                result.put("total_tax",   formatAmount(rs2.getDouble("total_tax")));
                result.put("taxable",     formatAmount(rs2.getDouble("taxable")));
                return result.toString();
            }
            return "No sales data found from " + from + " to " + to + ".";
        } catch (SQLException e) { return "Error fetching sales: " + e.getMessage(); }
    }

    public String getBillsForDate(String date, String partyName, String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            String targetDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();
            StringBuilder sql = new StringBuilder(
                "SELECT bill_no, bill_date, `voucher_date`, party_name, " +
                "gross_amount, discount_amount, sgst_amount, cgst_amount, igst_amount, " +
                "invoice_amount, taxable_amount, payment_mode, created_by " +
                "FROM ai_sales WHERE company_id = ? AND bill_date = ?"
            );
            if (partyName != null && !partyName.isBlank())
                sql.append(" AND LOWER(party_name) LIKE LOWER(?)");
            sql.append(" ORDER BY bill_date DESC, bill_no DESC LIMIT 50");

            PreparedStatement ps = conn.prepareStatement(sql.toString());
            ps.setString(1, companyId);
            ps.setDate(2, Date.valueOf(targetDate));
            if (partyName != null && !partyName.isBlank())
                ps.setString(3, "%" + partyName.trim() + "%");

            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();
            double totalInvoice = 0;
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("bill_no",        rs.getString("bill_no"));
                row.put("date",           rs.getString("bill_date"));
                row.put("party",          rs.getString("party_name"));
                row.put("gross",          formatAmount(rs.getDouble("gross_amount")));
                row.put("discount",       formatAmount(rs.getDouble("discount_amount")));
                row.put("sgst",           formatAmount(rs.getDouble("sgst_amount")));
                row.put("cgst",           formatAmount(rs.getDouble("cgst_amount")));
                row.put("igst",           formatAmount(rs.getDouble("igst_amount")));
                row.put("invoice_amount", formatAmount(rs.getDouble("invoice_amount")));
                row.put("taxable",        formatAmount(rs.getDouble("taxable_amount")));
                row.put("payment_mode",   rs.getString("payment_mode"));
                row.put("created_by",     rs.getString("created_by"));
                results.add(row);
                totalInvoice += rs.getDouble("invoice_amount");
            }
            if (results.isEmpty()) return "No bills found for " + targetDate
                    + (partyName != null ? " for party: " + partyName : "");
            ObjectNode response = mapper.createObjectNode();
            response.put("date",          targetDate);
            response.put("total_invoice", formatAmount(totalInvoice));
            response.put("count",         results.size());
            response.set("bills",         results);
            return response.toString();
        } catch (SQLException e) { return "Error fetching bills: " + e.getMessage(); }
    }

    // ─── 3. Collections ───────────────────────────────────────────────────────

    public String getCollections(String partyName, String date, String companyId) {
        try (Connection conn = dataSource.getConnection()) {
            // Use ai_daily_summary for collection totals
            String targetDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();
            if (partyName == null || partyName.isBlank()) {
                String sql = "SELECT summary_date, total_collection, total_receivable, total_payable " +
                             "FROM ai_daily_summary WHERE company_id = ? AND summary_date = ?";
                PreparedStatement ps = conn.prepareStatement(sql);
                ps.setString(1, companyId);
                ps.setDate(2, Date.valueOf(targetDate));
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("date",             rs.getString("summary_date"));
                    result.put("total_collection", formatAmount(rs.getDouble("total_collection")));
                    result.put("total_receivable", formatAmount(rs.getDouble("total_receivable")));
                    result.put("total_payable",    formatAmount(rs.getDouble("total_payable")));
                    return result.toString();
                }
                return "No collection data for " + targetDate;
            }
            // Party-specific: sum from ai_sales
            String sql = "SELECT SUM(invoice_amount) as total, COUNT(*) as count " +
                         "FROM ai_sales WHERE company_id = ? AND LOWER(party_name) LIKE LOWER(?) " +
                         "AND bill_date = ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, companyId);
            ps.setString(2, "%" + partyName.trim() + "%");
            ps.setDate(3, Date.valueOf(targetDate));
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ObjectNode result = mapper.createObjectNode();
                result.put("party",   partyName);
                result.put("date",    targetDate);
                result.put("total",   formatAmount(rs.getDouble("total")));
                result.put("bills",   rs.getInt("count"));
                return result.toString();
            }
            return "No collection data for " + partyName + " on " + targetDate;
        } catch (SQLException e) { return "Error: " + e.getMessage(); }
    }
}