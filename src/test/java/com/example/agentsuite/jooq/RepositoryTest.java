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
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.example.agentsuite.jooq.generated.Tables.MESSAGE;
import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;
import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@Import({SuiteUserRepository.class, ConversationRepository.class, MessageRepository.class})
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
        guestId = suiteUserRepo.findGuest().orElseThrow().getUserId();
        someoneId = suiteUserRepo.findByUuid("someone@somewhere.com").orElseThrow().getUserId();
    }

    @Test
    void findGuestReturnsGuestUser() {
        assertThat(suiteUserRepo.findGuest())
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
        long id = conversationRepo.insert(guestId, "Test Conv", "/home");
        assertThat(id).isPositive();
    }

    @Test
    void findConversationByIdRoundTrip() {
        long id = conversationRepo.insert(guestId, "My Conv", "/projects");
        ConversationRecord rec = conversationRepo.findById(id).orElseThrow();
        assertThat(rec.getConversationName()).isEqualTo("My Conv");
        assertThat(rec.getRootDirectory()).isEqualTo("/projects");
    }

    @Test
    void findConversationsByUserId() {
        conversationRepo.insert(guestId, "Conv A", null);
        conversationRepo.insert(guestId, "Conv B", null);
        assertThat(conversationRepo.findByUserId(guestId)).hasSize(2);
        assertThat(conversationRepo.findByUserId(someoneId)).isEmpty();
    }

    @Test
    void insertMessagesReturnedInOrder() {
        long convId = conversationRepo.insert(guestId, "Order Test", null);
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
        long convId = conversationRepo.insert(guestId, "Types Test", null);
        messageRepo.insert(convId, guestId, "model_changed", "deepseek-v4-pro");
        messageRepo.insert(convId, guestId, "SYSTEM", "You are helpful.");
        messageRepo.insert(convId, guestId, "USER", "Hi");
        messageRepo.insert(convId, guestId, "ASSISTANT", "Hello!");
        List<MessageRecord> msgs = messageRepo.findByConversationId(convId);
        assertThat(msgs).hasSize(4);
        assertThat(msgs).extracting(MessageRecord::getType)
                .containsExactly("model_changed", "SYSTEM", "USER", "ASSISTANT");
    }
}
