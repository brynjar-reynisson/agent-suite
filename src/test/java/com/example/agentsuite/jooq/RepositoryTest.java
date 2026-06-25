package com.example.agentsuite.jooq;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.example.agentsuite.jooq.generated.Tables.MESSAGE;
import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SuiteUserRepository.class, ConversationRepository.class, MessageRepository.class})
@TestPropertySource(properties = "spring.sql.init.mode=always")
@Transactional
class RepositoryTest {

    @Autowired SuiteUserRepository suiteUserRepo;
    @Autowired ConversationRepository conversationRepo;
    @Autowired MessageRepository messageRepo;
    @Autowired DSLContext dsl;

    private long guestId;
    private long someoneId;

    @BeforeEach
    void setUp() {
        dsl.insertInto(SUITE_USER).set(SUITE_USER.UUID, "someone@somewhere.com").execute();
        guestId = suiteUserRepo.findByUuid("Guest").orElseThrow().getUserId();
        someoneId = suiteUserRepo.findByUuid("someone@somewhere.com").orElseThrow().getUserId();
    }

    @Test
    void findByUuidGuestReturnsGuestUser() {
        assertThat(suiteUserRepo.findByUuid("Guest"))
                .isPresent()
                .hasValueSatisfying(u -> assertThat(u.getUuid()).isEqualTo("Guest"));
    }

    @Test
    void findByUuidReturnsCorrectUser() {
        assertThat(suiteUserRepo.findByUuid("someone@somewhere.com"))
                .isPresent()
                .hasValueSatisfying(u -> assertThat(u.getUuid()).isEqualTo("someone@somewhere.com"));
    }

    @Test
    void findByUuidUnknownReturnsEmpty() {
        assertThat(suiteUserRepo.findByUuid("nobody")).isEmpty();
    }

    @Test
    void insertConversationReturnsId() {
        long id = conversationRepo.insert(guestId, "Test Conv", "/home", UUID.randomUUID().toString());
        assertThat(id).isPositive();
    }

    @Test
    void findConversationByIdRoundTrip() {
        long id = conversationRepo.insert(guestId, "My Conv", "/projects", UUID.randomUUID().toString());
        ConversationRecord rec = conversationRepo.findById(id).orElseThrow();
        assertThat(rec.getConversationName()).isEqualTo("My Conv");
        assertThat(rec.getRootDirectory()).isEqualTo("/projects");
    }

    @Test
    void findConversationsByUserId() {
        conversationRepo.insert(guestId, "Conv A", null, UUID.randomUUID().toString());
        conversationRepo.insert(guestId, "Conv B", null, UUID.randomUUID().toString());
        assertThat(conversationRepo.findByUserId(guestId)).hasSize(2);
        assertThat(conversationRepo.findByUserId(someoneId)).isEmpty();
    }

    @Test
    void insertMessagesReturnedInOrder() {
        long convId = conversationRepo.insert(guestId, "Order Test", null, UUID.randomUUID().toString());
        OffsetDateTime t1 = OffsetDateTime.now().minusSeconds(2);
        OffsetDateTime t2 = OffsetDateTime.now().minusSeconds(1);
        OffsetDateTime t3 = OffsetDateTime.now();
        // Insert out of order to verify sorting, not insertion sequence
        dsl.insertInto(MESSAGE)
                .set(MESSAGE.CONVERSATION_ID, convId).set(MESSAGE.USER_ID, guestId)
                .set(MESSAGE.TYPE, "USER").set(MESSAGE.MESSAGE_, "first")
                .set(MESSAGE.MESSAGE_TIME, t1).execute();
        dsl.insertInto(MESSAGE)
                .set(MESSAGE.CONVERSATION_ID, convId).set(MESSAGE.USER_ID, guestId)
                .set(MESSAGE.TYPE, "ASSISTANT").set(MESSAGE.MESSAGE_, "third")
                .set(MESSAGE.MESSAGE_TIME, t3).execute();
        dsl.insertInto(MESSAGE)
                .set(MESSAGE.CONVERSATION_ID, convId).set(MESSAGE.USER_ID, guestId)
                .set(MESSAGE.TYPE, "USER").set(MESSAGE.MESSAGE_, "second")
                .set(MESSAGE.MESSAGE_TIME, t2).execute();
        List<MessageRecord> messages = messageRepo.findByConversationId(convId);
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).getMessage()).isEqualTo("first");
        assertThat(messages.get(1).getMessage()).isEqualTo("second");
        assertThat(messages.get(2).getMessage()).isEqualTo("third");
    }

    @Test
    void messageTypesRoundTrip() {
        long convId = conversationRepo.insert(guestId, "Types Test", null, UUID.randomUUID().toString());
        messageRepo.insert(convId, guestId, "model_change", "deepseek-v4-pro");
        messageRepo.insert(convId, guestId, "SYSTEM", "You are helpful.");
        messageRepo.insert(convId, guestId, "USER", "Hi");
        messageRepo.insert(convId, guestId, "ASSISTANT", "Hello!");
        List<MessageRecord> msgs = messageRepo.findByConversationId(convId);
        assertThat(msgs).hasSize(4);
        assertThat(msgs).extracting(MessageRecord::getType)
                .containsExactlyInAnyOrder("model_change", "SYSTEM", "USER", "ASSISTANT");
    }

    @Test
    void findConversationByExternalId() {
        long id = conversationRepo.insert(guestId, "Ext Conv", "/home", "ext-uuid-123");
        assertThat(conversationRepo.findByExternalId("ext-uuid-123"))
                .isPresent()
                .hasValueSatisfying(r -> assertThat(r.getConversationName()).isEqualTo("Ext Conv"));
    }

    @Test
    void findConversationByExternalIdMissingReturnsEmpty() {
        assertThat(conversationRepo.findByExternalId("no-such-uuid")).isEmpty();
    }

    @Test
    void findLastModelChangeReturnsLatest() {
        long convId = conversationRepo.insert(guestId, "Model Test", null, "uuid-model-test");
        messageRepo.insert(convId, guestId, "model_change", "deepseek-v4-pro");
        messageRepo.insert(convId, guestId, "model_change", "sonnet-4.6");
        assertThat(messageRepo.findLastModelChange(convId))
                .isPresent()
                .hasValue("sonnet-4.6");
    }

    @Test
    void findLastModelChangeEmptyWhenNone() {
        long convId = conversationRepo.insert(guestId, "No Model", null, "uuid-no-model");
        assertThat(messageRepo.findLastModelChange(convId)).isEmpty();
    }

    @Test
    void findByConversationId_excludesErasedMessages() {
        long convId = conversationRepo.insert(guestId, "Erase Test", null, UUID.randomUUID().toString());
        messageRepo.insert(convId, guestId, "user", "hello");
        messageRepo.insert(convId, guestId, "assistant", "hi");
        // Manually erase the assistant message directly via DSL
        dsl.update(MESSAGE).set(MESSAGE.ERASED, true)
                .where(MESSAGE.CONVERSATION_ID.eq(convId))
                .and(MESSAGE.TYPE.eq("assistant"))
                .execute();

        List<MessageRecord> result = messageRepo.findByConversationId(convId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessage()).isEqualTo("hello");
    }

    @Test
    void eraseLastTurn_erasesUserAndSubsequentMessages() {
        long convId = conversationRepo.insert(guestId, "EraseLastTurn Test", null, UUID.randomUUID().toString());
        messageRepo.insert(convId, guestId, "user", "first");
        messageRepo.insert(convId, guestId, "assistant", "reply");
        messageRepo.insert(convId, guestId, "user", "second");
        messageRepo.insert(convId, guestId, "tool_call", "[{}]");
        messageRepo.insert(convId, guestId, "assistant", "done");

        messageRepo.eraseLastTurn(convId);

        List<MessageRecord> remaining = dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(convId))
                .and(MESSAGE.ERASED.isFalse())
                .fetch();
        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(MessageRecord::getMessage)
                .containsExactly("first", "reply");
    }

    @Test
    void eraseLastTurn_throwsWhenNoUserMessage() {
        long convId = conversationRepo.insert(guestId, "NoUser Test", null, UUID.randomUUID().toString());
        messageRepo.insert(convId, guestId, "assistant", "hi");

        assertThatThrownBy(() -> messageRepo.eraseLastTurn(convId))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
