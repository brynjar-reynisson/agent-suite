package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        conversationService = new ConversationService(conversationRepository, messageRepository);
    }

    private MessageRecord rec(String type, String message) {
        MessageRecord r = mock(MessageRecord.class);
        when(r.getType()).thenReturn(type);
        when(r.getMessage()).thenReturn(message);
        return r;
    }

    @Test
    void getConversationDetail_compactRecord_mappedToCompactRole() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(1L);
        when(conv.getUserId()).thenReturn(1L);
        when(conv.getExternalId()).thenReturn("ext-1");
        when(conv.getConversationName()).thenReturn("test");
        when(conv.getCustomName()).thenReturn(null);
        when(conv.getCreateTime()).thenReturn(java.time.OffsetDateTime.now());
        when(conv.getRootDirectory()).thenReturn("");
        when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));

        MessageRecord userMsg = rec("user", "hello");
        MessageRecord compactMsg = rec("compact", "this is the summary");
        MessageRecord assistantMsg = rec("assistant", "hi");
        when(messageRepository.findByConversationId(1L)).thenReturn(List.of(userMsg, compactMsg, assistantMsg));

        ConversationDetailDto detail = conversationService.getConversationDetail("ext-1", 1L);

        assertThat(detail.messages()).hasSize(3);
        assertThat(detail.messages().get(1).role()).isEqualTo("compact");
        assertThat(detail.messages().get(1).content()).isEqualTo("this is the summary");
    }
}
