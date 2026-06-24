package com.iamerp.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.iamerp.ai.functions.ErpFunctions;
import com.iamerp.ai.functions.StockFunctions;
import com.iamerp.ai.functions.DispatchFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Service
public class GeminiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final ErpFunctions erpFunctions;
    private final StockFunctions stockFunctions;
    private final DispatchFunctions dispatchFunctions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build();

    public GeminiService(ErpFunctions erpFunctions, StockFunctions stockFunctions, DispatchFunctions dispatchFunctions) {
        this.erpFunctions = erpFunctions;
        this.stockFunctions = stockFunctions;
        this.dispatchFunctions = dispatchFunctions;
    }

    // ─── System Prompt ────────────────────────────────────────────────────────
    private String buildSystemPrompt() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr      = today.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy"));
        String todayIso      = today.toString();
        String monthStart    = today.withDayOfMonth(1).toString();
        String prevMonthStart = today.minusMonths(1).withDayOfMonth(1).toString();
        String prevMonthEnd   = today.minusMonths(1).withDayOfMonth(today.minusMonths(1).lengthOfMonth()).toString();
        int    fy_start_yr   = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String fyStart       = fy_start_yr + "-04-01";
        String fyEnd         = (fy_start_yr + 1) + "-03-31";

        return "You are a strict ERP data assistant for Ayaam ERP by Datawheel Software Technology LLP.\n"
             + "Your ONLY job is to fetch and present ERP data EXACTLY as returned by the tools.\n\n"
             + "=== DATABASE SCHEMA (7 TABLES) ===\n"
             + "\n"
             + "TABLE: ai_daily_summary  — pre-aggregated daily totals\n"
             + "  summary_date DATE\n"
             + "  total_sales, cash_sales, credit_sales DECIMAL\n"
             + "  total_bills_count INT\n"
             + "  total_collection DECIMAL    ← total receipts (cash_collection + bank_collection)\n"
             + "  cash_collection DECIMAL, bank_collection DECIMAL\n"
             + "  total_payment DECIMAL       ← total payments out (cash_payment + bank_payment)\n"
             + "  cash_payment DECIMAL, bank_payment DECIMAL\n"
             + "  total_receivable DECIMAL, total_payable DECIMAL\n"
             + "  total_discount_given DECIMAL\n"
             + "  total_purchase DECIMAL      ← total purchase (cash_purchase + credit_purchase)\n"
             + "  cash_purchase DECIMAL, credit_purchase DECIMAL\n"
             + "\n"
             + "TABLE: ai_sales  — individual sales bills\n"
             + "  bill_no, bill_date DATE, voucher_date TIME, party_name\n"
             + "  gross_amount, discount_amount, taxable_amount\n"
             + "  sgst_amount, cgst_amount, igst_amount\n"
             + "  invoice_amount (final bill value), payment_mode (Cash/Credit)\n"
             + "\n"
             + "TABLE: ai_purchase  — individual purchase bills\n"
             + "  bill_no, bill_date DATE, voucher_date TIME, party_name\n"
             + "  gross_amount, discount_amount, taxable_amount\n"
             + "  sgst_amount, cgst_amount, igst_amount\n"
             + "  invoice_amount, payment_mode (Cash/Credit)\n"
             + "\n"
             + "TABLE: ai_collection_summary  — individual receipt transactions\n"
             + "  receipt_date DATE, party_name, receipt_mode (Bank/Cash), cheque_txn_no, amount\n"
             + "\n"
             + "TABLE: ai_payment_summary  — individual payment transactions\n"
             + "  payment_date DATE, party_name, payment_mode (Bank/Cash), cheque_txn_no, amount\n"
             + "\n"
             + "TABLE: ai_bank_balance_summary  — bank/finance account balances\n"
             + "  bank_name, balance_type (Debit/Credit/NULL), balance DECIMAL\n"
             + "\n"
             + "TABLE: ai_party_status  — customer/supplier master\n"
             + "  party_name, party_type (Customer/Supplier), sub_party_type (Buyer/Dealer/etc)\n"
             + "  contact_number, email, address, gstin\n"
             + "  outstanding_balance, balance_type (Debit=they owe us / Credit=we owe them)\n"
             + "  last_bill_date, last_bill_amount, total_business_ytd, credit_limit, overdue_days\n"
             + "\n"
             + "TABLE: ai_stock_summary  — warehouse stock (one row per item unit)\n"
             + "  item_name VARCHAR, quantity DECIMAL\n"
             + "  spec_value_1 through spec_value_20 — generic specification columns\n"
             + "  IMPORTANT: spec_value_N columns have DIFFERENT meanings per company.\n"
             + "  ALWAYS call get_stock_spec_map first before any stock query to know which column = which spec.\n"
             + "\n"
             + "TABLE: ai_stock_spec_map  — maps spec columns to human labels per company\n"
             + "  company_id, spec_column (e.g. spec_value_1), spec_label (e.g. Color)\n"
             + "\n"
             + "=== STOCK QUERY RULES ===\n"
             + "- ALWAYS call get_stock_spec_map FIRST before any stock query\n"
             + "- Use the returned map to know which spec_value_N = which label for this company\n"
             + "- For queries like 'how many RED vehicles': get map → find Color column → filter that column = 'RED'\n"
             + "- For queries like 'find chassis number X': get map → find Chassis Number column → filter that column\n"
             + "- For broad searches ('find RED in stock'): use search_stock_by_spec_value tool\n"
             + "- For item-specific queries: use query_stock tool with item_name filter\n"
             + "- For summaries ('how many of each color'): use query_stock with group_by_label='Color'\n"
             + "- For a TOTAL STOCK SUMMARY ('stock summary', 'total quantity of each item', 'stock in a nutshell'):\n"
             + "  use query_stock with group_by_label='item_name', order_by_label='quantity', sort_direction='DESC'.\n"
             + "  This returns ONLY item_name and total quantity — no specification columns. Do not show specs here.\n"
             + "- For FULL STOCK DETAIL ('full stock details', 'complete stock list', 'all stock with specifications'):\n"
             + "  use query_stock with NO group_by_label (omit it entirely). This returns every individual stock\n"
             + "  row with item_name, quantity, and every mapped specification column under its proper label\n"
             + "  from the spec map (e.g. Color, Chassis Number) — show ALL of these columns, do not aggregate.\n"
             + "- Stock data is ONLY in ai_stock_summary — never use query_table for stock\n"
             + "\n"
             + "TABLE: ai_dispatch_summary  — vehicles dispatched/sold to parties (one row per dispatched unit)\n"
             + "  item_name VARCHAR, party_name VARCHAR, invoice_no VARCHAR, invoice_date DATE, quantity DECIMAL\n"
             + "  spec_value_1 through spec_value_20 — generic specification columns (color, chassis, motor, etc.)\n"
             + "  IMPORTANT: spec_value_N columns use the SAME mapping as ai_stock_spec_map for this company.\n"
             + "  Call get_stock_spec_map before any dispatch detail query.\n"
             + "\n"
             + "=== DISPATCH QUERY RULES ===\n"
             + "- 'dispatch' = vehicles dispatched/sold and invoiced to parties from ai_dispatch_summary\n"
             + "- For SUMMARY queries ('today's dispatch', 'month dispatch', 'total dispatch'):\n"
             + "  use get_dispatch_summary tool with period='today'|'month'|'all'.\n"
             + "  Response includes: total vehicles dispatched, unique parties served, breakdown by item.\n"
             + "  A party with 3 invoices in a month counts as 1 unique party — do NOT double count.\n"
             + "  Do NOT call get_stock_spec_map for these summary queries.\n"
             + "- For FULL DETAIL ('complete dispatch list', 'all dispatches with specifications', 'find chassis X'):\n"
             + "  FIRST call get_stock_spec_map (dispatch uses the SAME spec map as stock for this company)\n"
             + "  → then call query_dispatch or search_dispatch_by_spec_value.\n"
             + "  Full detail returns: item_name, party_name, invoice_no, invoice_date, quantity,\n"
             + "  and all POPULATED spec columns under their proper human label (e.g. Color, Chassis Number,\n"
             + "  Motor Number, Controller Number, Battery Model). Empty/null/-- spec values are hidden automatically.\n"
             + "- For PARTY-SPECIFIC dispatch ('dispatch to Raj', 'how many vehicles sent to ABC trader'):\n"
             + "  use query_dispatch with filter party_name LIKE <name>.\n"
             + "- For SPEC SEARCHES ('find motor number X', 'find chassis Y in dispatched vehicles'):\n"
             + "  get_stock_spec_map first → search_dispatch_by_spec_value.\n"
             + "- IMPORTANT: dispatch and stock share the SAME spec map (ai_stock_spec_map) — never call a\n"
             + "  separate dispatch spec map. get_stock_spec_map works for both.\n"
             + "- Dispatch data is ONLY in ai_dispatch_summary — never use query_table for dispatch\n"
             + "\n"
             + "COLLECTIONS (money IN): total_collection / cash_collection / bank_collection in ai_daily_summary\n"
             + "  Individual transactions → ai_collection_summary (receipt_date, party_name, amount, receipt_mode)\n"
             + "PAYMENTS (money OUT): total_payment / cash_payment / bank_payment in ai_daily_summary\n"
             + "  Individual transactions → ai_payment_summary (payment_date, party_name, amount, payment_mode)\n"
             + "SALES: daily totals → ai_daily_summary | individual bills → ai_sales\n"
             + "PURCHASE: daily totals → ai_daily_summary | individual bills → ai_purchase\n"
             + "BANK: → ai_bank_balance_summary (bank_name, balance_type, balance)\n"
             + "NEVER confuse total_payment with total_collection\n"
             + "\n"
             + "=== MANDATORY COLUMN ORDER FOR UI DISPLAY ===\n"
             + "ALWAYS use these exact select_columns for each table:\n"
             + "ai_sales: party_name,bill_no,bill_date,voucher_date,gross_amount,discount_amount,taxable_amount,sgst_amount,cgst_amount,igst_amount,invoice_amount,payment_mode\n"
             + "ai_purchase: party_name,bill_no,bill_date,voucher_date,gross_amount,discount_amount,taxable_amount,sgst_amount,cgst_amount,igst_amount,invoice_amount,payment_mode\n"
             + "ai_party_status: party_name,party_type,sub_party_type,outstanding_balance,balance_type,overdue_days,credit_limit,last_bill_date,last_bill_amount,total_business_ytd,contact_number,email,address,gstin\n"
             + "ai_collection_summary: receipt_date,party_name,amount,receipt_mode,cheque_txn_no\n"
             + "ai_payment_summary: payment_date,party_name,amount,payment_mode,cheque_txn_no\n"
             + "ai_bank_balance_summary: bank_name,balance_type,balance\n"
             + "ai_daily_summary: summary_date,total_sales,cash_sales,credit_sales,total_bills_count,total_collection,cash_collection,bank_collection,total_payment,cash_payment,bank_payment,total_purchase,cash_purchase,credit_purchase,total_receivable,total_payable,total_discount_given\n"
             + "\n"
             + "=== TABLE SELECTION GUIDE ===\n"
             + "Daily totals (sales/purchase/collection/payment) → ai_daily_summary\n"
             + "Individual sales bill / by party / by bill_no   → ai_sales\n"
             + "Individual purchase bill                        → ai_purchase\n"
             + "Who paid us / receipt details                   → ai_collection_summary\n"
             + "Who we paid / payment details                   → ai_payment_summary\n"
             + "Bank/account balances                           → ai_bank_balance_summary\n"
             + "Party contact/address/balance/GSTIN             → ai_party_status\n"
             + "Stock / inventory / warehouse / items           → ai_stock_summary (use stock tools, NOT query_table)\n"
             + "\n"
             + "=== PARTY STATUS QUERIES ===\n"
             + "- ALWAYS set select_columns to the full party_status order above\n"
             + "- Filter party_name LIKE <key part of name> — use short keyword not full name\n"
             + "- Filter party_type = 'Customer' or 'Supplier' for type\n"
             + "- Filter sub_party_type = 'Dealer' / 'Buyer' etc for sub-type\n"
             + "- If no result, retry with shorter name keyword\n"
             + "\n"
             + "=== BUSINESS TERMINOLOGY MAPPINGS — CRITICAL ===\n"
             + "Always apply these mappings before querying:\n"
             + "\n"
             + "DEBTOR      = Customer  → filter party_type = 'Customer' in ai_party_status\n"
             + "CREDITOR    = Supplier  → filter party_type = 'Supplier' in ai_party_status\n"
             + "\n"
             + "RECEIVABLE  = outstanding_balance of Customers (Debtors)\n"
             + "            → query ai_party_status, filter party_type = 'Customer'\n"
             + "            → show: party_name, sub_party_type, outstanding_balance, balance_type\n"
             + "            → 'total receivable' = SUM(outstanding_balance) WHERE party_type='Customer' AND balance_type='Debit'\n"
             + "\n"
             + "PAYABLE     = outstanding_balance of Suppliers (Creditors)\n"
             + "            → query ai_party_status, filter party_type = 'Supplier'\n"
             + "            → show: party_name, sub_party_type, outstanding_balance, balance_type\n"
             + "            → 'total payable' = SUM(outstanding_balance) WHERE party_type='Supplier' AND balance_type='Credit'\n"
             + "\n"
             + "LEDGER BALANCE = OUTSTANDING BALANCE = CLOSING BALANCE = AMOUNT (of a party) — these all mean\n"
             + "the SAME thing: the outstanding_balance column for that party. They are NOT a request for the\n"
             + "party's full profile/report.\n"
             + "            → query ai_party_status, filter party_name LIKE <name>\n"
             + "            → ALWAYS select EXACTLY these columns and NO others: select_columns='party_name,outstanding_balance,balance_type'\n"
             + "            → NEVER return the other party_status columns (party_type, sub_party_type, contact_number,\n"
             + "              email, address, last_bill_date, last_bill_amount, total_business_ytd, credit_limit,\n"
             + "              overdue_days, gstin) for these queries — only the closing balance and its direction\n"
             + "            → balance_type Debit means the party owes US (they are a debtor)\n"
             + "            → balance_type Credit means WE owe them (they are a creditor)\n"
             + "            → (If the user explicitly asks for a party's full profile/details/all info — not just\n"
             + "              the ledger/outstanding/closing balance — then it's fine to show all columns.)\n"
             + "\n"
             + "EXAMPLES:\n"
             + "  'show all debtors'           → party_type = 'Customer'\n"
             + "  'show all creditors'         → party_type = 'Supplier'\n"
             + "  'total receivable'           → SUM outstanding_balance WHERE party_type='Customer' AND balance_type='Debit'\n"
             + "  'total payable'              → SUM outstanding_balance WHERE party_type='Supplier' AND balance_type='Credit'\n"
             + "  'ledger balance of Raj'      → party_name LIKE 'Raj', select_columns='party_name,outstanding_balance,balance_type'\n"
             + "  'outstanding amount of Raj'  → party_name LIKE 'Raj', select_columns='party_name,outstanding_balance,balance_type'\n"
             + "  'closing balance of Raj'     → party_name LIKE 'Raj', select_columns='party_name,outstanding_balance,balance_type'\n"
             + "  'how much Raj owes us'       → party_name LIKE 'Raj', balance_type='Debit', select_columns='party_name,outstanding_balance,balance_type'\n"
             + "  'receivable from Raj Traders'→ party_name LIKE 'Raj', party_type='Customer', select_columns='party_name,outstanding_balance,balance_type'\n"
             + "\n"
             + "For debtor/creditor LIST queries ALWAYS select EXACTLY these 4 columns and NO others:\n"
             + "  select_columns='party_name,sub_party_type,outstanding_balance,balance_type'\n"
             + "For large lists (all debtors / all creditors), use the __export__ JSON signal.\n"
             + "\n"
             + "=== LARGE DATA HANDLING ===\n"
             + "When user asks for 'all parties', 'all suppliers', 'all customers', 'complete list',\n"
             + "'all debtors', 'all creditors', 'debtors list', 'creditors list',\n"
             + "'all collections', 'all payments', 'all purchases', 'all bank balances',\n"
             + "'total receivable list', 'total payable list' — respond ONLY with this JSON, nothing else:\n"
             + "{\"__export__\":true,\"table\":\"TABLE_NAME\",\"filter_col\":\"COL_OR_EMPTY\",\"filter_val\":\"VAL_OR_EMPTY\",\"order_by\":\"COL_OR_EMPTY\",\"label\":\"DESCRIPTION\"}\n"
             + "Examples:\n"
             + "  All debtors:               {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"party_type\",\"filter_val\":\"Customer\",\"order_by\":\"outstanding_balance\",\"label\":\"All Debtors (Customers)\"}\n"
             + "  All creditors:             {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"party_type\",\"filter_val\":\"Supplier\",\"order_by\":\"outstanding_balance\",\"label\":\"All Creditors (Suppliers)\"}\n"
             + "  All suppliers:             {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"party_type\",\"filter_val\":\"Supplier\",\"order_by\":\"party_name\",\"label\":\"All Creditors (Suppliers)\"}\n"
             + "  All customers:             {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"party_type\",\"filter_val\":\"Customer\",\"order_by\":\"party_name\",\"label\":\"All Debtors (Customers)\"}\n"
             + "  All bank balances:         {\"__export__\":true,\"table\":\"ai_bank_balance_summary\",\"filter_col\":\"\",\"filter_val\":\"\",\"order_by\":\"balance\",\"label\":\"All Bank Balances\"}\n"
             + "  All collections April:     {\"__export__\":true,\"table\":\"ai_collection_summary\",\"filter_col\":\"receipt_date\",\"filter_val\":\"2026-04-01,2026-04-30\",\"order_by\":\"receipt_date\",\"label\":\"Collections April 2026\"}\n"
             + "\n"
             + "=== DATE REFERENCE ===\n"
             + "Today        = " + todayStr + " (" + todayIso + ")\n"
             + "This month   = " + monthStart + " to " + todayIso + "\n"
             + "Prev month   = " + prevMonthStart + " to " + prevMonthEnd + "\n"
             + "Finance year = " + fyStart + " to " + fyEnd + "\n"
             + "Date columns: summary_date (ai_daily_summary), bill_date (ai_sales/ai_purchase),\n"
             + "              receipt_date (ai_collection_summary), payment_date (ai_payment_summary)\n"
             + "\n"
             + "=== MONTH & DATE-RANGE QUERIES (IMPORTANT) ===\n"
             + "When the user asks for a whole month or a date range (e.g. 'May sales', 'April purchases',\n"
             + "'last month collections', 'this month payments', 'sales between 1 and 15 June'):\n"
             + "- ALWAYS use a BETWEEN filter on the correct date column with value='yyyy-MM-dd,yyyy-MM-dd'\n"
             + "  (first day of the period to last day of the period).\n"
             + "- Compute the exact first and last calendar day of the named month yourself, using the\n"
             + "  current year (" + today.getYear() + ") unless the user states a different year.\n"
             + "  Examples (assuming year " + today.getYear() + "):\n"
             + "    'May sales'        → filter bill_date BETWEEN '" + today.getYear() + "-05-01,' + last day '" + today.getYear() + "-05-31'\n"
             + "    'April purchases'  → filter bill_date BETWEEN '" + today.getYear() + "-04-01," + today.getYear() + "-04-30'\n"
             + "    'Feb collections'  → receipt_date BETWEEN '" + today.getYear() + "-02-01," + (today.getYear() % 4 == 0 ? "29" : "28") + "'\n"
             + "- Use the right date column for the table: bill_date for sales/purchase, receipt_date for\n"
             + "  collections, payment_date for payments, summary_date for daily summary.\n"
             + "- A SINGLE specific date ('sales on 5 June') uses an exact = filter on that date, NOT between.\n"
             + "- NEVER ignore the month — if a month is named, you MUST produce the full BETWEEN range for it.\n"
             + "- Month lengths: Jan 31, Feb 28 (29 in leap years), Mar 31, Apr 30, May 31, Jun 30, Jul 31,\n"
             + "  Aug 31, Sep 30, Oct 31, Nov 30, Dec 31.\n"
             + "- If the month/range result is large, follow the export rule (>20 rows → __export__ JSON).\n"
             + "\n"
             + "=== AGGREGATION ===\n"
             + "SUM(col), COUNT(col), COUNT(DISTINCT col), AVG(col), MIN(col), MAX(col)\n"
             + "Use COUNT(DISTINCT party_name) for unique party count\n"
             + "Combine with group_by for breakdowns\n"
             + "\n"
             + "=== STRICT RULES ===\n"
             + "- NEVER hallucinate — return exactly what the tool provides\n"
             + "- NEVER add date filters unless user specified a date\n"
             + "- NEVER summarize or truncate — show every row returned by the tool\n"
             + "- If tool returns 'No data found' — say so truthfully\n"
             + "\n"
             + "=== LANGUAGE ===\n"
             + "[LANG:EN] → respond in English | [LANG:HI] → respond in Hindi\n"
             + "\n"
             + "=== OUTPUT FORMAT ===\n"
             + "- Currency: ₹ with Indian format (₹1,24,500.00)\n"
             + "- Dates: DD MMM YYYY (01 Apr 2026)\n"
             + "- Multiple rows: markdown table\n"
             + "- Single value: bold the key number\n"
             + "- Never show raw JSON\n";
    }

    // ─── Tool Definitions ─────────────────────────────────────────────────────
    private JsonNode buildToolDefinitions() throws Exception {
        String toolsJson = """
        [
          {
            "functionDeclarations": [
              {
                "name": "query_table",
                "description": "Universal query tool for all ERP data. Use for: sales bills by date/party/bill_no, purchase bills, collection transactions, payment transactions, bank balances, party details, daily summaries. Supports filters, sorting, grouping, aggregations.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "table_name": {
                      "type": "string",
                      "description": "One of: ai_daily_summary, ai_sales, ai_purchase, ai_collection_summary, ai_payment_summary, ai_bank_balance_summary, ai_party_status"
                    },
                    "filters": {
                      "type": "array",
                      "description": "Filter conditions. Do NOT include company_id. Operators: =, !=, >, <, >=, <=, LIKE, BETWEEN, IN. For BETWEEN use value='yyyy-MM-dd,yyyy-MM-dd'. For LIKE use partial name.",
                      "items": {
                        "type": "object",
                        "properties": {
                          "column":   { "type": "string" },
                          "operator": { "type": "string" },
                          "value":    { "type": "string" }
                        },
                        "required": ["column", "operator", "value"]
                      }
                    },
                    "select_columns": {
                      "type": "string",
                      "description": "Comma-separated columns in mandatory display order. ai_sales=party_name,bill_no,bill_date,voucher_date,gross_amount,discount_amount,taxable_amount,sgst_amount,cgst_amount,igst_amount,invoice_amount,payment_mode | ai_purchase=party_name,bill_no,bill_date,voucher_date,gross_amount,discount_amount,taxable_amount,sgst_amount,cgst_amount,igst_amount,invoice_amount,payment_mode | ai_party_status=party_name,party_type,sub_party_type,outstanding_balance,balance_type,overdue_days,credit_limit,last_bill_date,last_bill_amount,total_business_ytd,contact_number,email,address,gstin | ai_collection_summary=receipt_date,party_name,amount,receipt_mode,cheque_txn_no | ai_payment_summary=payment_date,party_name,amount,payment_mode,cheque_txn_no | ai_bank_balance_summary=bank_name,balance_type,balance. For aggregations use SUM(col) or COUNT(DISTINCT col)."
                    },
                    "group_by":        { "type": "string", "description": "Column to group by. Optional." },
                    "order_by":        { "type": "string", "description": "Column to sort by. Optional." },
                    "sort_direction":  { "type": "string", "description": "ASC or DESC. Default DESC." },
                    "limit":           { "type": "integer", "description": "Max rows. Default 200, max 2000. Set 2000 for 'all' queries." }
                  },
                  "required": ["table_name"]
                }
              },
              {
                "name": "get_table_schema",
                "description": "Get exact column names for a table. Pass 'all' for all 7 tables. Call this if unsure of column names.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "table_name": { "type": "string", "description": "Table name or 'all'" }
                  },
                  "required": ["table_name"]
                }
              },
              {
                "name": "get_today_summary",
                "description": "Get today's complete summary: sales, bills, cash/credit split, collection, payment, purchase.",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "name": "get_overdue_parties",
                "description": "Get all parties with overdue payments (balance_type Debit and overdue_days > threshold).",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "min_overdue_days": { "type": "integer", "description": "Minimum overdue days. Default 1." }
                  }
                }
              },
              {
                "name": "get_stock_spec_map",
                "description": "MUST call this before any stock query. Returns the mapping of spec_value_N columns to human-readable labels for this company. e.g. {spec_value_1: 'Color', spec_value_2: 'Chassis Number'}. Use this to know which column to filter when user asks about color, size, or any specification.",
                "parameters": {
                  "type": "object",
                  "properties": {}
                }
              },
              {
                "name": "query_stock",
                "description": "Query stock items with filters based on spec labels (not column names). Use AFTER calling get_stock_spec_map. Omit group_by_label to get full per-row detail (item_name, quantity, and every mapped specification column under its label — use this for 'full stock details'). Set group_by_label (e.g. 'item_name' or 'Color') to get an aggregated summary instead — returns only the group column plus total quantity and item count, with NO specification columns (use this for 'stock summary' / totals).",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "filters": {
                      "type": "array",
                      "description": "List of filter conditions using human label names (e.g. 'Color') or fixed columns (item_name, quantity). Each has label, operator, value.",
                      "items": {
                        "type": "object",
                        "properties": {
                          "label":    { "type": "string", "description": "Spec label (e.g. 'Color') or fixed column (item_name, quantity)" },
                          "operator": { "type": "string", "description": "=, !=, LIKE, >, <, >=, <=, IN" },
                          "value":    { "type": "string", "description": "Filter value" }
                        },
                        "required": ["label", "operator", "value"]
                      }
                    },
                    "group_by_label": { "type": "string", "description": "Spec label or column to group by. e.g. 'Color' to get count per color." },
                    "order_by_label": { "type": "string", "description": "Spec label or column to sort by. e.g. 'quantity'." },
                    "sort_direction": { "type": "string", "description": "ASC or DESC. Default DESC." },
                    "limit":          { "type": "integer", "description": "Max rows. Default 200." }
                  }
                }
              },
              {
                "name": "search_stock_by_spec_value",
                "description": "Search across ALL spec columns for a value. Use when user asks to 'find RED' or 'search for chassis number X' and you are not sure which spec column contains that value. Searches only mapped columns for this company.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "search_value": { "type": "string", "description": "Value to search for across all spec columns." }
                  },
                  "required": ["search_value"]
                }
              },
              {
                "name": "get_dispatch_summary",
                "description": "Get a high-level dispatch summary for a time period. Returns total vehicles dispatched, unique parties served (each counted once regardless of invoice count), and breakdown by item. Use for 'today dispatch', 'month dispatch', 'total dispatch', 'how many vehicles dispatched'. For a specific month like 'May dispatch' or 'April dispatch', use period='month' and set month (1-12) and year (e.g. current year). No spec map needed for this tool.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "period": {
                      "type": "string",
                      "enum": ["today", "month", "all"],
                      "description": "'today' = today only | 'month' = a calendar month (use month+year for specific months, omit for current month) | 'all' = all-time total"
                    },
                    "month": {
                      "type": "integer",
                      "description": "Month number 1-12. Required when the user asks for a specific month like May (5) or April (4). Omit for current month."
                    },
                    "year": {
                      "type": "integer",
                      "description": "4-digit year. Provide alongside month for a specific month. Omit to default to the current year."
                    }
                  },
                  "required": ["period"]
                }
              },
              {
                "name": "query_dispatch",
                "description": "Query dispatch records with optional filters, grouping, and ordering. Use AFTER calling get_stock_spec_map (dispatch uses the same spec map as stock for this company). Omit group_by_label for full per-row detail (item_name, party_name, invoice_no, invoice_date, quantity, and every populated+mapped spec column under its proper label). Set group_by_label to aggregate. Spec values that are empty or '--' are hidden automatically.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "filters": {
                      "type": "array",
                      "description": "Optional filters. Each item: {label: column or spec label, operator: '='|'!='|'LIKE'|'>'|'<'|'>='|'<='|'IN', value: string}. Date filter example: {label:'invoice_date', operator:'>=', value:'2026-04-01'}",
                      "items": {
                        "type": "object",
                        "properties": {
                          "label":    { "type": "string" },
                          "operator": { "type": "string" },
                          "value":    { "type": "string" }
                        }
                      }
                    },
                    "group_by_label": { "type": "string", "description": "Column or spec label to group by (e.g. 'item_name', 'party_name', 'Color'). Omit for full detail rows." },
                    "order_by_label": { "type": "string", "description": "Column or spec label to order by (e.g. 'invoice_date', 'quantity', 'total_quantity')." },
                    "sort_direction": { "type": "string", "enum": ["ASC", "DESC"], "description": "Sort direction." },
                    "limit":          { "type": "integer", "description": "Max rows to return. Default 500." }
                  }
                }
              },
              {
                "name": "search_dispatch_by_spec_value",
                "description": "Search across ALL mapped dispatch spec columns for a value — uses ai_stock_spec_map for the column mapping. Use when user asks to find a specific chassis number, motor number, controller number, battery model, or any other spec value in dispatched vehicles. Call get_stock_spec_map first.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "search_value": { "type": "string", "description": "Value to search for across all dispatch spec columns (e.g. a chassis number, motor number)." }
                  },
                  "required": ["search_value"]
                }
              }
            ]
          }
        ]
        """;
        return mapper.readTree(toolsJson);
    }

    // ─── Main chat entry point ────────────────────────────────────────────────
    public String chat(String userMessage,
                       List<Map<String, Object>> conversationHistory,
                       String language,
                       String companyId) {
        String langTag = "hi".equalsIgnoreCase(language) ? "[LANG:HI] " : "[LANG:EN] ";
        userMessage = langTag + userMessage;
        try {
            ObjectNode requestBody = buildRequestBody(userMessage, conversationHistory, true);
            String responseStr = callGemini(requestBody);
            JsonNode response  = mapper.readTree(responseStr);
            return processResponse(response, userMessage, conversationHistory, 0, companyId);
        } catch (Exception e) {
            log.error("[Gemini] Chat error: {}", e.getMessage());
            return "Sorry, I encountered an error: " + e.getMessage();
        }
    }

    // ─── Process response (supports chained tool calls, capped at MAX_TOOL_DEPTH) ─
    private static final int MAX_TOOL_DEPTH = 4;

    private String processResponse(JsonNode response,
                                   String originalMessage,
                                   List<Map<String, Object>> history,
                                   int depth,
                                   String companyId) throws Exception {
        if (depth > MAX_TOOL_DEPTH) {
            // Safety cap reached — return whatever text we have rather than looping forever
            JsonNode cands = response.path("candidates");
            if (!cands.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : cands.get(0).path("content").path("parts")) {
                    if (part.has("text")) sb.append(part.get("text").asText());
                }
                String t = sb.toString().trim();
                return t.isEmpty() ? "No data found for the requested query." : t;
            }
            return "No data found for the requested query.";
        }

        JsonNode candidates = response.path("candidates");
        if (candidates.isEmpty()) {
            log.error("[Gemini] No candidates in response");
            return "No response from Gemini.";
        }

        JsonNode parts      = candidates.get(0).path("content").path("parts");
        String finishReason = candidates.get(0).path("finishReason").asText("");
        log.info("[Gemini] depth={} finishReason={}", depth, finishReason);

        StringBuilder textParts = new StringBuilder();
        List<JsonNode> toolCalls = new ArrayList<>();

        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                toolCalls.add(part.get("functionCall"));
                log.info("[Gemini] Tool: {} args={}", part.get("functionCall").path("name").asText(),
                    part.get("functionCall").path("args").toString());
            } else if (part.has("text")) {
                textParts.append(part.get("text").asText());
            }
        }

        // No tool calls → return text directly
        if (toolCalls.isEmpty()) {
            return textParts.toString().trim();
        }

        // Build full conversation: history + user message + model tool call + tool results
        ArrayNode contents = buildContentsWithHistory(originalMessage, history);

        // Model turn (tool call)
        ObjectNode modelTurn = mapper.createObjectNode();
        modelTurn.put("role", "model");
        ArrayNode modelParts = mapper.createArrayNode();
        for (JsonNode tc : toolCalls) {
            ObjectNode p = mapper.createObjectNode();
            p.set("functionCall", tc);
            modelParts.add(p);
        }
        modelTurn.set("parts", modelParts);
        contents.add(modelTurn);

        // Tool result turn
        ObjectNode resultTurn = mapper.createObjectNode();
        resultTurn.put("role", "user");
        ArrayNode resultParts = mapper.createArrayNode();
        for (JsonNode tc : toolCalls) {
            String funcName = tc.get("name").asText();
            String result   = executeFunctionCall(funcName, tc.path("args"), companyId);
            log.info("[Gemini] Tool result size={}chars", result.length());

            ObjectNode fp = mapper.createObjectNode();
            ObjectNode fr = mapper.createObjectNode();
            fr.put("name", funcName);
            ObjectNode rn = mapper.createObjectNode();
            rn.put("result", result);
            fr.set("response", rn);
            fp.set("functionResponse", fr);
            resultParts.add(fp);
        }
        resultTurn.set("parts", resultParts);
        contents.add(resultTurn);

        // Follow-up call — tools included so Gemini can chain (e.g. get_stock_spec_map → query_stock)
        ObjectNode followUp = mapper.createObjectNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.set("parts", mapper.createArrayNode().add(
            mapper.createObjectNode().put("text", buildSystemPrompt())));
        followUp.set("systemInstruction", sys);
        followUp.set("contents", contents);
        followUp.set("tools", buildToolDefinitions());
        ObjectNode cfg = mapper.createObjectNode();
        cfg.put("temperature", 0.1);
        cfg.put("maxOutputTokens", 65536);
        followUp.set("generationConfig", cfg);

        String fr2 = callGemini(followUp);
        return processResponse(mapper.readTree(fr2), originalMessage, history, depth + 1, companyId);
    }

    // ─── Build request body ───────────────────────────────────────────────────
    private ObjectNode buildRequestBody(String userMessage,
                                        List<Map<String, Object>> history,
                                        boolean includeTools) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode sys = mapper.createObjectNode();
        sys.set("parts", mapper.createArrayNode().add(
            mapper.createObjectNode().put("text", buildSystemPrompt())));
        body.set("systemInstruction", sys);
        body.set("contents", buildContentsWithHistory(userMessage, history));
        if (includeTools) body.set("tools", buildToolDefinitions());
        ObjectNode cfg = mapper.createObjectNode();
        cfg.put("temperature", 0.1);
        cfg.put("maxOutputTokens", 65536);
        body.set("generationConfig", cfg);
        return body;
    }

    private ArrayNode buildContentsWithHistory(String userMessage,
                                               List<Map<String, Object>> history) {
        ArrayNode contents = mapper.createArrayNode();

        // Keep only last 10 turns to prevent context overflow
        List<Map<String, Object>> trimmed = history;
        if (history.size() > 10) {
            trimmed = history.subList(history.size() - 10, history.size());
        }

        for (Map<String, Object> turn : trimmed) {
            ObjectNode content = mapper.createObjectNode();
            content.put("role", (String) turn.get("role"));
            ArrayNode parts = mapper.createArrayNode();
            parts.add(mapper.createObjectNode().put("text", (String) turn.get("text")));
            content.set("parts", parts);
            contents.add(content);
        }
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode userParts = mapper.createArrayNode();
        userParts.add(mapper.createObjectNode().put("text", userMessage));
        userContent.set("parts", userParts);
        contents.add(userContent);
        return contents;
    }

    // ─── Execute function calls ───────────────────────────────────────────────
    private String executeFunctionCall(String funcName, JsonNode args, String companyId) {
        return switch (funcName) {
            case "query_table" -> {
                List<Map<String, String>> filters = new ArrayList<>();
                JsonNode filtersNode = args.path("filters");
                if (filtersNode.isArray()) {
                    for (JsonNode f : filtersNode) {
                        Map<String, String> filter = new HashMap<>();
                        filter.put("column",   f.path("column").asText(""));
                        filter.put("operator", f.path("operator").asText("="));
                        filter.put("value",    f.path("value").asText(""));
                        filters.add(filter);
                    }
                }
                yield erpFunctions.queryTable(
                    args.path("table_name").asText(""),
                    filters,
                    args.path("select_columns").asText("all"),
                    args.path("group_by").asText(null),
                    args.path("order_by").asText(null),
                    args.path("sort_direction").asText("DESC"),
                    args.path("limit").asInt(2000),
                    companyId
                );
            }
            case "get_table_schema"    -> erpFunctions.getTableSchema(args.path("table_name").asText("all"));
            case "get_today_summary"   -> erpFunctions.getTodaySummary(companyId);
            case "get_overdue_parties" -> erpFunctions.getOverdueParties(args.path("min_overdue_days").asInt(1), companyId);
            case "get_stock_spec_map"  -> stockFunctions.getSpecMap(companyId);
            case "query_stock" -> {
                List<Map<String, String>> filters = new ArrayList<>();
                JsonNode filtersNode = args.path("filters");
                if (filtersNode.isArray()) {
                    for (JsonNode f : filtersNode) {
                        Map<String, String> filter = new HashMap<>();
                        filter.put("label",    f.path("label").asText(""));
                        filter.put("operator", f.path("operator").asText("="));
                        filter.put("value",    f.path("value").asText(""));
                        filters.add(filter);
                    }
                }
                yield stockFunctions.queryStock(
                    companyId,
                    filters,
                    args.path("group_by_label").asText(null),
                    args.path("order_by_label").asText(null),
                    args.path("sort_direction").asText("DESC"),
                    args.path("limit").asInt(200)
                );
            }
            case "search_stock_by_spec_value" ->
                stockFunctions.searchBySpecValue(companyId, args.path("search_value").asText(""));
            case "get_dispatch_summary" ->
                dispatchFunctions.getDispatchSummary(
                    companyId,
                    args.path("period").asText("all"),
                    args.path("month").asInt(0),
                    args.path("year").asInt(0)
                );
            case "query_dispatch" -> {
                List<Map<String, String>> filters = new ArrayList<>();
                JsonNode filtersNode = args.path("filters");
                if (filtersNode.isArray()) {
                    for (JsonNode f : filtersNode) {
                        Map<String, String> filter = new HashMap<>();
                        filter.put("label",    f.path("label").asText(""));
                        filter.put("operator", f.path("operator").asText("="));
                        filter.put("value",    f.path("value").asText(""));
                        filters.add(filter);
                    }
                }
                yield dispatchFunctions.queryDispatch(
                    companyId,
                    filters,
                    args.path("group_by_label").asText(null),
                    args.path("order_by_label").asText(null),
                    args.path("sort_direction").asText("DESC"),
                    args.path("limit").asInt(500)
                );
            }
            case "search_dispatch_by_spec_value" ->
                dispatchFunctions.searchDispatchBySpecValue(companyId, args.path("search_value").asText(""));
            default -> "Function '" + funcName + "' not found.";
        };
    }

    // ─── HTTP call to Gemini ──────────────────────────────────────────────────
    private String callGemini(ObjectNode body) {
        String url = apiUrl + "?key=" + apiKey;
        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    resp -> resp.bodyToMono(String.class)
                        .map(b -> new RuntimeException("Gemini error " + resp.statusCode() + ": " + b)))
                .bodyToMono(String.class)
                .block();
    }
}