package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoogleChatService implements ChatService {

    private final ChatLanguageModel model;
    private static final int MAX_TOOL_ITERATIONS = 20;

    public GoogleChatService(String apiKey, String modelName) {
        this(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxOutputTokens(8192)
                .build());
    }

    GoogleChatService(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        return loop(messages, toolSpecs, executors);
    }

    private String loop(List<ChatMessage> messages,
                        List<ToolSpecification> toolSpecs,
                        Map<String, ToolExecutor> executors) {
        return loop(messages, toolSpecs, executors, 0);
    }

    private String loop(List<ChatMessage> messages,
                        List<ToolSpecification> toolSpecs,
                        Map<String, ToolExecutor> executors,
                        int iterations) {
        if (iterations >= MAX_TOOL_ITERATIONS) {
            throw new IllegalStateException("Exceeded maximum tool iterations: " + MAX_TOOL_ITERATIONS);
        }
        Response<AiMessage> response = toolSpecs.isEmpty()
                ? model.generate(messages)
                : model.generate(messages, toolSpecs);

        AiMessage aiMessage = response.content();
        if (aiMessage.hasToolExecutionRequests()) {
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                ToolExecutor executor = executors.get(req.name());
                if (executor == null) {
                    throw new IllegalStateException("No executor found for tool: " + req.name());
                }
                String result = executor.execute(req, "default");
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
            return loop(messages, toolSpecs, executors, iterations + 1);
        }
        return aiMessage.text() != null ? aiMessage.text() : "";
    }

    private List<ToolSpecification> buildToolSpecs(Object[] tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object tool : tools) {
            specs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        }
        return specs;
    }

    private Map<String, ToolExecutor> buildExecutors(Object[] tools) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    Tool annotation = method.getAnnotation(Tool.class);
                    String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                    executors.put(toolName, new DefaultToolExecutor(tool, method));
                }
            }
        }
        return executors;
    }
}
