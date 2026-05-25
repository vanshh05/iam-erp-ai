package com.iamerp.ai.functions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

/**
 * All query functions that Gemini can call.
 * Each method queries the Supabase AI tables and returns a clean string result.
 */
@Service
public class ErpFunctions {

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();

    public ErpFunctions(@Qualifier("supabaseDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── 1. Stock queries ─────────────────────────────────────────────────────

    /**
     * Get current stock for a specific item or all items.
     * @param itemName null = all items, otherwise specific item
     */
    public String getCurrentStock(String itemName) {
        try (Connection conn = dataSource.getConnection()) {
            String sql;
            PreparedStatement ps;

            if (itemName != null && !itemName.isBlank()) {
                sql = "SELECT item_name, category, current_qty, unit, min_stock_level, selling_rate " +
                      "FROM ai_stock WHERE LOWER(item_name) LIKE LOWER(?) ORDER BY item_name";
                ps = conn.prepareStatement(sql);
                ps.setString(1, "%" + itemName.trim() + "%");
            } else {
                sql = "SELECT item_name, category, current_qty, unit, min_stock_level, selling_rate " +
                      "FROM ai_stock ORDER BY category, item_name";
                ps = conn.prepareStatement(sql);
            }

            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();
            int count = 0;

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("item", rs.getString("item_name"));
                row.put("category", rs.getString("category"));
                row.put("qty", rs.getDouble("current_qty"));
                row.put("unit", rs.getString("unit"));
                row.put("min_level", rs.getDouble("min_stock_level"));
                row.put("rate", rs.getDouble("selling_rate"));
                row.put("low_stock", rs.getDouble("current_qty") < rs.getDouble("min_stock_level"));
                results.add(row);
                count++;
            }

            if (count == 0) return "No stock found" + (itemName != null ? " for: " + itemName : "");
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching stock: " + e.getMessage();
        }
    }

    /**
     * Get items that are below minimum stock level.
     */
    public String getLowStockItems() {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT item_name, category, current_qty, min_stock_level, unit " +
                         "FROM ai_stock WHERE current_qty < min_stock_level ORDER BY (min_stock_level - current_qty) DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("item", rs.getString("item_name"));
                row.put("category", rs.getString("category"));
                row.put("current_qty", rs.getDouble("current_qty"));
                row.put("min_level", rs.getDouble("min_stock_level"));
                row.put("unit", rs.getString("unit"));
                row.put("shortage", rs.getDouble("min_stock_level") - rs.getDouble("current_qty"));
                results.add(row);
            }

            if (results.isEmpty()) return "All items are adequately stocked.";
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching low stock: " + e.getMessage();
        }
    }

    // ─── 2. Sales queries ─────────────────────────────────────────────────────

    /**
     * Get today's sales summary.
     */
    public String getTodaySales() {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT total_sales, total_bills_count, cash_sales, credit_sales, " +
                         "total_discount_given, total_tax_collected, total_collection " +
                         "FROM ai_daily_summary WHERE summary_date = CURRENT_DATE";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                ObjectNode result = mapper.createObjectNode();
                result.put("date", LocalDate.now().toString());
                result.put("total_sales", rs.getDouble("total_sales"));
                result.put("total_bills", rs.getInt("total_bills_count"));
                result.put("cash_sales", rs.getDouble("cash_sales"));
                result.put("credit_sales", rs.getDouble("credit_sales"));
                result.put("collection", rs.getDouble("total_collection"));
                result.put("discount_given", rs.getDouble("total_discount_given"));
                result.put("tax_collected", rs.getDouble("total_tax_collected"));
                return result.toString();
            }
            return "No sales data found for today.";

        } catch (SQLException e) {
            return "Error fetching today's sales: " + e.getMessage();
        }
    }

    /**
     * Get cumulative sales between two dates.
     * @param fromDate start date (yyyy-MM-dd), null = start of current month
     * @param toDate   end date   (yyyy-MM-dd), null = today
     */
    public String getCumulativeSales(String fromDate, String toDate) {
        try (Connection conn = dataSource.getConnection()) {
            String from = (fromDate != null && !fromDate.isBlank()) ? fromDate
                          : LocalDate.now().withDayOfMonth(1).toString();
            String to   = (toDate != null && !toDate.isBlank()) ? toDate
                          : LocalDate.now().toString();

            String sql = "SELECT SUM(total_sales) AS total_sales, SUM(total_bills_count) AS total_bills, " +
                         "SUM(cash_sales) AS cash_sales, SUM(credit_sales) AS credit_sales, " +
                         "SUM(total_collection) AS total_collection, SUM(total_discount_given) AS discount, " +
                         "SUM(total_tax_collected) AS tax, COUNT(*) AS days_count " +
                         "FROM ai_daily_summary WHERE summary_date BETWEEN ? AND ?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setDate(1, Date.valueOf(from));
            ps.setDate(2, Date.valueOf(to));
            ResultSet rs = ps.executeQuery();

            if (rs.next()) {
                ObjectNode result = mapper.createObjectNode();
                result.put("from", from);
                result.put("to", to);
                result.put("total_sales", rs.getDouble("total_sales"));
                result.put("total_bills", rs.getInt("total_bills"));
                result.put("cash_sales", rs.getDouble("cash_sales"));
                result.put("credit_sales", rs.getDouble("credit_sales"));
                result.put("total_collection", rs.getDouble("total_collection"));
                result.put("total_discount", rs.getDouble("discount"));
                result.put("total_tax", rs.getDouble("tax"));
                result.put("days_active", rs.getInt("days_count"));
                return result.toString();
            }
            return "No sales data found for the given period.";

        } catch (SQLException e) {
            return "Error fetching cumulative sales: " + e.getMessage();
        }
    }

    /**
     * Get individual bills for today or a specific date.
     * @param date specific date (yyyy-MM-dd), null = today
     */
    public String getBillsForDate(String date, String partyName) {
        try (Connection conn = dataSource.getConnection()) {
            String targetDate = (date != null && !date.isBlank()) ? date : LocalDate.now().toString();

            StringBuilder sql = new StringBuilder(
                "SELECT bill_no, bill_time, party_name, gross_amount, discount_amount, " +
                "tax_amount, net_amount, payment_mode, created_by " +
                "FROM ai_sales WHERE bill_date = ?"
            );
            if (partyName != null && !partyName.isBlank()) {
                sql.append(" AND LOWER(party_name) LIKE LOWER(?)");
            }
            sql.append(" ORDER BY bill_time DESC");

            PreparedStatement ps = conn.prepareStatement(sql.toString());
            ps.setDate(1, Date.valueOf(targetDate));
            if (partyName != null && !partyName.isBlank()) {
                ps.setString(2, "%" + partyName.trim() + "%");
            }

            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("bill_no", rs.getString("bill_no"));
                row.put("time", rs.getString("bill_time"));
                row.put("party", rs.getString("party_name"));
                row.put("gross", rs.getDouble("gross_amount"));
                row.put("discount", rs.getDouble("discount_amount"));
                row.put("tax", rs.getDouble("tax_amount"));
                row.put("net", rs.getDouble("net_amount"));
                row.put("payment_mode", rs.getString("payment_mode"));
                row.put("created_by", rs.getString("created_by"));
                results.add(row);
            }

            if (results.isEmpty()) return "No bills found for " + targetDate;
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching bills: " + e.getMessage();
        }
    }

    // ─── 3. Party queries ─────────────────────────────────────────────────────

    /**
     * Get full status of a specific party/trader.
     */
    public String getPartyStatus(String partyName) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT party_name, party_type, contact_number, outstanding_balance, " +
                         "balance_type, last_bill_date, last_bill_amount, total_business_ytd, " +
                         "credit_limit, overdue_days " +
                         "FROM ai_party_status WHERE LOWER(party_name) LIKE LOWER(?) ORDER BY party_name";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, "%" + partyName.trim() + "%");
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("party_name", rs.getString("party_name"));
                row.put("type", rs.getString("party_type"));
                row.put("contact", rs.getString("contact_number"));
                row.put("outstanding", rs.getDouble("outstanding_balance"));
                row.put("balance_type", rs.getString("balance_type"));
                row.put("last_bill_date", rs.getString("last_bill_date"));
                row.put("last_bill_amount", rs.getDouble("last_bill_amount"));
                row.put("total_business_ytd", rs.getDouble("total_business_ytd"));
                row.put("credit_limit", rs.getDouble("credit_limit"));
                row.put("overdue_days", rs.getInt("overdue_days"));
                results.add(row);
            }

            if (results.isEmpty()) return "No party found matching: " + partyName;
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching party status: " + e.getMessage();
        }
    }

    /**
     * Get list of parties with overdue payments.
     */
    public String getOverdueParties(int minOverdueDays) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT party_name, outstanding_balance, balance_type, overdue_days, " +
                         "last_bill_date, contact_number " +
                         "FROM ai_party_status WHERE overdue_days >= ? AND balance_type = 'DR' " +
                         "ORDER BY overdue_days DESC, outstanding_balance DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setInt(1, minOverdueDays);
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("party", rs.getString("party_name"));
                row.put("outstanding", rs.getDouble("outstanding_balance"));
                row.put("overdue_days", rs.getInt("overdue_days"));
                row.put("last_bill_date", rs.getString("last_bill_date"));
                row.put("contact", rs.getString("contact_number"));
                results.add(row);
            }

            if (results.isEmpty()) return "No overdue parties found.";
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching overdue parties: " + e.getMessage();
        }
    }

    // ─── 4. Ledger queries ────────────────────────────────────────────────────

    /**
     * Get ledger summary for a party over a period.
     */
    public String getLedgerSummary(String partyName, String fromDate, String toDate) {
        try (Connection conn = dataSource.getConnection()) {
            String from = (fromDate != null && !fromDate.isBlank()) ? fromDate
                          : LocalDate.now().withDayOfMonth(1).toString();
            String to   = (toDate != null && !toDate.isBlank()) ? toDate
                          : LocalDate.now().toString();

            String sql = "SELECT ls.party_name, ls.period_from, ls.period_to, " +
                         "ls.opening_balance, ls.total_debit, ls.total_credit, " +
                         "ls.closing_balance, ls.balance_type " +
                         "FROM ai_ledger_summary ls " +
                         "WHERE LOWER(ls.party_name) LIKE LOWER(?) " +
                         "AND ls.period_from >= ? AND ls.period_to <= ? " +
                         "ORDER BY ls.party_name, ls.period_from";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, "%" + partyName.trim() + "%");
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("party", rs.getString("party_name"));
                row.put("period_from", rs.getString("period_from"));
                row.put("period_to", rs.getString("period_to"));
                row.put("opening_balance", rs.getDouble("opening_balance"));
                row.put("total_debit", rs.getDouble("total_debit"));
                row.put("total_credit", rs.getDouble("total_credit"));
                row.put("closing_balance", rs.getDouble("closing_balance"));
                row.put("balance_type", rs.getString("balance_type"));
                results.add(row);
            }

            if (results.isEmpty()) return "No ledger data found for " + partyName;
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching ledger: " + e.getMessage();
        }
    }

    // ─── 5. Collection queries ────────────────────────────────────────────────

    /**
     * Get collections (payments received) for today or a specific party.
     */
    public String getCollections(String partyName, String date) {
        try (Connection conn = dataSource.getConnection()) {
            StringBuilder sql = new StringBuilder(
                "SELECT receipt_no, receipt_date, party_name, amount, payment_mode, bank_name, created_by " +
                "FROM ai_collection WHERE 1=1"
            );
            List<Object> params = new ArrayList<>();

            if (date != null && !date.isBlank()) {
                sql.append(" AND receipt_date = ?");
                params.add(Date.valueOf(date));
            } else {
                sql.append(" AND receipt_date = CURRENT_DATE");
            }

            if (partyName != null && !partyName.isBlank()) {
                sql.append(" AND LOWER(party_name) LIKE LOWER(?)");
                params.add("%" + partyName.trim() + "%");
            }

            sql.append(" ORDER BY receipt_date DESC, party_name");

            PreparedStatement ps = conn.prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();
            double total = 0;

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("receipt_no", rs.getString("receipt_no"));
                row.put("date", rs.getString("receipt_date"));
                row.put("party", rs.getString("party_name"));
                row.put("amount", rs.getDouble("amount"));
                row.put("mode", rs.getString("payment_mode"));
                row.put("bank", rs.getString("bank_name"));
                row.put("created_by", rs.getString("created_by"));
                results.add(row);
                total += rs.getDouble("amount");
            }

            if (results.isEmpty()) return "No collections found.";

            ObjectNode response = mapper.createObjectNode();
            response.put("total_collected", total);
            response.put("count", results.size());
            response.set("entries", results);
            return response.toString();

        } catch (SQLException e) {
            return "Error fetching collections: " + e.getMessage();
        }
    }

    // ─── 6. Stock movement queries ────────────────────────────────────────────

    /**
     * Get stock movement history for an item.
     */
    public String getStockMovement(String itemName, String fromDate, String toDate) {
        try (Connection conn = dataSource.getConnection()) {
            String from = (fromDate != null && !fromDate.isBlank()) ? fromDate
                          : LocalDate.now().minusDays(7).toString();
            String to   = (toDate != null && !toDate.isBlank()) ? toDate
                          : LocalDate.now().toString();

            String sql = "SELECT movement_date, item_name, movement_type, qty, reference_no, party_name, rate " +
                         "FROM ai_stock_movement " +
                         "WHERE LOWER(item_name) LIKE LOWER(?) " +
                         "AND movement_date BETWEEN ? AND ? " +
                         "ORDER BY movement_date DESC, created_at DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, "%" + itemName.trim() + "%");
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            ResultSet rs = ps.executeQuery();
            ArrayNode results = mapper.createArrayNode();

            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                row.put("date", rs.getString("movement_date"));
                row.put("item", rs.getString("item_name"));
                row.put("type", rs.getString("movement_type"));
                row.put("qty", rs.getDouble("qty"));
                row.put("reference", rs.getString("reference_no"));
                row.put("party", rs.getString("party_name"));
                row.put("rate", rs.getDouble("rate"));
                results.add(row);
            }

            if (results.isEmpty()) return "No stock movement found for " + itemName;
            return results.toString();

        } catch (SQLException e) {
            return "Error fetching stock movement: " + e.getMessage();
        }
    }
}
