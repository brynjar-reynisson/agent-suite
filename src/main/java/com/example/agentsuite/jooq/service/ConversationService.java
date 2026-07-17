package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import com.example.agentsuite.service.ConversationFileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ConversationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final SuiteUserRepository suiteUserRepository;
    private final ConversationFileService conversationFileService;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository,
                                SuiteUserRepository suiteUserRepository,
                                ConversationFileService conversationFileService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.suiteUserRepository = suiteUserRepository;
        this.conversationFileService = conversationFileService;
    }

    @Transactional
    public long createConversation(long userId, String name, String rootDirectory, String externalId) {
        long conversationId = conversationRepository.insert(userId, name, rootDirectory, externalId);
        String email = suiteUserRepository.findById(userId).map(SuiteUserRecord::getEmail).orElse(null);
        conversationFileService.createFile(email, name, externalId, OffsetDateTime.now())
                .ifPresent(fileName -> conversationRepository.updateMdFileName(conversationId, fileName));
        return conversationId;
    }

    @Transactional
    public void addMessage(long conversationId, long userId, String type, String message) {
        messageRepository.insert(conversationId, userId, type, message);
        conversationRepository.findById(conversationId).ifPresent(conv ->
                conversationFileService.appendMessage(conv.getMdFileName(), type, message, OffsetDateTime.now()));
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
    public Optional<String> findLastSystemPrompt(long conversationId) {
        return messageRepository.findLastSystemPrompt(conversationId);
    }

    @Transactional
    public void renameConversation(String externalId, long userId, String customName) {
        ConversationRecord conv = conversationRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));
        if (!conv.getUserId().equals(userId)) {
            throw new NoSuchElementException("Conversation not found: " + externalId);
        }
        conversationRepository.updateCustomName(conv.getConversationId(), customName);

        String displayName = (customName != null && !customName.isBlank()) ? customName : conv.getConversationName();
        String email = suiteUserRepository.findById(userId).map(SuiteUserRecord::getEmail).orElse(null);
        conversationFileService.renameFile(conv.getMdFileName(), email, displayName)
                .ifPresent(fileName -> conversationRepository.updateMdFileName(conv.getConversationId(), fileName));
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversationSummaries(long userId) {
        return conversationRepository.findByUserId(userId).stream()
                .map(conv -> new ConversationSummaryDto(
                        conv.getExternalId(),
                        conv.getConversationName(),
                        conv.getCustomName(),
                        conv.getCreateTime().toString()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversationDetail(String externalId, long userId) {
        ConversationRecord conv = conversationRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));

        if (!conv.getUserId().equals(userId)) {
            throw new NoSuchElementException("Conversation not found: " + externalId);
        }

        List<MessageRecord> records = messageRepository.findByConversationId(conv.getConversationId());

        String initialModel = "";
        String systemPrompt = "";
        List<ConversationDetailDto.MessageDto> messages = new ArrayList<>();
        List<ConversationDetailDto.ToolCallDto> toolCallBuffer = new ArrayList<>();

        for (MessageRecord r : records) {
            switch (r.getType()) {
                case "model_change" -> {
                    initialModel = r.getMessage();
                    if (!r.getMessage().isEmpty())
                        messages.add(new ConversationDetailDto.MessageDto("meta", "model:" + r.getMessage(), List.of()));
                }
                case "system_prompt" -> {
                    systemPrompt = r.getMessage();
                    if (!r.getMessage().isEmpty())
                        messages.add(new ConversationDetailDto.MessageDto("meta", "system:" + r.getMessage(), List.of()));
                }
                case "user" -> {
                    toolCallBuffer.clear();
                    messages.add(new ConversationDetailDto.MessageDto("user", r.getMessage(), List.of()));
                }
                case "tool_call"  -> toolCallBuffer.addAll(parseToolCalls(r.getMessage()));
                case "tool_result" -> {}
                case "assistant" -> {
                    messages.add(new ConversationDetailDto.MessageDto(
                            "ai", r.getMessage(), List.copyOf(toolCallBuffer)));
                    toolCallBuffer.clear();
                }
                case "compact" ->
                    messages.add(new ConversationDetailDto.MessageDto("compact", r.getMessage(), List.of()));
                case "clear" ->
                    messages.add(new ConversationDetailDto.MessageDto("clear", "", List.of()));
                default -> {}
            }
        }

        return new ConversationDetailDto(
                conv.getExternalId(),
                conv.getConversationName(),
                conv.getCustomName(),
                conv.getCreateTime().toString(),
                initialModel,
                systemPrompt,
                conv.getRootDirectory() != null ? conv.getRootDirectory() : "",
                messages
        );
    }

    @Transactional
    public void eraseLastTurn(String externalId, long userId) {
        ConversationRecord conv = conversationRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));
        if (!conv.getUserId().equals(userId)) {
            throw new NoSuchElementException("Conversation not found: " + externalId);
        }
        messageRepository.eraseLastTurn(conv.getConversationId());
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
