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

    /** Returned when Gemini yields no usable text after retries. Callers should treat this
     *  as a failure, not as an answer. */
    public static final String EMPTY_RESULT =
        "The assistant couldn't process that request just now. Please try again.";

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
             + "=== CRITICAL DATA INTEGRITY RULE ===\n"
             + "EVERY value you present — amounts, dates, invoice numbers, party names, chassis/motor/serial\n"
             + "numbers, quantities — MUST come from a tool result in THIS conversation. NEVER invent,\n"
             + "estimate, or construct example rows. If a tool returns an error, say a technical error\n"
             + "occurred and ask the user to retry. If a tool returns no rows, say no records were found\n"
             + "for that criteria. Fabricated business data is catastrophic — an empty answer is always\n"
             + "better than an invented one.\n\n"
             + "=== DATABASE SCHEMA (10 TABLES) ===\n"
             + "\n"
             + "TABLE: ai_daily_summary  — pre-aggregated daily totals (RESERVED: only for total_receivable/total_payable as on TODAY; historical rows unreliable)\n"
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
             + "=== CRM TABLES (sales pipeline) ===\n"
             + "TABLE: ai_customer_info  — POTENTIAL customers whose lead is not yet created (the raw pipeline)\n"
             + "  customer_id, customer_code, customer_date DATE, company_name, customer_name\n"
             + "  user_name, source_of_info, designation, product_cateogory (NOTE: spelled exactly like this), gst_number\n"
             + "  mobile_no, email_id, address, city, state, customer_status, customer_comments\n"
             + "TABLE: ai_lead_info  — CURRENT status of each active lead (exactly ONE row per lead)\n"
             + "  customer_id, lead_id, lead_code, lead_date DATE, priority (Low/Medium/High)\n"
             + "  worked_by (the user who last WORKED this lead), worked_updated_date (when that update happened)\n"
             + "  follow_up_user (the user who is to follow up NEXT — different from worked_by), follow_up_date DATE, meeting_date, meeting_agenda\n"
             + "  lead_status (New/In-Progress/Closed), product_name\n"
             + "  is_price, is_product, is_other  (1 = that aspect discussed AND customer satisfied; 0 = not yet discussed OR not satisfied)\n"
             + "  follow_up_user_remarks, customer_comments\n"
             + "TABLE: ai_lead_event_info  — full HISTORY of a lead: one row per follow-up event (MANY rows per lead), each event carrying worked_by and worked_updated_date. Reached ONLY via get_lead_history, never query_table.\n"
             + "\n"
             + "=== STOCK QUERY RULES ===\n"
             + "- ALWAYS call get_stock_spec_map FIRST before any stock query\n"
             + "- Use the returned map to know which spec_value_N = which label for this company\n"
             + "- For queries like 'how many RED vehicles': get map → find Color column → filter that column = 'RED'\n"
             + "- For queries like 'find chassis number X': get map → find Chassis Number column → filter that column\n"
             + "- For broad searches ('find RED in stock'): use search_stock_by_spec_value tool\n"
             + "- SPEC VALUES CONTAIN EXTRA TEXT — when filtering a spec label (Color, Chassis, etc.) in\n"
             + "  query_stock, use operator 'LIKE' with the keyword only, never '='. A color is stored as\n"
             + "  e.g. 'RED (6X4X18+GRILL) DOUBLE KAMANI', so {label:'Color', operator:'LIKE', value:'RED'}.\n"
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
             + "- For a SPECIFIC DATE or DATE RANGE ('dispatch items on 30 June', 'dispatches last week',\n"
             + "  'what was dispatched yesterday'): FIRST get_stock_spec_map, THEN query_dispatch with a\n"
             + "  date filter, e.g. {label:'invoice_date', operator:'=', value:'2026-06-30'} or two filters\n"
             + "  with >= and <= for a range. NEVER answer a dated dispatch question without this tool call.\n"
             + "- For SPEC SEARCHES ('find motor number X', 'find chassis Y in dispatched vehicles'):\n"
             + "  get_stock_spec_map first → search_dispatch_by_spec_value.\n"
             + "- SPEC VALUES CONTAIN EXTRA TEXT — match them with LIKE, never exact '='. In this data a\n"
             + "  color is stored as e.g. 'RED (6X4X18+GRILL) DOUBLE KAMANI' or 'SKY BLUE SS DLX', not just\n"
             + "  'Red'. So 'red vehicles dispatched on 30 June' → get_stock_spec_map, then query_dispatch\n"
             + "  with filters [{label:'Color', operator:'LIKE', value:'RED'}, {label:'invoice_date',\n"
             + "  operator:'=', value:'2026-06-30'}]. The same applies to every spec label (chassis, motor,\n"
             + "  battery, controller): filter with LIKE + the keyword. Combine a spec LIKE filter with an\n"
             + "  exact invoice_date filter for date+spec questions.\n"
             + "- IMPORTANT: dispatch and stock share the SAME spec map (ai_stock_spec_map) — never call a\n"
             + "  separate dispatch spec map. get_stock_spec_map works for both.\n"
             + "- Dispatch data is ONLY in ai_dispatch_summary — never use query_table for dispatch\n"
             + "\n"
             + "COLLECTIONS (money IN): ALL collection queries → ai_collection_summary\n"
             + "  (receipt_date, party_name, amount, receipt_mode). Totals = SUM(amount); date-wise = GROUP BY receipt_date.\n"
             + "PAYMENTS (money OUT): ALL payment queries → ai_payment_summary\n"
             + "  (payment_date, party_name, amount, payment_mode). Totals = SUM(amount); date-wise = GROUP BY payment_date.\n"
             + "SALES: ALL sales queries → ai_sales. Totals = SUM(invoice_amount); bills count = COUNT(bill_no);\n"
             + "  date-wise/month breakdown = GROUP BY bill_date; details = the row columns. NEVER use ai_daily_summary for sales.\n"
             + "PURCHASE: ALL purchase queries → ai_purchase, exactly like sales. NEVER use ai_daily_summary for purchase.\n"
             + "BANK: → ai_bank_balance_summary (bank_name, balance_type, balance)\n"
             + "ai_daily_summary is reserved for ONE thing only: total_receivable / total_payable as on TODAY\n"
             + "  (summary_date = CURDATE()). Do not use it for sales, purchase, collection, or payment — its\n"
             + "  historical rows are unreliable and will give wrong/missing results.\n"
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
             + "Sales — totals, date-wise, month, details      → ai_sales (SUM/GROUP BY bill_date as needed)\n"
             + "Purchase — totals, date-wise, month, details   → ai_purchase\n"
             + "Collection — totals, date-wise, details        → ai_collection_summary\n"
             + "Payment — totals, date-wise, details           → ai_payment_summary\n"
             + "Total receivable/payable AS ON TODAY (only)    → ai_daily_summary (summary_date = CURDATE())\n"
             + "Bank/account balances                           → ai_bank_balance_summary\n"
             + "Party contact/address/balance/GSTIN             → ai_party_status\n"
             + "Party GENERAL LEDGER / statement / khata        → get_party_ledger tool ONLY (needs a party name; never query_table)\n"
             + "Potential customers / pipeline / not-yet-leads  → ai_customer_info (query_table)\n"
             + "Lead status / count / which leads / open leads  → ai_lead_info (query_table; ONE row per lead)\n"
             + "Lead HISTORY / journey / all follow-ups of a lead → get_lead_history tool ONLY (needs a named lead; never query_table)\n"
             + "Stock / inventory / warehouse / items           → ai_stock_summary (use stock tools, NOT query_table)\n"
             + "\n"
             + "=== PARTY STATUS QUERIES ===\n"
             + "- ALWAYS set select_columns to the full party_status order above\n"
             + "- Filter party_name LIKE <key part of name> — use short keyword not full name\n"
             + "- Filter party_type = 'Customer' or 'Supplier' for type\n"
             + "- Filter sub_party_type = 'Dealer' / 'Buyer' etc for sub-type\n"
             + "- If no result, retry with shorter name keyword\n"
             + "\n"
             + "=== PARTY GENERAL LEDGER (get_party_ledger) ===\n"
             + "- Use ONLY when the user explicitly asks for a party's ledger / general ledger /\n"
             + "  account statement / khata — AND names the party. The party name may appear\n"
             + "  ANYWHERE in the request — before or after the word 'ledger'. ALL of these name\n"
             + "  a party and must call the tool directly:\n"
             + "    'ledger of datawheel'            → get_party_ledger('datawheel')\n"
             + "    'datawheel ledger'               → get_party_ledger('datawheel')\n"
             + "    'datawheel ka khata'             → get_party_ledger('datawheel')\n"
             + "    'show me datawheel general ledger' → get_party_ledger('datawheel')\n"
             + "    'general ledger SM automobiles'  → get_party_ledger('SM automobiles')\n"
             + "  Strip only the generic words (ledger, general, account, statement, khata,\n"
             + "  show, me, of, the, ka) — whatever remains is the party name.\n"
             + "- ONLY if the request contains NO candidate party name at all ('show ledger',\n"
             + "  'general ledger', 'party ka khata') do you ask which party they mean.\n"
             + "- NEVER use this for outstanding balance / receivable / payable / due questions.\n"
             + "  Those ALWAYS come from ai_party_status. The ledger is only for the full\n"
             + "  statement view of one party.\n"
             + "- PRESENTATION (strict): show a table with columns Particulars, Voucher Date,\n"
             + "  Voucher Type, Voucher No, Invoice No, Debit, Credit, Balance — in EXACTLY the\n"
             + "  row order the tool returns. Do NOT re-sort. A ledger can have ANY number of\n"
             + "  entries — a few rows or hundreds. The FIRST row is always the Opening\n"
             + "  Balance; the LAST row (whatever its position) always contains the total debit,\n"
             + "  total credit and closing outstanding balance — label that final row 'Total'\n"
             + "  in the Particulars column (the raw data may show '0.0' there). Every row in\n"
             + "  between is a normal ledger entry and must be shown as-is. Empty/null cells\n"
             + "  stay blank.\n"
             + "- The tool result has NO seq_no field and none must be shown or mentioned.\n"
             + "- MISSPELLED PARTY (no_match with closest_matches): if the tool returns\n"
             + "  no_match=true with a closest_matches list:\n"
             + "  * If the list has EXACTLY ONE name: immediately call get_party_ledger again\n"
             + "    with that exact full name, and present the ledger with a short note like\n"
             + "    'Showing the ledger for <name> (closest match to \\\"<what user typed>\\\").'\n"
             + "  * If it has SEVERAL names: reply with ONLY this marker and nothing else:\n"
             + "    {\"__suggestions__\": [\"Ledger of <full name 1>\", \"Ledger of <full name 2>\"]}\n"
             + "    — one entry per suggested party, using the EXACT full party names from\n"
             + "    closest_matches. The UI renders these as tappable choices; each tap is sent\n"
             + "    back as a query, so every entry must be a self-contained request.\n"
             + "  * If closest_matches is absent/empty: say no party with a similar name has\n"
             + "    ledger data and ask the user to check the name.\n"
             + "- If the result's distinct_party_count is more than 1, the name matched several\n"
             + "  parties: reply with ONLY the {\"__suggestions__\": [...]} marker listing\n"
             + "  'Ledger of <name>' for each of matched_parties so the user picks one —\n"
             + "  do NOT merge ledgers of different parties into one statement.\n"
             + "\n"
             + "=== CRM / LEADS RULES ===\n"
             + "THREE tables, three jobs — do not confuse them:\n"
             + "- ai_customer_info = POTENTIAL customers (pipeline, leads not yet created). Use\n"
             + "  for 'potential customers', 'pipeline', 'prospects', 'customers in <city>',\n"
             + "  'who deals in <product_cateogory>', 'customers from <source>'.\n"
             + "- ai_lead_info = CURRENT state of each lead, ONE row per lead. Use for 'how many\n"
             + "  leads', 'open / in-progress / closed leads', 'high priority leads', 'leads to\n"
             + "  follow up today', 'which leads for <product>'. COUNT questions ALWAYS use this\n"
             + "  table — never ai_lead_event_info (that has many rows per lead and would inflate counts).\n"
             + "- get_lead_history tool = full JOURNEY of ONE named lead (every follow-up event in\n"
             + "  order). Use ONLY for 'history / journey / timeline / all follow-ups / progress /\n"
             + "  full story of <customer>'s lead'. Pass identifier = the customer/company name\n"
             + "  the user said (kind='name'); use kind='lead_id' or 'customer_id' only if they\n"
             + "  clearly gave an id. If no lead is named, ask which one.\n"
             + "  * If it returns multiple_customers=true, the name matched several customers:\n"
             + "    reply with ONLY {\"__suggestions__\": [\"Lead history of <full label 1>\", ...]}\n"
             + "    from matched_customers so the user picks one. Do NOT merge different leads.\n"
             + "FLAG COLUMNS is_price / is_product / is_other (on ai_lead_info): value 1 means that\n"
             + "  aspect has been discussed AND the customer is satisfied/agreed on it; 0 means not\n"
             + "  yet discussed OR the customer is not satisfied. So 'leads where price is agreed'\n"
             + "  → filter is_price = 1; 'leads still stuck on price' / 'price not sorted' →\n"
             + "  is_price = 0. Same for is_product (the product itself) and is_other (other terms).\n"
             + "  When showing these in a table, render 1 as 'Yes' and 0 as 'No' under clear\n"
             + "  headers (Price Agreed, Product Agreed, Other Agreed) — do not show bare 1/0.\n"
             + "PRESENTATION: for a customer or lead list, show the most useful columns for the\n"
             + "  question (e.g. customer_name/company_name, mobile_no, city, product_cateogory,\n"
             + "  customer_status for customers; lead_code, customer, priority, lead_status,\n"
             + "  follow_up_user, follow_up_date, product_name for leads). For a lead history, show\n"
             + "  the events in the returned order with columns Lead Date, Priority, Follow-up By,\n"
             + "  Follow-up Date, Status, Agenda, Remarks — so the progression is readable.\n"
             + "Lead/customer names, company names, remarks are TEXT columns — filter them with\n"
             + "  LIKE and a keyword, never exact '='. City/state/status/priority are exact '='.\n"
             + "WHO-DID-WHAT: worked_by = the user who already WORKED the lead/event; follow_up_user\n"
             + "  = the user who will follow up NEXT; worked_updated_date = when the update happened.\n"
             + "  'who worked on <lead>' / 'who handled it' → worked_by. 'who is following up' /\n"
             + "  'whose follow-up' → follow_up_user. 'when was it last updated' → worked_updated_date.\n"
             + "  In a lead-history table show a 'Worked By' and 'Updated' column so the progression\n"
             + "  of who-did-what-when is clear.\n"
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
             + "  All dispatches June:       {\"__export__\":true,\"table\":\"ai_dispatch_summary\",\"filter_col\":\"invoice_date\",\"filter_val\":\"2026-06-01,2026-06-30\",\"order_by\":\"invoice_date\",\"label\":\"Dispatches June 2026\"}\n"
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
             + "=== TABLE FORMATTING (STRICT — APPLIES TO EVERY TABLE) ===\n"
             + "1. QUANTITIES ARE WHOLE NUMBERS: any quantity / count / number-of-items value\n"
             + "   must be shown WITHOUT decimals — write 179, 34, 1 (never 179.00 or 1.0).\n"
             + "   Monetary amounts KEEP their decimals and Indian formatting as returned.\n"
             + "1b. MONETARY AMOUNTS ALWAYS USE INDIAN COMMA GROUPING: every money value —\n"
             + "   amounts, debit, credit, balance, totals, receivable, payable — must be\n"
             + "   written with Indian digit grouping and 2 decimals, in EVERY table cell AND\n"
             + "   in every sentence. Examples: 58,054.50 (not 58054.5), 1,25,050.00 (not\n"
             + "   125050), 30,43,55,888.00 (not 3043558888.00). If a tool returns a plain\n"
             + "   number, reformat it with commas before presenting. Never present a monetary\n"
             + "   amount without comma grouping.\n"
             + "2. COLUMN ALIGNMENT via markdown alignment markers in the separator row:\n"
             + "   - NAME-like columns (party name, item name, particulars, bank name,\n"
             + "     address, email, remarks)                          → LEFT   use :---\n"
             + "   - NUMERIC/MONEY columns (amount, debit, credit, balance, quantity, total,\n"
             + "     rate, price, gross, taxable, GST, discount, outstanding, overdue days)\n"
             + "                                                       → RIGHT  use ---:\n"
             + "   - EVERYTHING ELSE (dates, invoice no, voucher no, bill no, types, modes,\n"
             + "     status, cheque no)                                → CENTER use :---:\n"
             + "   Example:\n"
             + "   | ITEM NAME | INVOICE DATE | QUANTITY |\n"
             + "   |:---|:---:|---:|\n"
             + "   | EV.Badshah Smart | 30 Jun 2026 | 179 |\n"
             + "3. Apply these markers on EVERY table you output — stock, sales, ledger,\n"
             + "   dispatch, all of them.\n"
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
                "description": "Returns today's row from ai_daily_summary. Use ONLY when the user asks for total receivable or total payable as on today. Do NOT use for sales, purchase, collection, or payment figures — compute those from ai_sales / ai_purchase / ai_collection_summary / ai_payment_summary instead.",
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
                "name": "get_party_ledger",
                "description": "Get the complete GENERAL LEDGER (account statement) of ONE specific party: opening balance, every voucher entry (date, type, number, invoice, debit, credit, running balance), and the closing totals row. ONLY use when the user explicitly asks for a ledger / general ledger / account statement / khata of a NAMED party. A party name is REQUIRED — if the user has not named a party, ask which party they mean instead of calling this tool. Never use this for outstanding-balance, receivable, or payable questions (those come from ai_party_status).",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "party_name": { "type": "string", "description": "Name (or distinctive part of the name) of the party whose ledger is wanted. REQUIRED." }
                  },
                  "required": ["party_name"]
                }
              },
              {
                "name": "get_lead_history",
                "description": "Get the complete HISTORY / JOURNEY of ONE lead from ai_lead_event_info: every follow-up event in chronological order (what was discussed, by whom, status at each step, remarks). ONLY use when the user explicitly asks for a lead's history / journey / timeline / all follow-ups / events / progress of a NAMED customer-company (or a specific lead_id / customer_id). Identify the lead by 'identifier' + 'kind'. If the user names no lead at all, ask which one. NEVER use this to COUNT leads or list current lead status — that is ai_lead_info via query_table (one row per lead). This tool returns many rows per lead and would inflate any count.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "identifier": { "type": "string", "description": "What the user gave to name the lead: usually a company/customer name; may also be a numeric lead_id or customer_id. REQUIRED." },
                    "kind": { "type": "string", "enum": ["name", "lead_id", "customer_id"], "description": "Which kind 'identifier' is. Default 'name'. Use 'lead_id' only if the user clearly gave a lead id (e.g. LD-00012 or a lead number), 'customer_id' for a customer id, otherwise 'name'." }
                  },
                  "required": ["identifier"]
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
                      "description": "List of filter conditions using human label names (e.g. 'Color') or fixed columns (item_name, quantity). Each has label, operator, value. CRITICAL: for SPEC-value labels (Color, Chassis Number, etc.) ALWAYS use operator 'LIKE' with just the keyword, because spec values contain extra text — e.g. {label:'Color', operator:'LIKE', value:'RED'} matches 'RED (6X4X18+GRILL) DOUBLE KAMANI'. Using '=' returns nothing. Use '=' only for exact columns (item_name, quantity).",
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
                "description": "Get a high-level dispatch summary for a time period. Returns total vehicles dispatched, unique parties served (each counted once regardless of invoice count), and breakdown by item. Use for 'today dispatch', 'month dispatch', 'total dispatch', 'how many vehicles dispatched'. For a specific month like 'May dispatch' or 'April dispatch', use period='month' and set month (1-12) and year. No spec map needed for this tool.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "period": {
                      "type": "string",
                      "enum": ["today", "month", "all"],
                      "description": "'today' = today only | 'month' = a calendar month (use month+year for a specific month, omit them for current month) | 'all' = all-time total"
                    },
                    "month": {
                      "type": "integer",
                      "description": "Month number 1-12. Required when the user asks for a specific month like May (5). Omit for current month."
                    },
                    "year": {
                      "type": "integer",
                      "description": "4-digit year. Provide with month for a specific month. Omit to default to current year."
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
                      "description": "Optional filters. Each item: {label: column or spec label, operator: '='|'!='|'LIKE'|'>'|'<'|'>='|'<='|'IN', value: string}. Date filter example: {label:'invoice_date', operator:'>=', value:'2026-04-01'}. CRITICAL: for SPEC-value labels (Color, Chassis Number, Motor Number, Battery Model, etc.) ALWAYS use operator 'LIKE' with just the keyword, because spec values often contain extra descriptive text. Example: to find red vehicles use {label:'Color', operator:'LIKE', value:'RED'} — this matches 'RED (6X4X18+GRILL) DOUBLE KAMANI'. Using '=' would wrongly return nothing. Use '=' only for exact structured columns like invoice_date, quantity, party_name.",
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
    private static final int MAX_TOOL_DEPTH = 6;

    private String processResponse(JsonNode response,
                                   String originalMessage,
                                   List<Map<String, Object>> history,
                                   int depth,
                                   String companyId) throws Exception {
        return processResponse(response, originalMessage, history, depth, companyId, null);
    }

    private String processResponse(JsonNode response,
                                   String originalMessage,
                                   List<Map<String, Object>> history,
                                   int depth,
                                   String companyId,
                                   ArrayNode priorContents) throws Exception {
        if (depth > MAX_TOOL_DEPTH) {
            log.warn("[Gemini] Tool depth cap ({}) reached — forcing a final text answer.", MAX_TOOL_DEPTH);
            // First, use any text already in this response
            JsonNode cands = response.path("candidates");
            if (!cands.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode part : cands.get(0).path("content").path("parts")) {
                    if (part.has("text")) sb.append(part.get("text").asText());
                }
                String t = sb.toString().trim();
                if (!t.isEmpty()) return t;
            }
            // No text yet — make ONE final call with tools disabled so Gemini must answer in words
            // using the data it already fetched in this conversation.
            try {
                ObjectNode finalBody = buildRequestBody(
                    originalMessage + "\n\n(Please now give the final answer in text using the data already retrieved. Do not call any more tools.)",
                    history, false);
                String finalStr = callGemini(finalBody);
                JsonNode finalResp = mapper.readTree(finalStr);
                JsonNode fc = finalResp.path("candidates");
                if (!fc.isEmpty()) {
                    StringBuilder sb2 = new StringBuilder();
                    for (JsonNode part : fc.get(0).path("content").path("parts")) {
                        if (part.has("text")) sb2.append(part.get("text").asText());
                    }
                    String t2 = sb2.toString().trim();
                    if (!t2.isEmpty()) return t2;
                }
            } catch (Exception e) {
                log.error("[Gemini] Final-answer call failed: {}", e.getMessage());
            }
            return "The assistant gathered the data but couldn't finish composing the answer. Please try again.";
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
            String text = textParts.toString().trim();

            // Gemini sometimes returns an empty completion — a MALFORMED_FUNCTION_CALL, or a bare
            // STOP with no text after a tool error. This is transient. Retry on ANY empty result
            // rather than only on specific finishReasons: an empty answer is never a valid answer,
            // whatever the reason given.
            if (text.isEmpty() && depth < 3) {
                log.warn("[Gemini] Empty result (finishReason={}), retrying (attempt {})", finishReason, depth + 1);
                try {
                    ObjectNode retryBody = buildRequestBody(originalMessage, history, true);
                    String retryStr = callGemini(retryBody);
                    JsonNode retry = mapper.readTree(retryStr);
                    return processResponse(retry, originalMessage, history, depth + 1, companyId);
                } catch (Exception e) {
                    log.error("[Gemini] Retry failed: {}", e.getMessage());
                }
            }

            if (text.isEmpty()) {
                return EMPTY_RESULT;
            }
            return text;
        }

        // Build full conversation: history + user message + model tool call + tool results
        // Build/continue the conversation. On the first tool round priorContents is null so we
        // build fresh; on deeper rounds we REUSE the accumulated contents so Gemini can see every
        // tool call and result it already made — this prevents it re-fetching the same data in a loop.
        ArrayNode contents = (priorContents != null)
            ? priorContents
            : buildContentsWithHistory(originalMessage, history);

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
        return processResponse(mapper.readTree(fr2), originalMessage, history, depth + 1, companyId, contents);
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
            case "get_party_ledger" -> erpFunctions.getPartyLedger(args.path("party_name").asText(""), companyId);
            case "get_lead_history" -> erpFunctions.getLeadHistory(args.path("identifier").asText(""), args.path("kind").asText("name"), companyId);
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