package com.example.agentsuite.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeepSeekService implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Map<String, String> reasoningCache = new ConcurrentHashMap<>();

    public DeepSeekService(@Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
                           @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
                           @Value("${langchain4j.open-ai.chat-model.model-name}") String model,
                           @Value("${langchain4j.open-ai.chat-model.temperature}") double temperature,
                           @Value("${langchain4j.open-ai.chat-model.max-tokens}") int maxTokens) {
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.mapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public String chat(String systemPrompt, String userMessage, Object... tools) {
        List<ObjectNode> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(createMessage("system", systemPrompt));
        }
        messages.add(createMessage("user", userMessage));

        ArrayNode toolDefs = tools.length > 0 ? buildToolDefinitions(tools) : null;
        JsonNode response = sendRequest(messages, toolDefs);

        return processResponse(messages, toolDefs, tools, response);
    }

    private String processResponse(List<ObjectNode> messages, ArrayNode toolDefs,
                                    Object[] tools, JsonNode response) {
        JsonNode choice = response.get("choices").get(0);
        JsonNode msg = choice.get("message");

        String reasoning = msg.has("reasoning_content") ? msg.get("reasoning_content").asText() : null;
        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : "stop";

        if ("tool_calls".equals(finishReason) && msg.has("tool_calls")) {
            // Cache reasoning content
            String fingerprint = buildFingerprint(msg);
            if (reasoning != null && !reasoning.isEmpty()) {
                log.debug("Caching reasoning: {}", reasoning);
                reasoningCache.put(fingerprint, reasoning);
            }

            // Add assistant message to history
            messages.add((ObjectNode) msg);

            // Execute tools and add results
            ArrayNode toolCalls = (ArrayNode) msg.get("tool_calls");
            for (JsonNode toolCall : toolCalls) {
                String funcName = toolCall.get("function").get("name").asText();
                String funcArgs = toolCall.get("function").get("arguments").asText();
                String callId = toolCall.get("id").asText();

                String result = executeTool(tools, funcName, funcArgs);
                ObjectNode toolMsg = mapper.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", result);
                messages.add(toolMsg);
            }

            // Send follow-up request
            JsonNode followUp = sendRequest(messages, toolDefs);
            return processResponse(messages, toolDefs, tools, followUp);
        }

        return msg.has("content") && !msg.get("content").isNull()
                ? msg.get("content").asText()
                : "";
    }

    private JsonNode sendRequest(List<ObjectNode> messages, ArrayNode tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.set("messages", messagesToArray(messages));

        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
        }

        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(JsonNode.class);
    }

    private ArrayNode messagesToArray(List<ObjectNode> messages) {
        ArrayNode arr = mapper.createArrayNode();
        for (ObjectNode msg : messages) {
            ObjectNode copy = msg.deepCopy();
            if ("assistant".equals(copy.get("role").asText())) {
                // Inject reasoning_content if available
                String fingerprint = buildFingerprint(copy);
                String cached = reasoningCache.get(fingerprint);
                if (cached != null) {
                    copy.put("reasoning_content", cached);
                }
            }
            arr.add(copy);
        }
        return arr;
    }

    private ObjectNode createMessage(String role, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String buildFingerprint(JsonNode msg) {
        if (msg.has("tool_calls")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode tc : msg.get("tool_calls")) {
                sb.append(tc.get("id").asText())
                  .append(":")
                  .append(tc.get("function").get("name").asText())
                  .append(",");
            }
            return sb.toString();
        }
        return msg.has("content") && !msg.get("content").isNull()
                ? msg.get("content").asText() : "";
    }

    private ArrayNode buildToolDefinitions(Object[] tools) {
        ArrayNode arr = mapper.createArrayNode();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                dev.langchain4j.agent.tool.Tool toolAnn =
                        method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                if (toolAnn == null) continue;

                ObjectNode def = mapper.createObjectNode();
                def.put("type", "function");

                ObjectNode func = mapper.createObjectNode();
                func.put("name", method.getName());
                func.put("description", toolAnn.value().length > 0 ? toolAnn.value()[0] : "");

                ObjectNode params = mapper.createObjectNode();
                params.put("type", "object");

                ObjectNode props = mapper.createObjectNode();
                ArrayNode required = mapper.createArrayNode();

                for (Parameter param : method.getParameters()) {
                    dev.langchain4j.agent.tool.P pAnn =
                            param.getAnnotation(dev.langchain4j.agent.tool.P.class);
                    String paramName = param.getName();
                    String paramDesc = pAnn != null ? pAnn.value() : paramName;

                    ObjectNode prop = mapper.createObjectNode();
                    prop.put("type", "string");
                    prop.put("description", paramDesc);
                    props.set(paramName, prop);
                    required.add(paramName);
                }

                params.set("properties", props);
                params.set("required", required);
                func.set("parameters", params);
                def.set("function", func);
                arr.add(def);
            }
        }
        return arr;
    }

    private String executeTool(Object[] tools, String functionName, String arguments) {
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (!method.getName().equals(functionName)) continue;
                try {
                    Parameter[] params = method.getParameters();
                    if (params.length == 0) {
                        return String.valueOf(method.invoke(tool));
                    }
                    // Parse arguments JSON
                    JsonNode args = mapper.readTree(arguments);
                    Object[] callArgs = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        String paramName = params[i].getName();
                        JsonNode val = args.get(paramName);
                        callArgs[i] = val != null ? val.asText() : null;
                    }
                    return String.valueOf(method.invoke(tool, callArgs));
                } catch (Exception e) {
                    return "Error executing " + functionName + ": " + e.getMessage();
                }
            }
        }
        return "Error: tool " + functionName + " not found";
    }
}
