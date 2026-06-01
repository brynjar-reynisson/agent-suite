package com.example.agentsuite.jooq;

import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.service.ConversationService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConversationRepository.class, MessageRepository.class})
@Transactional
@TestPropertySource(properties = "spring.sql.init.mode=always")
class ConversationServiceTest {

    @Autowired ConversationRepository conversationRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired DSLContext dsl;

    private ConversationService service;
    private long guestId;
    private long someoneId;
    private long guestConvId;
    private long someoneConvId;

    @BeforeEach
    void setUp() {
        // All addMessage calls share the same @Transactional now() timestamp in H2,
        // so messages are ordered by message_id ASC (secondary sort) = insertion order.
        // Guest is seeded by schema.sql; add the second user
        dsl.insertInto(SUITE_USER).set(SUITE_USER.UUID, "someone@somewhere.com").execute();
        guestId = dsl.select(SUITE_USER.USER_ID).from(SUITE_USER)
                .where(SUITE_USER.UUID.eq("Guest")).fetchOne(SUITE_USER.USER_ID);
        someoneId = dsl.select(SUITE_USER.USER_ID).from(SUITE_USER)
                .where(SUITE_USER.UUID.eq("someone@somewhere.com")).fetchOne(SUITE_USER.USER_ID);

        service = new ConversationService(conversationRepo, messageRepo);

        // Guest conversation: two model switches, two system prompts, user/assistant pairs
        guestConvId = service.createConversation(guestId, "Guest Chat", "/projects", UUID.randomUUID().toString());
        service.addMessage(guestConvId, guestId, "model_change",  "deepseek-v4-pro");
        service.addMessage(guestConvId, guestId, "system_prompt", "You are a helpful assistant.");
        service.addMessage(guestConvId, guestId, "user",          "Hello, what can you do?");
        service.addMessage(guestConvId, guestId, "assistant",     "I can help with many things.");
        service.addMessage(guestConvId, guestId, "model_change",  "sonnet-4.6");
        service.addMessage(guestConvId, guestId, "system_prompt", "You are a concise assistant.");
        service.addMessage(guestConvId, guestId, "user",          "Summarise that.");
        service.addMessage(guestConvId, guestId, "assistant",     "I assist.");

        // someone@somewhere.com conversation
        someoneConvId = service.createConversation(someoneId, "Coding Chat", "/code", UUID.randomUUID().toString());
        service.addMessage(someoneConvId, someoneId, "model_change",  "gemini-2.5-pro");
        service.addMessage(someoneConvId, someoneId, "system_prompt", "You are a coding assistant.");
        service.addMessage(someoneConvId, someoneId, "user",          "Write hello world in Java.");
        service.addMessage(someoneConvId, someoneId, "assistant",     "System.out.println(\"Hello, world!\");");
    }

    @Test
    void createConversationReturnsId() {
        long id = service.createConversation(guestId, "New Conv", null, UUID.randomUUID().toString());
        assertThat(id).isPositive();
    }

    @Test
    void getConversationReturnsRecord() {
        var rec = service.getConversation(guestConvId);
        assertThat(rec.getConversationName()).isEqualTo("Guest Chat");
        assertThat(rec.getRootDirectory()).isEqualTo("/projects");
    }

    @Test
    void getConversationThrowsForUnknownId() {
        assertThrows(IllegalArgumentException.class, () -> service.getConversation(-1L));
    }

    @Test
    void guestMessageHistoryHasCorrectCount() {
        assertThat(service.getMessages(guestConvId)).hasSize(8);
    }

    @Test
    void guestMessageHistoryInOrder() {
        List<MessageRecord> msgs = service.getMessages(guestConvId);
        assertThat(msgs).extracting(MessageRecord::getType)
                .containsExactly("model_change", "system_prompt", "user", "assistant",
                                  "model_change", "system_prompt", "user", "assistant");
    }

    @Test
    void modelChangedEventsPreserveValue() {
        List<MessageRecord> modelChanges = service.getMessages(guestConvId).stream()
                .filter(m -> "model_change".equals(m.getType()))
                .toList();
        assertThat(modelChanges).hasSize(2);
        assertThat(modelChanges.get(0).getMessage()).isEqualTo("deepseek-v4-pro");
        assertThat(modelChanges.get(1).getMessage()).isEqualTo("sonnet-4.6");
    }

    @Test
    void systemMessagePreservesPrompt() {
        List<MessageRecord> systemMsgs = service.getMessages(guestConvId).stream()
                .filter(m -> "system_prompt".equals(m.getType()))
                .toList();
        assertThat(systemMsgs).hasSize(2);
        assertThat(systemMsgs.get(0).getMessage()).isEqualTo("You are a helpful assistant.");
        assertThat(systemMsgs.get(1).getMessage()).isEqualTo("You are a concise assistant.");
    }

    @Test
    void someoneConversationIsIsolated() {
        List<MessageRecord> msgs = service.getMessages(someoneConvId);
        assertThat(msgs).hasSize(4);
        assertThat(msgs).noneMatch(m -> m.getUserId().equals(guestId));
    }

    @Test
    void getConversationSummaries_returnsGuestConversations() {
        List<ConversationSummaryDto> summaries = service.getConversationSummaries();
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.get(0).name()).isEqualTo("Guest Chat");
        assertThat(summaries.get(0).initialModel()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void getConversationDetail_throwsForUnknownExternalId() {
        assertThrows(NoSuchElementException.class,
                () -> service.getConversationDetail("no-such-uuid"));
    }

    @Test
    void getConversationDetail_reconstructsUserAndAssistantMessages() {
        String extId = UUID.randomUUID().toString();
        long convId = service.createConversation(guestId, "Test conv", null, extId);
        service.addMessage(convId, guestId, "model_change", "deepseek-v4-pro");
        service.addMessage(convId, guestId, "system_prompt", "Be helpful.");
        service.addMessage(convId, guestId, "user", "Hello!");
        service.addMessage(convId, guestId, "assistant", "Hi there!");

        ConversationDetailDto detail = service.getConversationDetail(extId);

        assertThat(detail.externalId()).isEqualTo(extId);
        assertThat(detail.initialModel()).isEqualTo("deepseek-v4-pro");
        assertThat(detail.systemPrompt()).isEqualTo("Be helpful.");
        assertThat(detail.messages()).hasSize(2);
        assertThat(detail.messages().get(0).role()).isEqualTo("user");
        assertThat(detail.messages().get(0).content()).isEqualTo("Hello!");
        assertThat(detail.messages().get(1).role()).isEqualTo("ai");
        assertThat(detail.messages().get(1).content()).isEqualTo("Hi there!");
        assertThat(detail.messages().get(1).toolCalls()).isEmpty();
    }

    @Test
    void getConversationDetail_groupsToolCallsWithFollowingAssistantMessage() {
        String extId = UUID.randomUUID().toString();
        long convId = service.createConversation(guestId, "Tool conv", null, extId);
        service.addMessage(convId, guestId, "model_change", "deepseek-v4-pro");
        service.addMessage(convId, guestId, "system_prompt", "");
        service.addMessage(convId, guestId, "user", "List files");
        service.addMessage(convId, guestId, "tool_call",
                "[{\"name\":\"ls\",\"arguments\":\"{}\"}]");
        service.addMessage(convId, guestId, "tool_result",
                "[{\"name\":\"ls\",\"result\":\"file.txt\"}]");
        service.addMessage(convId, guestId, "assistant", "Here are the files.");

        ConversationDetailDto detail = service.getConversationDetail(extId);

        assertThat(detail.messages()).hasSize(2);
        ConversationDetailDto.MessageDto aiMsg = detail.messages().get(1);
        assertThat(aiMsg.role()).isEqualTo("ai");
        assertThat(aiMsg.content()).isEqualTo("Here are the files.");
        assertThat(aiMsg.toolCalls()).hasSize(1);
        assertThat(aiMsg.toolCalls().get(0).name()).isEqualTo("ls");
        assertThat(aiMsg.toolCalls().get(0).arguments()).isEqualTo("{}");
    }
}
