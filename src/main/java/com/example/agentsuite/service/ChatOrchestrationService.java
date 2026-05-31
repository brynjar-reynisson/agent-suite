package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    private static final long GUEST_USER_ID = 1L;

    private final ModelRegistry modelRegistry;
    private final ConversationService conversationService;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ChatOrchestrationService(ModelRegistry modelRegistry,
                                     ConversationService conversationService) {
        this.modelRegistry = modelRegistry;
        this.conversationService = conversationService;
    }

    public void chatStream(String conversationId, String model, String systemPrompt,
                           String userMessage, String rootDirectory,
                           Consumer<ChatEvent> emitter, Object[] tools) {

        if (conversationId == null || conversationId.isBlank()) {
            ChatService service = modelRegistry.get(model);
            if (service == null) {
                emitter.accept(new ChatEvent.Error("Unknown model: " + model));
                emitter.accept(new ChatEvent.Done());
                return;
            }
            service.chatStream(systemPrompt, userMessage, emitter, tools);
            return;
        }

        long conversationDbId;
        try {
            conversationDbId = resolveConversation(conversationId, model, systemPrompt, userMessage, rootDirectory);
        } catch (Exception e) {
            log.error("Failed to resolve conversation {}", conversationId, e);
            emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        List<HistoryMessage> history = loadHistory(conversationDbId);

        try {
            conversationService.addMessage(conversationDbId, GUEST_USER_ID, "user", userMessage);
        } catch (Exception e) {
            log.error("Failed to save user message for conversation {}", conversationDbId, e);
            emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        ChatService service = modelRegistry.get(model);
        if (service == null) {
            emitter.accept(new ChatEvent.Error("Unknown model: " + model));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        List<ChatEvent.ToolBatch> toolBatchBuffer = new ArrayList<>();
        StringBuilder contentBuffer = new StringBuilder();
        long convId = conversationDbId;

        service.chatStreamWithHistory(history, userMessage, event -> {
            switch (event) {
                case ChatEvent.ToolBatch tb -> {
                    emitter.accept(event);
                    toolBatchBuffer.add(tb);
                }
                case ChatEvent.Content c -> {
                    emitter.accept(event);
                    contentBuffer.append(c.text());
                }
                case ChatEvent.Done d -> {
                    persistTurnResult(convId, toolBatchBuffer, contentBuffer.toString());
                    emitter.accept(event);
                }
                case ChatEvent.Error e -> emitter.accept(event);
            }
        }, tools);
    }

    private long resolveConversation(String externalId, String model, String systemPrompt,
                                      String userMessage, String rootDirectory) {
        return conversationService.findByExternalId(externalId)
                .map(conv -> {
                    long convId = conv.getConversationId();
                    conversationService.findLastModelChange(convId).ifPresentOrElse(
                            lastModel -> {
                                if (!lastModel.equals(model)) {
                                    conversationService.addMessage(convId, GUEST_USER_ID, "model_change", model);
                                }
                            },
                            () -> conversationService.addMessage(convId, GUEST_USER_ID, "model_change", model)
                    );
                    return convId;
                })
                .orElseGet(() -> {
                    String name = userMessage.length() > 80 ? userMessage.substring(0, 80) : userMessage;
                    long convId = conversationService.createConversation(
                            GUEST_USER_ID, name, rootDirectory, externalId);
                    conversationService.addMessage(convId, GUEST_USER_ID, "model_change", model);
                    conversationService.addMessage(convId, GUEST_USER_ID, "system_prompt",
                            systemPrompt != null ? systemPrompt : "");
                    return convId;
                });
    }

    private List<HistoryMessage> loadHistory(long conversationDbId) {
        List<HistoryMessage> history = new ArrayList<>();
        for (MessageRecord r : conversationService.getMessages(conversationDbId)) {
            HistoryMessage msg = switch (r.getType()) {
                case "system_prompt" -> new HistoryMessage.SystemPrompt(r.getMessage());
                case "user"          -> new HistoryMessage.User(r.getMessage());
                case "assistant"     -> new HistoryMessage.Assistant(r.getMessage());
                case "tool_call"     -> new HistoryMessage.ToolCall(r.getMessage());
                case "tool_result"   -> new HistoryMessage.ToolResult(r.getMessage());
                default              -> null;
            };
            if (msg != null) history.add(msg);
        }
        return history;
    }

    private void persistTurnResult(long conversationDbId,
                                    List<ChatEvent.ToolBatch> batches,
                                    String content) {
        try {
            for (ChatEvent.ToolBatch batch : batches) {
                conversationService.addMessage(conversationDbId, GUEST_USER_ID,
                        "tool_call", serializeCalls(batch.executions()));
                conversationService.addMessage(conversationDbId, GUEST_USER_ID,
                        "tool_result", serializeResults(batch.executions()));
            }
            if (!content.isBlank()) {
                conversationService.addMessage(conversationDbId, GUEST_USER_ID, "assistant", content);
            }
        } catch (Exception e) {
            log.error("Failed to persist turn result for conversation {}", conversationDbId, e);
        }
    }

    private String serializeCalls(List<ChatEvent.ToolBatch.ToolExecution> executions) {
        try {
            return OBJECT_MAPPER.writeValueAsString(executions.stream()
                    .map(e -> Map.of("name", e.name(), "arguments", e.arguments()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeResults(List<ChatEvent.ToolBatch.ToolExecution> executions) {
        try {
            return OBJECT_MAPPER.writeValueAsString(executions.stream()
                    .map(e -> Map.of("name", e.name(), "result", e.result()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }
}
