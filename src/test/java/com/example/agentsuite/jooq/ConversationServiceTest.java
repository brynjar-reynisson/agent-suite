package com.example.agentsuite.jooq;

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
        service.addMessage(guestConvId, guestId, "model_changed",  "deepseek-v4-pro");
        service.addMessage(guestConvId, guestId, "SYSTEM",         "You are a helpful assistant.");
        service.addMessage(guestConvId, guestId, "USER",           "Hello, what can you do?");
        service.addMessage(guestConvId, guestId, "ASSISTANT",      "I can help with many things.");
        service.addMessage(guestConvId, guestId, "model_changed",  "sonnet-4.6");
        service.addMessage(guestConvId, guestId, "SYSTEM",         "You are a concise assistant.");
        service.addMessage(guestConvId, guestId, "USER",           "Summarise that.");
        service.addMessage(guestConvId, guestId, "ASSISTANT",      "I assist.");

        // someone@somewhere.com conversation
        someoneConvId = service.createConversation(someoneId, "Coding Chat", "/code", UUID.randomUUID().toString());
        service.addMessage(someoneConvId, someoneId, "model_changed",  "gemini-2.5-pro");
        service.addMessage(someoneConvId, someoneId, "SYSTEM",         "You are a coding assistant.");
        service.addMessage(someoneConvId, someoneId, "USER",           "Write hello world in Java.");
        service.addMessage(someoneConvId, someoneId, "ASSISTANT",      "System.out.println(\"Hello, world!\");");
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
                .containsExactly("model_changed", "SYSTEM", "USER", "ASSISTANT",
                                  "model_changed", "SYSTEM", "USER", "ASSISTANT");
    }

    @Test
    void modelChangedEventsPreserveValue() {
        List<MessageRecord> modelChanges = service.getMessages(guestConvId).stream()
                .filter(m -> "model_changed".equals(m.getType()))
                .toList();
        assertThat(modelChanges).hasSize(2);
        assertThat(modelChanges.get(0).getMessage()).isEqualTo("deepseek-v4-pro");
        assertThat(modelChanges.get(1).getMessage()).isEqualTo("sonnet-4.6");
    }

    @Test
    void systemMessagePreservesPrompt() {
        List<MessageRecord> systemMsgs = service.getMessages(guestConvId).stream()
                .filter(m -> "SYSTEM".equals(m.getType()))
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
}
