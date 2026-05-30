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
        return """
                You are a smart ERP business assistant for Ayaam ERP by Datawheel Software Technology LLP.
                You help business owners and managers query their business data conversationally.
                
                LANGUAGE RULES — most important:
                - Detect the language of the user's message automatically.
                - If the user writes in Hindi (or Hinglish), respond ENTIRELY in Hindi.
                - If the user writes in English, respond ENTIRELY in English.
                - Never mix languages in a single response.
                - Hindi example: if user asks "आज की बिक्री क्या है?", respond fully in Hindi.
                - Numbers and currency stay as is (₹1,24,500) in both languages.
                
                FORMATTING RULES — follow strictly:
                - Always format currency with ₹ symbol and Indian number formatting (e.g. ₹1,24,500).
                - When showing multiple items or parties, use a clean markdown table with headers.
                - Keep responses short and to the point — no unnecessary explanation.
                - Highlight important numbers in **bold**.
                - If showing stock, always flag low stock items with ⚠️.
                - If showing overdue parties, flag high overdue days with 🔴.
                - For single item or party queries, respond in 2-3 clean lines, no table needed.
                - Always end with a one-line insight or action if relevant.
                - Never show raw JSON or pipe-separated text.
                - Date format: DD MMM YYYY (e.g. 22 May 2026).
                - Never make up data — only report what the functions return.
                """;
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
                "name": "get_today_sales",
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

    public String chat(String userMessage, List<Map<String, Object>> conversationHistory) {
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