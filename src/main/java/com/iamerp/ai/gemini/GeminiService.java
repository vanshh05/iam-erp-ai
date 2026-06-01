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
             + "STRICT BOUNDARIES — never break these:\n"
             + "- You ONLY answer questions about data available in the ERP: sales, stock, parties, collections, ledger, bills.\n"
             + "- You NEVER give business advice, suggestions, recommendations, or opinions of any kind.\n"
             + "- You NEVER say things like 'you should', 'consider', 'I recommend', 'it would be better to', 'you may want to'.\n"
             + "- You NEVER answer questions unrelated to accounting or ERP data.\n"
             + "- If asked anything outside ERP data, respond only with: 'I can only answer questions about your ERP data such as sales, stock, parties, and collections.'\n"
             + "- You NEVER make up or estimate data. Only report exactly what the functions return.\n"
             + "- You NEVER add commentary, analysis, or insight beyond the raw data requested.\n"
             + "- You are not a chatbot — you are a data retrieval tool. Just answer what was asked, nothing more.\n\n"
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
                "name": "get_current_stock",
                "description": "Get current stock quantity for a specific item or all items in inventory",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "item_name": {
                      "type": "string",
                      "description": "Name of the item to search. Leave empty for all items."
                    }
                  }
                }
              },
              {
                "name": "get_low_stock_items",
                "description": "Get all items that are below their minimum stock level",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "name": "get_previous_month_sales",
                "description": "Get total sales for the previous month. Use this when user asks for last month or previous month sales. No parameters needed.",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "description": "Get today's total sales, number of bills, cash and credit breakdown, collection and tax",
                "parameters": { "type": "object", "properties": {} }
              },
              {
                "name": "get_cumulative_sales",
                "description": "Get total sales between two dates or for a period",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "from_date": {
                      "type": "string",
                      "description": "Start date in yyyy-MM-dd format. Default is start of current month."
                    },
                    "to_date": {
                      "type": "string",
                      "description": "End date in yyyy-MM-dd format. Default is today."
                    }
                  }
                }
              },
              {
                "name": "get_bills_for_date",
                "description": "Get list of all bills raised on a specific date, optionally filtered by party",
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
                "description": "Get full account status of a party or trader including outstanding balance, last bill, credit limit and overdue info",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "party_name": {
                      "type": "string",
                      "description": "Name of the party or trader to look up"
                    }
                  },
                  "required": ["party_name"]
                }
              },
              {
                "name": "get_overdue_parties",
                "description": "Get list of all parties who have overdue payments",
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
                "name": "get_ledger_summary",
                "description": "Get ledger account summary for a party showing debit, credit and closing balance",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "party_name": {
                      "type": "string",
                      "description": "Name of the party"
                    },
                    "from_date": {
                      "type": "string",
                      "description": "Start date in yyyy-MM-dd format"
                    },
                    "to_date": {
                      "type": "string",
                      "description": "End date in yyyy-MM-dd format"
                    }
                  },
                  "required": ["party_name"]
                }
              },
              {
                "name": "get_collections",
                "description": "Get payment collections received today or for a specific party and date",
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
              },
              {
                "name": "get_stock_movement",
                "description": "Get stock movement history (sales, purchases, adjustments) for an item",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "item_name": {
                      "type": "string",
                      "description": "Name of the item"
                    },
                    "from_date": {
                      "type": "string",
                      "description": "Start date in yyyy-MM-dd format. Default is last 7 days."
                    },
                    "to_date": {
                      "type": "string",
                      "description": "End date in yyyy-MM-dd format. Default is today."
                    }
                  },
                  "required": ["item_name"]
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
        return switch (funcName) {
            case "get_current_stock" ->
                    erpFunctions.getCurrentStock(args.path("item_name").asText(null));
            case "get_low_stock_items" ->
                    erpFunctions.getLowStockItems();
            case "get_previous_month_sales" ->
                    erpFunctions.getPreviousMonthSales();
            case "get_today_sales" ->
                    erpFunctions.getTodaySales();
            case "get_cumulative_sales" ->
                    erpFunctions.getCumulativeSales(
                            args.path("from_date").asText(null),
                            args.path("to_date").asText(null));
            case "get_bills_for_date" ->
                    erpFunctions.getBillsForDate(
                            args.path("date").asText(null),
                            args.path("party_name").asText(null));
            case "get_party_status" ->
                    erpFunctions.getPartyStatus(args.path("party_name").asText(""));
            case "get_overdue_parties" ->
                    erpFunctions.getOverdueParties(args.path("min_overdue_days").asInt(1));
            case "get_ledger_summary" ->
                    erpFunctions.getLedgerSummary(
                            args.path("party_name").asText(""),
                            args.path("from_date").asText(null),
                            args.path("to_date").asText(null));
            case "get_collections" ->
                    erpFunctions.getCollections(
                            args.path("party_name").asText(null),
                            args.path("date").asText(null));
            case "get_stock_movement" ->
                    erpFunctions.getStockMovement(
                            args.path("item_name").asText(""),
                            args.path("from_date").asText(null),
                            args.path("to_date").asText(null));
            default -> "Unknown function: " + funcName;
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