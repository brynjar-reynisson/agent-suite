package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ConversationService {

    private static final long GUEST_USER_ID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public long createConversation(long userId, String name, String rootDirectory, String externalId) {
        return conversationRepository.insert(userId, name, rootDirectory, externalId);
    }

    @Transactional
    public void addMessage(long conversationId, long userId, String type, String message) {
        messageRepository.insert(conversationId, userId, type, message);
    }

    @Transactional(readOnly = true)
    public List<MessageRecord> getMessages(long conversationId) {
        return messageRepository.findByConversationId(conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationRecord getConversation(long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }

    @Transactional(readOnly = true)
    public Optional<ConversationRecord> findByExternalId(String externalId) {
        return conversationRepository.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Optional<String> findLastModelChange(long conversationId) {
        return messageRepository.findLastModelChange(conversationId);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversationSummaries() {
        return conversationRepository.findByUserId(GUEST_USER_ID).stream()
                .map(conv -> {
                    List<MessageRecord> msgs = messageRepository.findByConversationId(conv.getConversationId());
                    String initialModel = msgs.stream()
                            .filter(m -> "model_change".equals(m.getType()))
                            .findFirst()
                            .map(MessageRecord::getMessage)
                            .orElse("");
                    String systemPrompt = msgs.stream()
                            .filter(m -> "system_prompt".equals(m.getType()))
                            .findFirst()
                            .map(MessageRecord::getMessage)
                            .orElse("");
                    return new ConversationSummaryDto(
                            conv.getExternalId(),
                            conv.getConversationName(),
                            conv.getCreateTime().toString(),
                            initialModel,
                            systemPrompt
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversationDetail(String externalId) {
        ConversationRecord conv = conversationRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));

        List<MessageRecord> records = messageRepository.findByConversationId(conv.getConversationId());

        String initialModel = "";
        String systemPrompt = "";
        List<ConversationDetailDto.MessageDto> messages = new ArrayList<>();
        List<ConversationDetailDto.ToolCallDto> toolCallBuffer = new ArrayList<>();

        for (MessageRecord r : records) {
            switch (r.getType()) {
                case "model_change"  -> { if (initialModel.isEmpty()) initialModel = r.getMessage(); }
                case "system_prompt" -> { if (systemPrompt.isEmpty()) systemPrompt = r.getMessage(); }
                case "user" -> {
                    toolCallBuffer.clear();
                    messages.add(new ConversationDetailDto.MessageDto("user", r.getMessage(), List.of()));
                }
                case "tool_call"  -> toolCallBuffer.addAll(parseToolCalls(r.getMessage()));
                case "tool_result" -> {} // not shown in UI
                case "assistant" -> {
                    messages.add(new ConversationDetailDto.MessageDto(
                            "ai", r.getMessage(), List.copyOf(toolCallBuffer)));
                    toolCallBuffer.clear();
                }
                default -> {} // ignore unknown types
            }
        }

        return new ConversationDetailDto(
                conv.getExternalId(),
                conv.getConversationName(),
                conv.getCreateTime().toString(),
                initialModel,
                systemPrompt,
                conv.getRootDirectory() != null ? conv.getRootDirectory() : "",
                messages
        );
    }

    private List<ConversationDetailDto.ToolCallDto> parseToolCalls(String callsJson) {
        try {
            JsonNode arr = MAPPER.readTree(callsJson);
            List<ConversationDetailDto.ToolCallDto> result = new ArrayList<>();
            for (JsonNode item : arr) {
                result.add(new ConversationDetailDto.ToolCallDto(
                        item.get("name").asText(),
                        item.get("arguments").asText()
                ));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
