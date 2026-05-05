package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

abstract class AbstractLangChain4jChatService implements ChatService {

    private static final int MAX_TOOL_ITERATIONS = 20;

    protected final ChatLanguageModel model;

    protected AbstractLangChain4jChatService(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public ChatResponse chat(String systemPrompt, String userMessage, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        List<ChatResponse.ToolCall> allToolCalls = new ArrayList<>();
        int iterations = 0;
        while (true) {
            if (iterations >= MAX_TOOL_ITERATIONS) {
                throw new IllegalStateException("Exceeded maximum tool iterations: " + MAX_TOOL_ITERATIONS);
            }
            Response<AiMessage> response = toolSpecs.isEmpty()
                    ? model.generate(messages)
                    : model.generate(messages, toolSpecs);

            AiMessage aiMessage = response.content();
            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text() != null ? aiMessage.text() : "";
                return new ChatResponse(allToolCalls, text);
            }
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                allToolCalls.add(new ChatResponse.ToolCall(req.name(), req.arguments()));
                ToolExecutor executor = executors.get(req.name());
                if (executor == null) {
                    throw new IllegalStateException("No executor found for tool: " + req.name());
                }
                messages.add(ToolExecutionResultMessage.from(req, executor.execute(req, "default")));
            }
            iterations++;
        }
    }

    @Override
    public void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        int iterations = 0;
        while (true) {
            if (iterations >= MAX_TOOL_ITERATIONS) {
                throw new IllegalStateException("Exceeded maximum tool iterations: " + MAX_TOOL_ITERATIONS);
            }
            Response<AiMessage> response = toolSpecs.isEmpty()
                    ? model.generate(messages)
                    : model.generate(messages, toolSpecs);

            AiMessage aiMessage = response.content();
            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text() != null ? aiMessage.text() : "";
                emitter.accept(new ChatEvent.Content(text));
                emitter.accept(new ChatEvent.Done());
                return;
            }
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                emitter.accept(new ChatEvent.ToolCall(req.name(), req.arguments()));
                ToolExecutor executor = executors.get(req.name());
                if (executor == null) {
                    throw new IllegalStateException("No executor found for tool: " + req.name());
                }
                messages.add(ToolExecutionResultMessage.from(req, executor.execute(req, "default")));
            }
            iterations++;
        }
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
