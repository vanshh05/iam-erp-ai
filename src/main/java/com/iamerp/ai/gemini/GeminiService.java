package com.iamerp.ai.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.iamerp.ai.functions.ErpFunctions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Handles all communication with the Gemini API.
 * Uses function calling so Gemini decides which ERP query to run.
 */
@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final ErpFunctions erpFunctions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient = WebClient.builder().build();

    public GeminiService(ErpFunctions erpFunctions) {
        this.erpFunctions = erpFunctions;
    }

    // ─── Tool definitions (what Gemini can call) ──────────────────────────────

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

    // ─── System prompt ─────────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are an intelligent ERP assistant for IAM ERP.
                You help business owners and managers query their business data conversationally.
                
                Rules:
                - Always use the provided functions to fetch real data before answering.
                - Format all Indian currency values with ₹ symbol and Indian number formatting (e.g. ₹1,24,500).
                - Keep responses concise, clear and business-friendly.
                - If a party name or item name is not found, suggest the user check the spelling.
                - For date ranges, if not specified assume current month or today.
                - Never make up data — only report what the functions return.
                - If the data shows low stock or overdue payments, proactively highlight them.
                """;
    }

    // ─── Main chat method ─────────────────────────────────────────────────────

    public String chat(String userMessage, List<Map<String, Object>> conversationHistory) {
        try {
            // Build request body
            ObjectNode requestBody = mapper.createObjectNode();

            // System instruction
            ObjectNode systemInstruction = mapper.createObjectNode();
            ObjectNode sysPart = mapper.createObjectNode();
            sysPart.put("text", buildSystemPrompt());
            systemInstruction.set("parts", mapper.createArrayNode().add(sysPart));
            requestBody.set("systemInstruction", systemInstruction);

            // Conversation history + new message
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

            // Generation config
            ObjectNode genConfig = mapper.createObjectNode();
            genConfig.put("temperature", 0.2);
            genConfig.put("maxOutputTokens", 1024);
            requestBody.set("generationConfig", genConfig);

            // Call Gemini
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

    // ─── Process Gemini response + execute function calls ─────────────────────

    private String processGeminiResponse(JsonNode response,
                                          String originalMessage,
                                          List<Map<String, Object>> history) throws Exception {
        JsonNode candidates = response.path("candidates");
        if (candidates.isEmpty()) return "No response from Gemini.";

        JsonNode content = candidates.get(0).path("content");
        JsonNode parts = content.path("parts");

        // Check if Gemini wants to call a function
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode funcCall = part.get("functionCall");
                String funcName = funcCall.get("name").asText();
                JsonNode args = funcCall.path("args");

                // Execute the function
                String funcResult = executeFunctionCall(funcName, args);

                // Send function result back to Gemini for final response
                return sendFunctionResultToGemini(
                        originalMessage, funcName, funcResult, history
                );
            }
        }

        // No function call — direct text response
        StringBuilder textResponse = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.has("text")) {
                textResponse.append(part.get("text").asText());
            }
        }
        return textResponse.toString();
    }

    // ─── Route function calls to ErpFunctions ────────────────────────────────

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

    // ─── Send function result back to Gemini for formatting ──────────────────

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

        // Previous history
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

        // Original user message
        ObjectNode userContent = mapper.createObjectNode();
        userContent.put("role", "user");
        ArrayNode userParts = mapper.createArrayNode();
        ObjectNode userPart = mapper.createObjectNode();
        userPart.put("text", originalMessage);
        userParts.add(userPart);
        userContent.set("parts", userParts);
        contents.add(userContent);

        // Function result as model turn
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
        genConfig.put("temperature", 0.2);
        genConfig.put("maxOutputTokens", 1024);
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
