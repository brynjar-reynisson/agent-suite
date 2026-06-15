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

    private final ModelRegistry modelRegistry;
    private final ConversationService conversationService;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ChatOrchestrationService(ModelRegistry modelRegistry,
                                     ConversationService conversationService) {
        this.modelRegistry = modelRegistry;
        this.conversationService = conversationService;
    }

    public void chatStream(String conversationId, long userId, String model, String systemPrompt,
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
            conversationDbId = resolveConversation(conversationId, userId, model, systemPrompt, userMessage, rootDirectory);
        } catch (Exception e) {
            log.error("Failed to resolve conversation {}", conversationId, e);
            emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        List<HistoryMessage> history = loadHistory(conversationDbId);

        try {
            conversationService.addMessage(conversationDbId, userId, "user", userMessage);
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
                    persistTurnResult(convId, userId, toolBatchBuffer, contentBuffer.toString());
                    emitter.accept(event);
                }
                case ChatEvent.Error e -> emitter.accept(event);
            }
        }, tools);
    }

    private long resolveConversation(String externalId, long userId, String model, String systemPrompt,
                                      String userMessage, String rootDirectory) {
        return conversationService.findByExternalId(externalId)
                .map(conv -> {
                    if (!conv.getUserId().equals(userId)) {
                        throw new java.util.NoSuchElementException("Conversation not found: " + externalId);
                    }
                    long convId = conv.getConversationId();
                    conversationService.findLastModelChange(convId).ifPresentOrElse(
                            lastModel -> {
                                if (!lastModel.equals(model)) {
                                    conversationService.addMessage(convId, userId, "model_change", model);
                                }
                            },
                            () -> conversationService.addMessage(convId, userId, "model_change", model)
                    );
                    String normalizedPrompt = systemPrompt != null ? systemPrompt : "";
                    conversationService.findLastSystemPrompt(convId).ifPresentOrElse(
                            lastPrompt -> {
                                if (!lastPrompt.equals(normalizedPrompt)) {
                                    conversationService.addMessage(convId, userId, "system_prompt", normalizedPrompt);
                                }
                            },
                            () -> conversationService.addMessage(convId, userId, "system_prompt", normalizedPrompt)
                    );
                    return convId;
                })
                .orElseGet(() -> {
                    String name = userMessage.length() > 80 ? userMessage.substring(0, 80) : userMessage;
                    long convId = conversationService.createConversation(
                            userId, name, rootDirectory, externalId);
                    conversationService.addMessage(convId, userId, "model_change", model);
                    conversationService.addMessage(convId, userId, "system_prompt",
                            systemPrompt != null ? systemPrompt : "");
                    return convId;
                });
    }

    private List<HistoryMessage> loadHistory(long conversationDbId) {
        List<MessageRecord> records = conversationService.getMessages(conversationDbId);

        String lastSystemPrompt = null;
        for (MessageRecord r : records) {
            if ("system_prompt".equals(r.getType())) lastSystemPrompt = r.getMessage();
        }

        int compactIndex = -1;
        for (int i = records.size() - 1; i >= 0; i--) {
            if ("compact".equals(records.get(i).getType())) {
                compactIndex = i;
                break;
            }
        }

        List<HistoryMessage> history = new ArrayList<>();
        if (lastSystemPrompt != null && !lastSystemPrompt.isEmpty()) {
            history.add(new HistoryMessage.SystemPrompt(lastSystemPrompt));
        }

        if (compactIndex >= 0) {
            history.add(new HistoryMessage.User(
                    "Previous conversation summary:\n\n" + records.get(compactIndex).getMessage()));
            for (int i = compactIndex + 1; i < records.size(); i++) {
                addIfSubstantive(history, records.get(i));
            }
        } else {
            for (MessageRecord r : records) {
                addIfSubstantive(history, r);
            }
        }

        return history;
    }

    private static void addIfSubstantive(List<HistoryMessage> history, MessageRecord r) {
        HistoryMessage msg = switch (r.getType()) {
            case "user"        -> new HistoryMessage.User(r.getMessage());
            case "assistant"   -> new HistoryMessage.Assistant(r.getMessage());
            case "tool_call"   -> new HistoryMessage.ToolCall(r.getMessage());
            case "tool_result" -> new HistoryMessage.ToolResult(r.getMessage());
            default            -> null;
        };
        if (msg != null) history.add(msg);
    }

    private static final String SUMMARY_SYSTEM_PROMPT =
            "Summarise the conversation below concisely. Preserve the key context, decisions, " +
            "facts, and any ongoing tasks. Write in the third person and omit pleasantries.";

    public String compact(String externalId, long userId) {
        ConversationRecord conv = conversationService.findByExternalId(externalId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new java.util.NoSuchElementException("Conversation not found: " + externalId));

        long convDbId = conv.getConversationId();
        List<MessageRecord> records = conversationService.getMessages(convDbId);

        // Use full history intentionally: when re-compacting, the LLM should see prior summaries plus all subsequent messages.
        String transcript = buildTranscript(records);
        if (transcript.isBlank()) {
            throw new IllegalArgumentException("Nothing to compact.");
        }

        String model = conversationService.findLastModelChange(convDbId).orElse("deepseek-v4-pro");
        ChatService service = modelRegistry.get(model);
        if (service == null) service = modelRegistry.get("deepseek-v4-pro");
        if (service == null) throw new IllegalStateException("No chat service available for compact.");

        String summary = service.chat(SUMMARY_SYSTEM_PROMPT, transcript).content();
        conversationService.addMessage(convDbId, userId, "compact", summary);
        return summary;
    }

    static String buildTranscript(List<MessageRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (MessageRecord r : records) {
            String line = switch (r.getType()) {
                case "user"        -> "[User]: " + r.getMessage();
                case "assistant"   -> "[Assistant]: " + r.getMessage();
                case "tool_call"   -> "[Tool call]: " + r.getMessage();
                case "tool_result" -> "[Tool result]: " + r.getMessage();
                case "compact"     -> "[Summary]: " + r.getMessage();
                default            -> null;
            };
            if (line != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private void persistTurnResult(long conversationDbId, long userId,
                                    List<ChatEvent.ToolBatch> batches,
                                    String content) {
        try {
            for (ChatEvent.ToolBatch batch : batches) {
                conversationService.addMessage(conversationDbId, userId,
                        "tool_call", serializeCalls(batch.executions()));
                conversationService.addMessage(conversationDbId, userId,
                        "tool_result", serializeResults(batch.executions()));
            }
            if (!content.isBlank()) {
                conversationService.addMessage(conversationDbId, userId, "assistant", content);
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
