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
            .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();

    public GeminiService(ErpFunctions erpFunctions) {
        this.erpFunctions = erpFunctions;
    }

    private String buildSystemPrompt() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr      = today.format(java.time.format.DateTimeFormatter.ofPattern("dd MMMM yyyy"));
        String currentMonth  = today.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
        String prevMonth     = today.minusMonths(1).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"));
        String prevMonthFrom = today.minusMonths(1).withDayOfMonth(1).toString();
        String prevMonthTo   = today.minusMonths(1).withDayOfMonth(today.minusMonths(1).lengthOfMonth()).toString();
        int    currentYear   = today.getYear();

        return "You are a strict ERP data assistant for Ayaam ERP by Datawheel Software Technology LLP.\n"
             + "Your ONLY job is to fetch and present business data from the ERP system.\n\n"
             + "AVAILABLE DATA — only these 3 tables exist:\n"
             + "1. ai_party_status — party/customer/supplier info: outstanding_balance, balance_type (DR/CR), last_bill_date, last_bill_amount, total_business_ytd, credit_limit, overdue_days, gstin, contact_number\n"
             + "2. ai_sales — individual bills: bill_no, bill_date, party_name, gross_amount, discount_amount, sgst_amount, cgst_amount, igst_amount, invoice_amount, taxable_amount, payment_mode\n"
             + "3. ai_daily_summary — daily totals: total_sales, total_bills_count, cash_sales, credit_sales, total_collection, total_receivable, total_payable, total_purchase, total_discount_given\n\n"
             + "STRICT BOUNDARIES:\n"
             + "- ONLY answer questions about sales, party status, bills, collections from the above tables.\n"
             + "- NO stock queries — stock data is not available yet.\n"
             + "- NEVER give advice, suggestions or recommendations.\n"
             + "- NEVER answer anything outside ERP data.\n"
             + "- If asked about stock: say 'Stock data is not available in the system yet.'\n"
             + "- If asked anything else outside ERP: say 'I can only answer questions about sales, bills, and party status.'\n\n"
             + "TODAY'S DATE: " + todayStr + "\n"
             + "CURRENT MONTH: " + currentMonth + "\n"
             + "PREVIOUS MONTH: " + prevMonth + " (from " + prevMonthFrom + " to " + prevMonthTo + ")\n\n"
             + "DATE RULES — critical:\n"
             + "- Always use the above dates for any time-based query.\n"
             + "- 'Today' = " + todayStr + "\n"
             + "- 'This month' = " + currentMonth + "\n"
             + "- 'Previous month' or 'last month' = " + prevMonth + " (from " + prevMonthFrom + " to " + prevMonthTo + ")\n"
             + "- 'This year' = " + currentYear + "\n"
             + "- Never default to 2024 or any past year — always use current dates above.\n\n"
             + "LANGUAGE RULES:\n"
             + "- The user's message will start with [LANG:EN] or [LANG:HI].\n"
             + "- [LANG:EN] = respond ENTIRELY in English.\n"
             + "- [LANG:HI] = respond ENTIRELY in Hindi.\n"
             + "- Never mix languages. Strip the [LANG:XX] tag from your response.\n\n"
             + "FORMATTING RULES:\n"
             + "- Always format currency with the rupee symbol and Indian number formatting (e.g. 1,24,500).\n"
             + "- When showing multiple items or parties, use a clean markdown table with headers.\n"
             + "- Keep responses short and factual — no filler words.\n"
             + "- Highlight important numbers in **bold**.\n"
             + "- Flag low stock items with the warning symbol and high overdue days with a red circle.\n"
             + "- Never show raw JSON or pipe-separated text.\n"
             + "- Date format: DD MMM YYYY (e.g. 22 May 2026).\n";
    }

    private JsonNode buildToolDefinitions() throws Exception {
        String toolsJson = """
        [
          {
            "functionDeclarations": [
              {
                "name": "get_previous_month_sales",
                "description": "Get total sales for the previous month. Use this when user asks for last month or previous month sales. No parameters needed.",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "name": "get_today_sales",
                "description": "Get today's total sales, number of bills, cash and credit breakdown, collection and purchase total",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "name": "get_cumulative_sales",
                "description": "Get total sales between two dates or for a specific month/period. Use this for monthly sales, yearly sales, or any date range.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "from_date": {
                      "type": "string",
                      "description": "Start date in yyyy-MM-dd format."
                    },
                    "to_date": {
                      "type": "string",
                      "description": "End date in yyyy-MM-dd format."
                    }
                  }
                }
              },
              {
                "name": "get_bills_for_date",
                "description": "Get list of individual bills/invoices for a specific date. Each bill shows gross, discount, GST (SGST/CGST/IGST), invoice amount, taxable amount and payment mode.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "date": {
                      "type": "string",
                      "description": "Date in yyyy-MM-dd format. Default is today."
                    },
                    "party_name": {
                      "type": "string",
                      "description": "Filter bills by party name. Optional."
                    }
                  }
                }
              },
              {
                "name": "get_party_status",
                "description": "Get full account status of a party or trader. Shows outstanding balance (DR/CR), last bill date and amount, total business YTD, credit limit, overdue days, GSTIN and contact.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "party_name": {
                      "type": "string",
                      "description": "Name or partial name of the party or trader to look up"
                    }
                  },
                  "required": ["party_name"]
                }
              },
              {
                "name": "get_overdue_parties",
                "description": "Get list of all parties/customers who have overdue payments (balance_type DR with overdue_days > 0)",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "min_overdue_days": {
                      "type": "integer",
                      "description": "Minimum number of overdue days to filter by. Default is 1."
                    }
                  }
                }
              },
              {
                "name": "get_collections",
                "description": "Get collection/receivable summary for today or a specific date. Shows total_collection, total_receivable and total_payable.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "party_name": {
                      "type": "string",
                      "description": "Filter by party name. Optional."
                    },
                    "date": {
                      "type": "string",
                      "description": "Date in yyyy-MM-dd format. Default is today."
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

    public String chat(String userMessage, List<Map<String, Object>> conversationHistory, String language) {
        // Prepend language tag so Gemini knows which language to respond in
        String langTag = "hi".equalsIgnoreCase(language) ? "[LANG:HI] " : "[LANG:EN] ";
        userMessage = langTag + userMessage;

        try {
            ObjectNode requestBody = mapper.createObjectNode();

            ObjectNode systemInstruction = mapper.createObjectNode();
            ObjectNode sysPart = mapper.createObjectNode();
            sysPart.put("text", buildSystemPrompt());
            systemInstruction.set("parts", mapper.createArrayNode().add(sysPart));
            requestBody.set("systemInstruction", systemInstruction);

            ArrayNode contents = mapper.createArrayNode();
            for (Map<String, Object> turn : conversationHistory) {
                ObjectNode content = mapper.createObjectNode();
                content.put("role", (String) turn.get("role"));
                ArrayNode parts = mapper.createArrayNode();
                ObjectNode part = mapper.createObjectNode();
                part.put("text", (String) turn.get("text"));
                parts.add(part);
                content.set("parts", parts);
                contents.add(content);
            }

            ObjectNode userContent = mapper.createObjectNode();
            userContent.put("role", "user");
            ArrayNode userParts = mapper.createArrayNode();
            ObjectNode userPart = mapper.createObjectNode();
            userPart.put("text", userMessage);
            userParts.add(userPart);
            userContent.set("parts", userParts);
            contents.add(userContent);

            requestBody.set("contents", contents);
            requestBody.set("tools", buildToolDefinitions());

            ObjectNode genConfig = mapper.createObjectNode();
            genConfig.put("temperature", 0.1);
            genConfig.put("maxOutputTokens", 2048);
            requestBody.set("generationConfig", genConfig);

            String url = apiUrl + "?key=" + apiKey;
            String responseStr = webClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                            .map(body -> new RuntimeException("Gemini API error " + clientResponse.statusCode() + ": " + body)))
                    .bodyToMono(String.class)
                    .block();

            JsonNode response = mapper.readTree(responseStr);
            return processGeminiResponse(response, userMessage, conversationHistory);

        } catch (Exception e) {
            return "Sorry, I encountered an error: " + e.getMessage();
        }
    }

    private String processGeminiResponse(JsonNode response,
                                          String originalMessage,
                                          List<Map<String, Object>> history) throws Exception {
        JsonNode candidates = response.path("candidates");
        if (candidates.isEmpty()) return "No response from Gemini.";

        JsonNode content = candidates.get(0).path("content");
        JsonNode parts = content.path("parts");

        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode funcCall = part.get("functionCall");
                String funcName = funcCall.get("name").asText();
                JsonNode args = funcCall.path("args");
                String funcResult = executeFunctionCall(funcName, args);
                return sendFunctionResultToGemini(originalMessage, funcName, funcResult, history);
            }
        }

        StringBuilder textResponse = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text")) textResponse.append(part.get("text").asText());
        }
        return textResponse.toString();
    }

    private String executeFunctionCall(String funcName, JsonNode args) {
        String companyId = "HOTAGE INDIA";
        return switch (funcName) {
            case "get_previous_month_sales" ->
                    erpFunctions.getPreviousMonthSales(companyId);
            case "get_today_sales" ->
                    erpFunctions.getTodaySales(companyId);
            case "get_cumulative_sales" ->
                    erpFunctions.getCumulativeSales(
                            args.path("from_date").asText(null),
                            args.path("to_date").asText(null), companyId);
            case "get_bills_for_date" ->
                    erpFunctions.getBillsForDate(
                            args.path("date").asText(null),
                            args.path("party_name").asText(null), companyId);
            case "get_party_status" ->
                    erpFunctions.getPartyStatus(args.path("party_name").asText(""), companyId);
            case "get_overdue_parties" ->
                    erpFunctions.getOverdueParties(args.path("min_overdue_days").asInt(1), companyId);
            case "get_collections" ->
                    erpFunctions.getCollections(
                            args.path("party_name").asText(null),
                            args.path("date").asText(null), companyId);
            default -> "Function not available: " + funcName + ". Available: get_today_sales, get_cumulative_sales, get_previous_month_sales, get_bills_for_date, get_party_status, get_overdue_parties, get_collections";
        };
    }

    private String sendFunctionResultToGemini(String originalMessage,
                                               String funcName,
                                               String funcResult,
                                               List<Map<String, Object>> history) throws Exception {
        ObjectNode requestBody = mapper.createObjectNode();

        ObjectNode systemInstruction = mapper.createObjectNode();
        ObjectNode sysPart = mapper.createObjectNode();
        sysPart.put("text", buildSystemPrompt());
        systemInstruction.set("parts", mapper.createArrayNode().add(sysPart));
        requestBody.set("systemInstruction", systemInstruction);

        ArrayNode contents = mapper.createArrayNode();

        for (Map<String, Object> turn : history) {
            ObjectNode content = mapper.createObjectNode();
            content.put("role", (String) turn.get("role"));
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode part = mapper.createObjectNode();
            part.put("text", (String) turn.get("text"));
            parts.add(part);
            content.set("parts", parts);
            contents.add(content);
        }

        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode userParts = mapper.createArrayNode();
        ObjectNode userPart = mapper.createObjectNode();
        userPart.put("text", originalMessage);
        userParts.add(userPart);
        userContent.set("parts", userParts);
        contents.add(userContent);

        ObjectNode funcResultContent = mapper.createObjectNode();
        funcResultContent.put("role", "model");
        ArrayNode funcParts = mapper.createArrayNode();
        ObjectNode funcPart = mapper.createObjectNode();
        ObjectNode funcResponse = mapper.createObjectNode();
        funcResponse.put("name", funcName);
        funcResponse.set("response", mapper.createObjectNode().put("result", funcResult));
        funcPart.set("functionResponse", funcResponse);
        funcParts.add(funcPart);
        funcResultContent.set("parts", funcParts);
        contents.add(funcResultContent);

        requestBody.set("contents", contents);

        ObjectNode genConfig = mapper.createObjectNode();
        genConfig.put("temperature", 0.1);
        genConfig.put("maxOutputTokens", 2048);
        requestBody.set("generationConfig", genConfig);

        String url = apiUrl + "?key=" + apiKey;
        String responseStr = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                    clientResponse -> clientResponse.bodyToMono(String.class)
                        .map(body -> new RuntimeException("Gemini API error " + clientResponse.statusCode() + ": " + body)))
                .bodyToMono(String.class)
                .block();

        JsonNode response = mapper.readTree(responseStr);
        JsonNode parts = response.path("candidates").get(0).path("content").path("parts");
        StringBuilder result = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text")) result.append(part.get("text").asText());
        }
        return result.toString();
    }
}