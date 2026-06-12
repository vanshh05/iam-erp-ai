package com.iamerp.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.iamerp.ai.functions.ErpFunctions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final ErpFunctions erpFunctions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient = WebClient.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build();

    public GeminiService(ErpFunctions erpFunctions) {
        this.erpFunctions = erpFunctions;
    }

    // ─── System Prompt ────────────────────────────────────────────────────────
    private String buildSystemPrompt() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr     = today.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy"));
        String todayIso     = today.toString();
        String currentMonth = today.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
        String monthStart   = today.withDayOfMonth(1).toString();
        String monthEnd     = today.toString();
        String prevMonthStart = today.minusMonths(1).withDayOfMonth(1).toString();
        String prevMonthEnd   = today.minusMonths(1)
                                     .withDayOfMonth(today.minusMonths(1).lengthOfMonth()).toString();
        String yearStart    = today.withDayOfMonth(1).withMonth(1).toString();
        int    fy_start_yr  = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        String fyStart      = fy_start_yr + "-04-01";
        String fyEnd        = (fy_start_yr + 1) + "-03-31";

        return "You are a strict ERP data assistant for Ayaam ERP by Datawheel Software Technology LLP.\n"
             + "Your ONLY job is to fetch and present ERP data EXACTLY as returned by the tools.\n\n"
             + "=== DATABASE SCHEMA (7 TABLES) ===\n"
             + "\n"
             + "TABLE: ai_daily_summary  — pre-aggregated daily totals, fastest for date-based summaries\n"
             + "  summary_date DATE\n"
             + "  total_sales, cash_sales, credit_sales DECIMAL\n"
             + "  total_bills_count INT\n"
             + "  total_collection DECIMAL    ← total receipts from customers (cash_collection + bank_collection)\n"
             + "  cash_collection DECIMAL     ← cash receipts\n"
             + "  bank_collection DECIMAL     ← bank/online receipts\n"
             + "  total_payment DECIMAL       ← total payments to suppliers (cash_payment + bank_payment)\n"
             + "  cash_payment DECIMAL        ← cash payments made\n"
             + "  bank_payment DECIMAL        ← bank/online payments made\n"
             + "  total_receivable DECIMAL, total_payable DECIMAL\n"
             + "  total_discount_given DECIMAL\n"
             + "  total_purchase DECIMAL      ← total purchase (cash_purchase + credit_purchase)\n"
             + "  cash_purchase DECIMAL, credit_purchase DECIMAL\n"
             + "\n"
             + "TABLE: ai_sales  — individual sales bills/invoices\n"
             + "  bill_no VARCHAR, bill_date DATE, voucher_date TIME, party_name\n"
             + "  gross_amount, discount_amount\n"
             + "  sgst_amount, cgst_amount, igst_amount  ← GST breakdown\n"
             + "  invoice_amount  ← final bill value (use this for bill totals)\n"
             + "  taxable_amount, payment_mode (Cash/Credit), created_by\n"
             + "\n"
             + "TABLE: ai_purchase  — individual purchase bills (same structure as ai_sales)\n"
             + "  bill_no VARCHAR, bill_date DATE, voucher_date TIME, party_name\n"
             + "  gross_amount, discount_amount\n"
             + "  sgst_amount, cgst_amount, igst_amount\n"
             + "  invoice_amount  ← final purchase bill value\n"
             + "  taxable_amount, payment_mode (Cash/Credit)\n"
             + "\n"
             + "TABLE: ai_collection_summary  — individual collection/receipt transactions\n"
             + "  receipt_date DATE\n"
             + "  party_name     ← who paid\n"
             + "  receipt_mode   ← 'Bank' or 'Cash'\n"
             + "  cheque_txn_no  ← cheque or transaction number\n"
             + "  amount DECIMAL ← amount received\n"
             + "\n"
             + "TABLE: ai_payment_summary  — individual payment transactions\n"
             + "  payment_date DATE\n"
             + "  party_name     ← who was paid\n"
             + "  payment_mode   ← 'Bank' or 'Cash'\n"
             + "  cheque_txn_no  ← cheque or transaction number\n"
             + "  amount DECIMAL ← amount paid\n"
             + "\n"
             + "TABLE: ai_bank_balance  — current bank/finance account balances\n"
             + "  bank_name      ← full account name e.g. 'HDFC Bank- CC-50200032245968 (01766)'\n"
             + "  balance_type   ← 'Debit', 'Credit', or NULL\n"
             + "  balance DECIMAL← current balance (0.00 if NULL balance_type means zero)\n"
             + "\n"
             + "TABLE: ai_party_status  — customer/supplier master data\n"
             + "  party_name, party_type ('Customer'/'Supplier'), sub_party_type ('Buyer'/'Dealer'/etc)\n"
             + "  contact_number, email, address, gstin\n"
             + "  outstanding_balance, balance_type (DR=they owe us / CR=we owe them)\n"
             + "  last_bill_date, last_bill_amount, total_business_ytd, credit_limit, overdue_days\n"
             + "\n"
             + "=== CRITICAL COLUMN RULES ===\n"
             + "COLLECTIONS (money IN):\n"
             + "  For daily totals  → ai_daily_summary: total_collection / cash_collection / bank_collection\n"
             + "  For individual txn → ai_collection_summary: amount, receipt_mode, receipt_date, party_name, cheque_txn_no\n"
             + "  → 'collections from Raj Traders' → ai_collection_summary, filter party_name LIKE 'Raj'\n"
             + "  → 'bank collection on 1st June' → ai_collection_summary, filter receipt_date=date AND receipt_mode='Bank'\n"
             + "  → 'total collection this week' → ai_daily_summary, filter summary_date BETWEEN dates, SUM(total_collection)\n"
             + "\n"
             + "PAYMENTS (money OUT):\n"
             + "  For daily totals  → ai_daily_summary: total_payment / cash_payment / bank_payment\n"
             + "  For individual txn → ai_payment_summary: amount, payment_mode, payment_date, party_name, cheque_txn_no\n"
             + "  → 'payments to Raj Traders' → ai_payment_summary, filter party_name LIKE 'Raj'\n"
             + "  → 'cash payments in April' → ai_payment_summary, filter payment_date BETWEEN dates AND payment_mode='Cash'\n"
             + "  NEVER confuse payment with collection\n"
             + "\n"
             + "SALES:\n"
             + "  For daily totals → ai_daily_summary: total_sales / cash_sales / credit_sales\n"
             + "  For individual bills → ai_sales: invoice_amount, bill_no, bill_date, party_name, payment_mode\n"
             + "  → 'sales bill of Raj Traders' → ai_sales, filter party_name LIKE 'Raj'\n"
             + "  → 'bill number GST-001' → ai_sales, filter bill_no='GST-001'\n"
             + "\n"
             + "PURCHASE:\n"
             + "  For daily totals → ai_daily_summary: total_purchase / cash_purchase / credit_purchase\n"
             + "  For individual bills → ai_purchase: invoice_amount, bill_no, bill_date, party_name, payment_mode\n"
             + "  → 'purchase from Raj Traders' → ai_purchase, filter party_name LIKE 'Raj'\n"
             + "\n"
             + "BANK BALANCE:\n"
             + "  → ai_bank_balance, filter bank_name LIKE 'HDFC' for a specific bank\n"
             + "  → 'all bank balances' → ai_bank_balance, no filter, order_by balance DESC\n"
             + "  → balance_type 'Credit' = bank owes us (positive), 'Debit' = we owe bank (overdraft/loan)\n"
             + "\n"
             + "DATE FILTERING RULES:\n"
             + "  → 'today' → filter date_col = TODAY\n"
             + "  → 'this week' → filter date_col BETWEEN (today-7 days) AND today\n"
             + "  → 'this month' → filter date_col BETWEEN (first of month) AND today\n"
             + "  → 'last month' → filter date_col BETWEEN (first of last month) AND (last of last month)\n"
             + "  → 'in April' / 'April 2026' → filter date_col BETWEEN 2026-04-01 AND 2026-04-30\n"
             + "  → 'financial year' → filter date_col BETWEEN FY_START AND FY_END\n"
             + "  Use the correct date column per table: summary_date, bill_date, receipt_date, payment_date\n"
             + "\n"
             + "PARTY TYPE:\n"
             + "  party_type = 'Customer' or 'Supplier'\n"
             + "  sub_party_type = 'Buyer', 'Dealer', 'Supplier' etc.\n"
             + "\n"
             + "=== HOW TO USE TOOLS ===\n"
             + "PRIMARY TOOL: query_table — use for all data retrieval.\n"
             + "SECONDARY: get_table_schema — call first if unsure of column names.\n"
             + "CONVENIENCE: get_today_summary, get_overdue_parties — for those specific use cases.\n"
             + "\n"
             + "TABLE SELECTION GUIDE:\n"
             + "  User asks about...                    → Use this table\n"
             + "  Daily totals (sales/purchase/etc)     → ai_daily_summary\n"
             + "  Individual sales bill / by party      → ai_sales\n"
             + "  Individual purchase bill / by party   → ai_purchase\n"
             + "  Individual receipt / who paid us      → ai_collection_summary\n"
             + "  Individual payment / who we paid      → ai_payment_summary\n"
             + "  Bank balance / account balance        → ai_bank_balance\n"
             + "  Party details / contact / GSTIN       → ai_party_status\n"
             + "\n"
             + "FILTER OPERATORS:\n"
             + "  =        exact match (use for IDs, modes, types, specific dates)\n"
             + "  LIKE     partial name match (use for party_name, bank_name)\n"
             + "  BETWEEN  date range — value format: 'yyyy-MM-dd,yyyy-MM-dd'\n"
             + "  >=, <=   numeric or date comparison\n"
             + "  IN       multiple values — value format: 'val1,val2,val3'\n"
             + "\n"
             + "AGGREGATION (use select_columns with aggregate functions):\n"
             + "  SUM(amount), COUNT(bill_no), AVG(amount) etc.\n"
             + "  Combine with group_by for breakdowns e.g. group_by=receipt_mode for cash vs bank split\n"
             + "\n"
             + "ALWAYS use select_columns='all' for ai_party_status queries.\n"
             + "For ai_bank_balance, always fetch all columns — it's a small table.\n"
             + "\n"
             + "=== DATE REFERENCE ===\n"
             + "Today           = " + todayStr + " (" + todayIso + ")\n"
             + "This month      = " + monthStart + " to " + monthEnd + "\n"
             + "Previous month  = " + prevMonthStart + " to " + prevMonthEnd + "\n"
             + "This year (cal) = " + yearStart + " to " + todayIso + "\n"
             + "Financial year  = " + fyStart + " to " + fyEnd + "\n\n"
             + "=== LARGE DATA HANDLING ===\n"
             + "When a user asks for 'all parties', 'all suppliers', 'all customers', 'give me all details', 'complete list',\n"
             + "'all collections', 'all payments', 'all purchases', 'all bank balances' or any query\n"
             + "that would return more than 50 rows, DO NOT output the table yourself.\n"
             + "Instead respond ONLY with this exact JSON on a single line and nothing else:\n"
             + "{\"__export__\":true,\"table\":\"TABLE_NAME\",\"filter_col\":\"COLUMN_OR_EMPTY\",\"filter_val\":\"VALUE_OR_EMPTY\",\"order_by\":\"COLUMN_OR_EMPTY\",\"label\":\"HUMAN READABLE DESCRIPTION\"}\n"
             + "Examples:\n"
             + "  All suppliers:          {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"party_type\",\"filter_val\":\"Supplier\",\"order_by\":\"party_name\",\"label\":\"All Suppliers\"}\n"
             + "  All parties:            {\"__export__\":true,\"table\":\"ai_party_status\",\"filter_col\":\"\",\"filter_val\":\"\",\"order_by\":\"party_name\",\"label\":\"All Parties\"}\n"
             + "  All bank balances:      {\"__export__\":true,\"table\":\"ai_bank_balance\",\"filter_col\":\"\",\"filter_val\":\"\",\"order_by\":\"balance\",\"label\":\"All Bank Balances\"}\n"
             + "  All collections April:  {\"__export__\":true,\"table\":\"ai_collection_summary\",\"filter_col\":\"receipt_date\",\"filter_val\":\"2026-04-01,2026-04-30\",\"order_by\":\"receipt_date\",\"label\":\"Collections April 2026\"}\n"
             + "  All payments April:     {\"__export__\":true,\"table\":\"ai_payment_summary\",\"filter_col\":\"payment_date\",\"filter_val\":\"2026-04-01,2026-04-30\",\"order_by\":\"payment_date\",\"label\":\"Payments April 2026\"}\n"
             + "  All purchase bills:     {\"__export__\":true,\"table\":\"ai_purchase\",\"filter_col\":\"\",\"filter_val\":\"\",\"order_by\":\"bill_date\",\"label\":\"All Purchase Bills\"}\n"
             + "The frontend will intercept this JSON and handle the download directly.\n\n"
             + "=== PARTY STATUS QUERIES — READ CAREFULLY ===\n"
             + "- For ANY question about a party/supplier/customer → ALWAYS use query_table on ai_party_status\n"
             + "- ALWAYS set select_columns='all' when querying ai_party_status — NEVER restrict to specific columns\n"
             + "  Even if user asks ONLY for contact number, address, GSTIN, email, or any single field:\n"
             + "  → still fetch ALL columns (select_columns='all'), then highlight the requested field in your reply\n"
             + "  → NEVER set select_columns='contact_number' or any subset — it causes query failures\n"
             + "- Filter by party_name LIKE <partial_name> — use the key part of the name, not the full name\n"
             + "  Example: user asks about 'Raj Traders Delhi' → filter party_name LIKE 'Raj Traders'\n"
             + "  Example: user asks about 'Anand Trading' → filter party_name LIKE 'Anand'\n"
             + "- Filter by party_type = 'Supplier' or party_type = 'Customer' when user specifies type\n"
             + "- If query returns multiple matches, show all of them in a table\n"
             + "- If result is empty, try again with a shorter/simpler version of the party name\n"
             + "- If still empty, say 'No party found matching that name'\n\n"
             + "=== STRICT RULES ===\n"
             + "- NEVER hallucinate data — return exactly what the tool provides\n"
             + "- NEVER add dates to a query unless the user specifically asked for a date filter\n"
             + "- NEVER summarize or truncate tool results — show every row returned\n"
             + "- NEVER say 'here are the first few' — if you got data, show it all\n"
             + "- If the tool returns 'No data found' — report that truthfully, do not guess\n"
             + "- Stock data is not available — say so if asked\n"
             + "- ONLY answer ERP data questions\n\n"
             + "=== LANGUAGE ===\n"
             + "- Message starts with [LANG:EN] → respond in English only\n"
             + "- Message starts with [LANG:HI] → respond in Hindi only\n"
             + "- Never mix languages\n\n"
             + "=== OUTPUT FORMAT ===\n"
             + "- Currency: use ₹ with Indian number format (e.g. ₹1,24,500.00)\n"
             + "- Dates: DD MMM YYYY format (e.g. 01 Jun 2026)\n"
             + "- Multiple rows: clean markdown table with headers\n"
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
                "description": "Universal query tool. Use this to fetch ANY data from the ERP database. Supports filters on any column, sorting, grouping, and selecting specific columns. Use this for: bills by bill_no, bills by party name, sales/purchase/collection/payment for any date or date range, cash vs credit sales, party search, any custom query.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "table_name": {
                      "type": "string",
                      "description": "Table to query. Must be one of: ai_daily_summary, ai_sales, ai_purchase, ai_collection_summary, ai_payment_summary, ai_bank_balance, ai_party_status"
                    },
                    "filters": {
                      "type": "array",
                      "description": "List of filter conditions (do NOT include company_id — it is added automatically). Each filter has column, operator, value.",
                      "items": {
                        "type": "object",
                        "properties": {
                          "column":   { "type": "string", "description": "Exact column name from the table schema" },
                          "operator": { "type": "string", "description": "One of: =, !=, >, <, >=, <=, LIKE, BETWEEN, IN. Use LIKE for partial name match. Use BETWEEN for date ranges with value='start,end'." },
                          "value":    { "type": "string", "description": "Filter value. For BETWEEN use 'yyyy-MM-dd,yyyy-MM-dd'. For IN use comma-separated values." }
                        },
                        "required": ["column", "operator", "value"]
                      }
                    },
                    "select_columns": {
                      "type": "string",
                      "description": "Comma-separated column names to return, or 'all'. IMPORTANT: For ai_party_status, ALWAYS use 'all' regardless of what field the user asked for — fetch everything and highlight the relevant field in your response. Only restrict columns for ai_daily_summary or ai_sales when you need specific aggregates. Example for ai_daily_summary: 'summary_date,total_payment,total_collection'. Never restrict ai_party_status columns."
                    },
                    "group_by": {
                      "type": "string",
                      "description": "Column to group results by. Optional. Example: 'payment_mode' to group bills by cash/credit."
                    },
                    "order_by": {
                      "type": "string",
                      "description": "Column to sort results by. Optional."
                    },
                    "sort_direction": {
                      "type": "string",
                      "description": "ASC or DESC. Default DESC."
                    },
                    "limit": {
                      "type": "integer",
                      "description": "Maximum number of rows to return. Default 200, max 2000. When user says 'all', 'complete list', 'सभी', 'पूरी list' or similar — always set this to 2000 to ensure all records are returned."
                    }
                  },
                  "required": ["table_name"]
                }
              },
              {
                "name": "get_table_schema",
                "description": "Get the exact column names available in a table. Call this if you are unsure of a column name before running query_table. Pass 'all' to see all 7 tables. Available tables: ai_daily_summary, ai_sales, ai_purchase, ai_collection_summary, ai_payment_summary, ai_bank_balance, ai_party_status",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "table_name": {
                      "type": "string",
                      "description": "Table name, or 'all' to see all tables and columns."
                    }
                  },
                  "required": ["table_name"]
                }
              },
              {
                "name": "get_today_summary",
                "description": "Get today's complete summary: sales, bills, cash/credit split, collection, payment, purchase. Fastest way to get today's overview.",
                "parameters": {
                  "type": "object",
                  "properties": {}
                }
              },
              {
                "name": "get_overdue_parties",
                "description": "Get all parties/customers with overdue payments (balance_type DR and overdue_days > 0).",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "min_overdue_days": {
                      "type": "integer",
                      "description": "Minimum overdue days threshold. Default 1."
                    }
                  }
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
                       String language) {
        String langTag = "hi".equalsIgnoreCase(language) ? "[LANG:HI] " : "[LANG:EN] ";
        userMessage = langTag + userMessage;

        try {
            ObjectNode requestBody = buildRequestBody(userMessage, conversationHistory, true);
            String responseStr = callGemini(requestBody);
            JsonNode response = mapper.readTree(responseStr);
            return processResponse(response, userMessage, conversationHistory, 0);
        } catch (Exception e) {
            return "Sorry, I encountered an error: " + e.getMessage();
        }
    }

    // ─── Process Gemini response (handles multi-turn tool calls) ─────────────
    private String processResponse(JsonNode response,
                                   String originalMessage,
                                   List<Map<String, Object>> history,
                                   int depth) throws Exception {
        if (depth > 3) return "Maximum tool call depth reached.";

        JsonNode candidates = response.path("candidates");
        if (candidates.isEmpty()) return "No response from Gemini.";

        JsonNode content = candidates.get(0).path("content");
        JsonNode parts   = content.path("parts");

        // Collect any text parts first
        StringBuilder textParts = new StringBuilder();
        List<JsonNode> toolCalls = new ArrayList<>();

        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                toolCalls.add(part.get("functionCall"));
            } else if (part.has("text")) {
                textParts.append(part.get("text").asText());
            }
        }

        // If no tool calls, return the text
        if (toolCalls.isEmpty()) {
            return textParts.toString();
        }

        // Execute all tool calls and build function response turn
        ArrayNode contentsWithTools = buildContentsWithHistory(originalMessage, history);

        // Add the model's function call turn
        ObjectNode modelTurn = mapper.createObjectNode();
        modelTurn.put("role", "model");
        ArrayNode modelParts = mapper.createArrayNode();
        for (JsonNode tc : toolCalls) {
            ObjectNode p = mapper.createObjectNode();
            p.set("functionCall", tc);
            modelParts.add(p);
        }
        modelTurn.set("parts", modelParts);
        contentsWithTools.add(modelTurn);

        // Add function results turn
        ObjectNode funcResultTurn = mapper.createObjectNode();
        funcResultTurn.put("role", "user");
        ArrayNode funcResultParts = mapper.createArrayNode();
        for (JsonNode tc : toolCalls) {
            String funcName = tc.get("name").asText();
            JsonNode args   = tc.path("args");
            String result   = executeFunctionCall(funcName, args);

            ObjectNode fp = mapper.createObjectNode();
            ObjectNode fr = mapper.createObjectNode();
            fr.put("name", funcName);
            ObjectNode responseNode = mapper.createObjectNode();
            responseNode.put("result", result);
            fr.set("response", responseNode);
            fp.set("functionResponse", fr);
            funcResultParts.add(fp);
        }
        funcResultTurn.set("parts", funcResultParts);
        contentsWithTools.add(funcResultTurn);

        // Build follow-up request with tool results
        ObjectNode followUpBody = mapper.createObjectNode();
        ObjectNode sysInstr = mapper.createObjectNode();
        sysInstr.set("parts", mapper.createArrayNode().add(
            mapper.createObjectNode().put("text", buildSystemPrompt())));
        followUpBody.set("systemInstruction", sysInstr);
        followUpBody.set("contents", contentsWithTools);
        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 65536);
        followUpBody.set("generationConfig", genConfig);

        String followUpStr = callGemini(followUpBody);
        JsonNode followUp  = mapper.readTree(followUpStr);

        // Recurse in case Gemini wants to make another tool call
        return processResponse(followUp, originalMessage, history, depth + 1);
    }

    // ─── Build request body ───────────────────────────────────────────────────
    private ObjectNode buildRequestBody(String userMessage,
                                        List<Map<String, Object>> history,
                                        boolean includeTools) throws Exception {
        ObjectNode body = mapper.createObjectNode();

        ObjectNode sysInstr = mapper.createObjectNode();
        sysInstr.set("parts", mapper.createArrayNode().add(
            mapper.createObjectNode().put("text", buildSystemPrompt())));
        body.set("systemInstruction", sysInstr);

        ArrayNode contents = buildContentsWithHistory(userMessage, history);
        body.set("contents", contents);

        if (includeTools) body.set("tools", buildToolDefinitions());

        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 65536);
        body.set("generationConfig", genConfig);

        return body;
    }

    private ArrayNode buildContentsWithHistory(String userMessage,
                                               List<Map<String, Object>> history) {
        ArrayNode contents = mapper.createArrayNode();
        for (Map<String, Object> turn : history) {
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
    private String executeFunctionCall(String funcName, JsonNode args) {
        String companyId = "HOTAGE INDIA";
        return switch (funcName) {
            case "query_table" -> {
                // Parse filters array
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
                    args.path("limit").asInt(200),
                    companyId
                );
            }
            case "get_table_schema" ->
                erpFunctions.getTableSchema(args.path("table_name").asText("all"));
            case "get_today_summary" ->
                erpFunctions.getTodaySummary(companyId);
            case "get_overdue_parties" ->
                erpFunctions.getOverdueParties(args.path("min_overdue_days").asInt(1), companyId);
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