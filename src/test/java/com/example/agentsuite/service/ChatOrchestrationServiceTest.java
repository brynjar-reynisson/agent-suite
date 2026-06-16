package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.service.ConversationService;
import com.example.agentsuite.service.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        orchestration.chatStream(null, 1L, "deepseek-v4-pro", "Be helpful", "Hi", "", null,
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
                "/projects", null, events::add, new Object[0]);

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
                "/projects", null, e -> {}, new Object[0]);

        @SuppressWarnings("unchecked")
        var historyCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService).chatStreamWithHistory(historyCaptor.capture(), eq("Hello"), any());
        List<HistoryMessage> history = historyCaptor.getValue();
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
        assertThat(((HistoryMessage.SystemPrompt) history.get(0)).content())
                .isEqualTo("Be helpful\nWorking directory: /projects");
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
                "/projects", null, e -> {}, new Object[0]);

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
                "/projects", null, e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("model_change"), eq("sonnet-4.6"));
    }

    private MessageRecord rec(String type, String message) {
        MessageRecord r = mock(MessageRecord.class);
        when(r.getType()).thenReturn(type);
        when(r.getMessage()).thenReturn(message);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<HistoryMessage> captureHistory(String externalId, long convDbId,
                                                 List<MessageRecord> messages) {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of("sys"));
        when(conversationService.getMessages(convDbId)).thenReturn(messages);

        doAnswer(inv -> {
            Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("ok"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "sys", "q", "", null, e -> {}, new Object[0]);

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService, atLeastOnce()).chatStreamWithHistory(captor.capture(), any(), any());
        return captor.getValue();
    }

    @Test
    void loadHistory_noCompact_includesAllSubstantiveMessages() {
        String externalId = UUID.randomUUID().toString();
        List<MessageRecord> msgs = List.of(
            rec("system_prompt", "sys"),
            rec("user", "hello"),
            rec("assistant", "hi")
        );
        List<HistoryMessage> history = captureHistory(externalId, 50L, msgs);
        assertThat(history).hasSize(3);
        assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
        assertThat(history.get(1)).isInstanceOf(HistoryMessage.User.class);
        assertThat(history.get(2)).isInstanceOf(HistoryMessage.Assistant.class);
    }

    @Test
    void loadHistory_compactInMiddle_dropsMessagesBeforeCompactAndEmitsCompactAsUser() {
        String externalId = UUID.randomUUID().toString();
        List<MessageRecord> msgs = List.of(
            rec("system_prompt", "sys"),
            rec("user", "old message"),
            rec("assistant", "old reply"),
            rec("compact", "summary of earlier"),
            rec("user", "new question"),
            rec("assistant", "new answer")
        );
        List<HistoryMessage> history = captureHistory(externalId, 51L, msgs);
        // SystemPrompt + User(compact) + User(new) + Assistant(new)
        assertThat(history).hasSize(4);
        assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
        assertThat(history.get(1)).isInstanceOf(HistoryMessage.User.class);
        assertThat(((HistoryMessage.User) history.get(1)).content())
                .startsWith("Previous conversation summary:\n\nsummary of earlier");
        assertThat(history.get(2)).isInstanceOf(HistoryMessage.User.class);
        assertThat(((HistoryMessage.User) history.get(2)).content()).isEqualTo("new question");
        assertThat(history.get(3)).isInstanceOf(HistoryMessage.Assistant.class);
    }

    @Test
    void loadHistory_multipleCompacts_usesOnlyMostRecent() {
        String externalId = UUID.randomUUID().toString();
        List<MessageRecord> msgs = List.of(
            rec("compact", "first summary"),
            rec("user", "middle message"),
            rec("compact", "second summary"),
            rec("user", "latest question")
        );
        List<HistoryMessage> history = captureHistory(externalId, 52L, msgs);
        // No SystemPrompt (empty), User(compact2), User(latest)
        assertThat(history).hasSize(2);
        assertThat(((HistoryMessage.User) history.get(0)).content())
                .startsWith("Previous conversation summary:\n\nsecond summary");
        assertThat(((HistoryMessage.User) history.get(1)).content()).isEqualTo("latest question");
    }

    // --- compact() tests ---

    @Test
    void compact_conversationNotFound_throwsNoSuchElement() {
        when(conversationService.findByExternalId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> orchestration.compact("missing", 1L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void compact_wrongOwner_throwsNoSuchElement() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(99L);
        when(conv.getUserId()).thenReturn(2L); // different user
        when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
        assertThatThrownBy(() -> orchestration.compact("abc", 1L))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void compact_emptyHistory_throwsIllegalArgument() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(20L);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
        List<MessageRecord> emptyMsgs = List.of(
            rec("system_prompt", "be helpful"),
            rec("model_change", "deepseek-v4-pro")
        );
        when(conversationService.getMessages(20L)).thenReturn(emptyMsgs);
        assertThatThrownBy(() -> orchestration.compact("abc", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing to compact");
    }

    @Test
    void compact_validConversation_callsLlmAndStoresSummary() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(21L);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
        List<MessageRecord> validMsgs = List.of(
            rec("user", "Hello"),
            rec("assistant", "Hi there")
        );
        when(conversationService.getMessages(21L)).thenReturn(validMsgs);
        when(conversationService.findLastModelChange(21L)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(chatService.chat(anyString(), anyString())).thenReturn(ChatResponse.of("Compact summary"));

        String result = orchestration.compact("abc", 1L);

        assertThat(result).isEqualTo("Compact summary");
        verify(conversationService).addMessage(21L, 1L, "compact", "Compact summary");
    }

    @Test
    void buildTranscript_includesUserAssistantAndCompactRecords() {
        List<MessageRecord> msgs = List.of(
            rec("system_prompt", "ignored"),
            rec("model_change", "ignored"),
            rec("user", "hi"),
            rec("assistant", "hello"),
            rec("compact", "earlier summary"),
            rec("tool_call", "[{\"name\":\"ls\"}]"),
            rec("tool_result", "[{\"result\":\"file\"}]")
        );
        String transcript = ChatOrchestrationService.buildTranscript(msgs);
        assertThat(transcript).contains("[User]: hi");
        assertThat(transcript).contains("[Assistant]: hello");
        assertThat(transcript).contains("[Summary]: earlier summary");
        assertThat(transcript).contains("[Tool call]:");
        assertThat(transcript).contains("[Tool result]:");
        assertThat(transcript).doesNotContain("ignored");
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
                "/projects", null, e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_call"),
                argThat(json -> json.contains("\"name\":\"ls\"")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_result"),
                argThat(json -> json.contains("file1")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("assistant"),
                eq("Here are the files."));
    }

    @Test
    void errorEventPersistsPartialTurnProgress() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 15L;
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.ToolBatch(List.of(
                    new ChatEvent.ToolBatch.ToolExecution("ls", "{}", "file1"))));
            emitter.accept(new ChatEvent.Error("Tool timed out"));
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "List files",
                "", null, e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_call"),
                argThat(json -> json.contains("\"name\":\"ls\"")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_result"),
                argThat(json -> json.contains("file1")));
        verify(conversationService, never()).addMessage(eq(convDbId), eq(1L), eq("assistant"), any());
    }

    @Test
    void duplicateRequestId_skipsUserMessageInsert() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 16L;
        String requestId = UUID.randomUUID().toString();
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Hi!"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Hello",
                "", requestId, e -> {}, new Object[0]);
        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Hello",
                "", requestId, e -> {}, new Object[0]);

        verify(conversationService, times(1)).addMessage(eq(convDbId), eq(1L), eq("user"), eq("Hello"));
        verify(chatService, times(1)).chatStreamWithHistory(any(), any(), any());
    }

    @Test
    void loadHistory_rootDirectory_appendedFreshToSystemPromptForLlm() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 17L;
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conv.getUserId()).thenReturn(1L);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of("Be helpful"));

        MessageRecord sysRecord = mock(MessageRecord.class);
        when(sysRecord.getType()).thenReturn("system_prompt");
        when(sysRecord.getMessage()).thenReturn("Be helpful");
        when(conversationService.getMessages(convDbId)).thenReturn(List.of(sysRecord));

        doAnswer(inv -> {
            Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any());

        orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "q",
                "/projects", null, e -> {}, new Object[0]);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService).chatStreamWithHistory(captor.capture(), eq("q"), any());
        HistoryMessage.SystemPrompt sp = (HistoryMessage.SystemPrompt) captor.getValue().get(0);
        assertThat(sp.content()).isEqualTo("Be helpful\nWorking directory: /projects");
    }

    @Test
    void applyWorkingDirectory_variousCombinations() {
        assertThat(ChatOrchestrationService.applyWorkingDirectory("", "")).isEqualTo("");
        assertThat(ChatOrchestrationService.applyWorkingDirectory("Be helpful", ""))
                .isEqualTo("Be helpful");
        assertThat(ChatOrchestrationService.applyWorkingDirectory("", "/p"))
                .isEqualTo("Working directory: /p");
        assertThat(ChatOrchestrationService.applyWorkingDirectory("Be helpful", "/p"))
                .isEqualTo("Be helpful\nWorking directory: /p");
    }
}
