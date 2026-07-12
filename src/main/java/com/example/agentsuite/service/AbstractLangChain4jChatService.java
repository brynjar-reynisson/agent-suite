package com.example.agentsuite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

abstract class AbstractLangChain4jChatService implements ChatService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final ChatModel model;

    protected AbstractLangChain4jChatService(ChatModel model) {
        this.model = model;
    }

    private interface AssistantService {
        Result<String> chat(@dev.langchain4j.service.UserMessage String userMessage);
    }

    private AssistantService buildAiService(String systemPrompt, List<ChatMessage> historyMessages,
                                            Consumer<ChatEvent> emitter, Object[] tools) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(1000);
        for (ChatMessage m : historyMessages) memory.add(m);

        AiServices<AssistantService> builder = AiServices.builder(AssistantService.class)
                .chatModel(model)
                .chatMemory(memory)
                .maxSequentialToolsInvocations(ChatService.MAX_SEQUENTIAL_TOOL_INVOCATIONS);

        if (!systemPrompt.isBlank()) {
            builder.systemMessageProvider(id -> systemPrompt);
        }

        if (tools.length > 0) {
            builder.toolProvider(buildToolProvider(tools, emitter));
        }

        return builder.build();
    }

    private ToolProvider buildToolProvider(Object[] tools, Consumer<ChatEvent> emitter) {
        return request -> {
            ToolProviderResult.Builder result = ToolProviderResult.builder();
            for (Object tool : tools) {
                if (tool instanceof DynamicToolProvider dtp) {
                    for (Map.Entry<ToolSpecification, ToolExecutor> entry : dtp.toolEntries().entrySet()) {
                        ToolSpecification spec = entry.getKey();
                        ToolExecutor real = entry.getValue();
                        result.add(spec, (req, memId) -> {
                            String out = real.execute(req, memId);
                            emitter.accept(new ChatEvent.ToolBatch(List.of(
                                    new ChatEvent.ToolBatch.ToolExecution(req.name(), req.arguments(), out)
                            )));
                            return out;
                        });
                    }
                } else {
                    for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(tool)) {
                        Method method = findMethod(tool, spec.name());
                        ToolExecutor real = new DefaultToolExecutor(tool, method);
                        result.add(spec, (req, memId) -> {
                            String out = real.execute(req, memId);
                            emitter.accept(new ChatEvent.ToolBatch(List.of(
                                    new ChatEvent.ToolBatch.ToolExecution(req.name(), req.arguments(), out)
                            )));
                            return out;
                        });
                    }
                }
            }
            return result.build();
        };
    }

    private Method findMethod(Object tool, String toolName) {
        for (Method method : tool.getClass().getMethods()) {
            if (method.isAnnotationPresent(Tool.class)) {
                Tool ann = method.getAnnotation(Tool.class);
                String name = ann.name().isEmpty() ? method.getName() : ann.name();
                if (name.equals(toolName)) return method;
            }
        }
        throw new IllegalStateException("Method not found for tool: " + toolName);
    }

    @Override
    public ChatResponse chat(String systemPrompt, String userMessage, Object... tools) {
        if (tools.length == 0) {
            // Bypass AiServices template processing: the @UserMessage parameter annotation
            // causes LangChain4j to treat the string as a PromptTemplate, so any {{...}} in
            // user content (e.g. JSX inline styles) is misinterpreted as a template variable.
            List<ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            messages.add(UserMessage.from(userMessage));
            var req = dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages).build();
            return new ChatResponse(List.of(), model.chat(req).aiMessage().text());
        }
        List<ChatResponse.ToolCall> collectedCalls = new ArrayList<>();
        Consumer<ChatEvent> collector = event -> {
            if (event instanceof ChatEvent.ToolBatch tb) {
                for (var ex : tb.executions()) {
                    collectedCalls.add(new ChatResponse.ToolCall(ex.name(), ex.arguments()));
                }
            }
        };
        AssistantService svc = buildAiService(
                systemPrompt != null ? systemPrompt : "",
                List.of(),
                collector,
                tools
        );
        Result<String> result = svc.chat(userMessage);
        return new ChatResponse(collectedCalls, result.content());
    }

    @Override
    public void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools) {
        List<HistoryMessage> history = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            history.add(new HistoryMessage.SystemPrompt(systemPrompt));
        }
        chatStreamWithHistory(history, userMessage, emitter, tools);
    }

    @Override
    public void chatStreamWithHistory(List<HistoryMessage> history, String userMessage,
                                      Consumer<ChatEvent> emitter, Object... tools) {
        String systemPrompt = "";
        List<HistoryMessage> nonSystemHistory = new ArrayList<>();
        for (HistoryMessage h : history) {
            if (h instanceof HistoryMessage.SystemPrompt sp) {
                systemPrompt = sp.content();
            } else {
                nonSystemHistory.add(h);
            }
        }

        List<ChatMessage> seedMessages = buildMessageList(nonSystemHistory);
        AssistantService svc = buildAiService(systemPrompt, seedMessages, emitter, tools);
        try {
            Result<String> result = svc.chat(userMessage);
            emitter.accept(new ChatEvent.Content(result.content()));
            emitter.accept(new ChatEvent.Done(toTurnUsage(result.tokenUsage())));
        } catch (Exception e) {
            emitter.accept(new ChatEvent.Error(toUserFacingError(e)));
            emitter.accept(new ChatEvent.Done());
        }
    }

    static String toUserFacingError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        if (msg.toLowerCase().contains("sequential")) {
            return "Tool call limit reached (" + ChatService.MAX_SEQUENTIAL_TOOL_INVOCATIONS +
                    " per turn). Send another message to continue.";
        }
        return msg;
    }

    static TurnUsage toTurnUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        int input = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        Integer cacheRead = null;
        Integer cacheWrite = null;
        if (usage instanceof AnthropicTokenUsage anthropicUsage) {
            cacheRead = anthropicUsage.cacheReadInputTokens();
            cacheWrite = anthropicUsage.cacheCreationInputTokens();
        }
        return new TurnUsage(input, output, cacheRead, cacheWrite);
    }

    private List<ChatMessage> buildMessageList(List<HistoryMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        List<ToolExecutionRequest> pendingRequests = null;
        for (HistoryMessage h : history) {
            switch (h) {
                case HistoryMessage.SystemPrompt sp -> messages.add(SystemMessage.from(sp.content()));
                case HistoryMessage.User u -> messages.add(UserMessage.from(u.content()));
                case HistoryMessage.Assistant a -> messages.add(AiMessage.from(a.content()));
                case HistoryMessage.ToolCall tc -> {
                    pendingRequests = parseToolCallRequests(tc.callsJson());
                    messages.add(AiMessage.from(pendingRequests));
                }
                case HistoryMessage.ToolResult tr -> {
                    if (pendingRequests != null) {
                        List<String> results = parseToolResults(tr.resultsJson());
                        int count = Math.min(results.size(), pendingRequests.size());
                        for (int i = 0; i < count; i++) {
                            messages.add(ToolExecutionResultMessage.from(pendingRequests.get(i), results.get(i)));
                        }
                        pendingRequests = null;
                    }
                }
            }
        }
        return messages;
    }

    private List<ToolExecutionRequest> parseToolCallRequests(String callsJson) {
        try {
            JsonNode arr = MAPPER.readTree(callsJson);
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (JsonNode item : arr) {
                requests.add(ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(item.get("name").asText())
                        .arguments(item.get("arguments").asText())
                        .build());
            }
            return requests;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseToolResults(String resultsJson) {
        try {
            JsonNode arr = MAPPER.readTree(resultsJson);
            List<String> results = new ArrayList<>();
            for (JsonNode item : arr) {
                results.add(item.get("result").asText());
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }
}
