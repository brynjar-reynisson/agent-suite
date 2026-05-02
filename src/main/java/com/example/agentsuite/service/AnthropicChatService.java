package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnthropicChatService implements ChatService {

    private final ChatLanguageModel model;

    public AnthropicChatService(String apiKey, String modelName) {
        this(AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(8192)
                .build());
    }

    AnthropicChatService(ChatLanguageModel model) {
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
        Response<AiMessage> response = toolSpecs.isEmpty()
                ? model.generate(messages)
                : model.generate(messages, toolSpecs);

        AiMessage aiMessage = response.content();
        if (aiMessage.hasToolExecutionRequests()) {
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String result = executors.get(req.name()).execute(req, "default");
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
            return loop(messages, toolSpecs, executors);
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
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    executors.put(method.getName(), new DefaultToolExecutor(tool, method));
                }
            }
        }
        return executors;
    }
}
