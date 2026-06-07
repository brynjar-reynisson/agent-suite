package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatOrchestrationServiceTest {

    private ModelRegistry modelRegistry;
    private ConversationService conversationService;
    private ChatService chatService;
    private ChatOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        modelRegistry = mock(ModelRegistry.class);
        conversationService = mock(ConversationService.class);
        chatService = mock(ChatService.class);
        when(modelRegistry.get(anyString())).thenReturn(chatService);
        orchestration = new ChatOrchestrationService(modelRegistry, conversationService);
    }

    @Test
    void statelessMode_whenNoConversationId_doesNotInteractWithDb() {
        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("hello"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStream(any(), any(), any());

        List<ChatEvent> events = new ArrayList<>();
        orchestration.chatStream(null, 1L, "deepseek-v4-pro", "Be helpful", "Hi", "",
                events::add, new Object[0]);

        verifyNoInteractions(conversationService);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ChatEvent.Content.class);
    }

    @Test
    void firstTurn_createsConversationAndInsertsMetadata() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 42L;

        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.empty());
        when(conversationService.createConversation(anyLong(), anyString(), anyString(), eq(externalId)))
                .thenReturn(convDbId);
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Hello!"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        List<ChatEvent> events = new ArrayList<>();
        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "Hello",
                "/projects", events::add, new Object[0]);

        verify(conversationService).createConversation(eq(1L), eq("Hello"), eq("/projects"), eq(externalId));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("model_change"), eq("deepseek-v4-pro"));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("system_prompt"), eq("Be helpful"));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("user"), eq("Hello"));
    }

    @Test
    void firstTurn_historyPassedToLlmContainsOnlySystemPrompt() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 7L;

        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.empty());
        when(conversationService.createConversation(anyLong(), anyString(), anyString(), eq(externalId)))
                .thenReturn(convDbId);
        // DB has system_prompt after resolveConversation; loaded before user insert
        MessageRecord sysRecord = mock(MessageRecord.class);
        when(sysRecord.getType()).thenReturn("system_prompt");
        when(sysRecord.getMessage()).thenReturn("Be helpful");
        when(conversationService.getMessages(convDbId)).thenReturn(List.of(sysRecord));

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Hi!"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "Hello",
                "/projects", e -> {}, new Object[0]);

        @SuppressWarnings("unchecked")
        var historyCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService).chatStreamWithHistory(historyCaptor.capture(), eq("Hello"), any());
        List<HistoryMessage> history = historyCaptor.getValue();
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
        assertThat(((HistoryMessage.SystemPrompt) history.get(0)).content()).isEqualTo("Be helpful");
    }

    @Test
    void subsequentTurn_sameModel_noModelChangeInserted() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 9L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("OK"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Follow up",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService, never()).addMessage(eq(convDbId), anyLong(), eq("model_change"), any());
        verify(conversationService, never()).addMessage(eq(convDbId), anyLong(), eq("system_prompt"), any());
    }

    @Test
    void subsequentTurn_modelChanged_insertsModelChange() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 11L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Got it"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "sonnet-4.6", "", "Continue",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("model_change"), eq("sonnet-4.6"));
    }

    @Test
    void toolBatch_persistedAsToolCallAndToolResult() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 13L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.ToolBatch(List.of(
                    new ChatEvent.ToolBatch.ToolExecution("ls", "{\"path\":\".\"}",  "file1\nfile2")
            )));
            emitter.accept(new ChatEvent.Content("Here are the files."));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "List files",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_call"),
                argThat(json -> json.contains("\"name\":\"ls\"")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_result"),
                argThat(json -> json.contains("file1")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("assistant"),
                eq("Here are the files."));
    }
}
