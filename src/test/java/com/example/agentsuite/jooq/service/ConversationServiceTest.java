package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import com.example.agentsuite.service.ConversationFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.NoSuchElementException;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private SuiteUserRepository suiteUserRepository;
    private ConversationFileService conversationFileService;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        suiteUserRepository = mock(SuiteUserRepository.class);
        conversationFileService = mock(ConversationFileService.class);
        conversationService = new ConversationService(conversationRepository, messageRepository,
                suiteUserRepository, conversationFileService);
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

    @Test
    void eraseLastTurn_delegatesToRepository() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(5L);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));

        conversationService.eraseLastTurn("ext-1", 1L);

        verify(messageRepository).eraseLastTurn(5L);
    }

    @Test
    void eraseLastTurn_throwsWhenConversationNotFound() {
        when(conversationRepository.findByExternalId("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.eraseLastTurn("missing", 1L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void eraseLastTurn_throwsWhenWrongUser() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(5L);
        when(conv.getUserId()).thenReturn(99L);
        when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));

        assertThatThrownBy(() -> conversationService.eraseLastTurn("ext-1", 1L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void createConversation_createsFileAndPersistsFileName() {
        when(conversationRepository.insert(1L, "Chat", "/root", "ext-1")).thenReturn(10L);
        SuiteUserRecord user = mock(SuiteUserRecord.class);
        when(user.getEmail()).thenReturn("a@x.com");
        when(suiteUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(conversationFileService.createFile(eq("a@x.com"), eq("Chat"), eq("ext-1"), any()))
                .thenReturn(Optional.of("a_Chat-dev.md"));

        long id = conversationService.createConversation(1L, "Chat", "/root", "ext-1");

        assertThat(id).isEqualTo(10L);
        verify(conversationRepository).updateMdFileName(10L, "a_Chat-dev.md");
    }

    @Test
    void addMessage_appendsToConversationFile() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getMdFileName()).thenReturn("a_Chat-dev.md");
        when(conversationRepository.findById(10L)).thenReturn(Optional.of(conv));

        conversationService.addMessage(10L, 1L, "user", "Hello");

        verify(messageRepository).insert(10L, 1L, "user", "Hello");
        verify(conversationFileService).appendMessage(eq("a_Chat-dev.md"), eq("user"), eq("Hello"), any());
    }

    @Test
    void renameConversation_renamesFileAndPersistsNewFileName() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(10L);
        when(conv.getUserId()).thenReturn(1L);
        when(conv.getConversationName()).thenReturn("Old Name");
        when(conv.getMdFileName()).thenReturn("a_Old Name-dev.md");
        when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));
        SuiteUserRecord user = mock(SuiteUserRecord.class);
        when(user.getEmail()).thenReturn("a@x.com");
        when(suiteUserRepository.findById(1L)).thenReturn(Optional.of(user));
        when(conversationFileService.renameFile("a_Old Name-dev.md", "a@x.com", "New Name"))
                .thenReturn(Optional.of("a_New Name-dev.md"));

        conversationService.renameConversation("ext-1", 1L, "New Name");

        verify(conversationRepository).updateMdFileName(10L, "a_New Name-dev.md");
    }
}
