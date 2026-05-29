# jOOQ Repository and Service Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add H2-backed unit tests for `SuiteUserRepository`, `ConversationRepository`, `MessageRepository`, and `ConversationService` covering realistic message histories with `model_changed` and `SYSTEM` message types.

**Architecture:** Spring Boot's `@JooqTest` slice auto-replaces the configured PostgreSQL datasource with H2 and runs `schema.sql` on startup. Both test classes use `@Transactional` so each test method rolls back, giving per-test isolation. `MessageRepository.findByConversationId` is patched to add a secondary `MESSAGE_ID ASC` sort so insertion order is stable even when H2 assigns identical timestamps to fast sequential inserts.

**Tech Stack:** Spring Boot 3.5 / JUnit 5 / AssertJ / jOOQ 3.19 / H2 2.x (embedded, managed by Spring Boot BOM)

---

### Task 1: Add H2 dependency and schema.sql

**Files:**
- Modify: `pom.xml`
- Create: `src/test/resources/schema.sql`

- [ ] **Step 1: Add H2 test dependency to pom.xml**

Inside `<dependencies>` in `pom.xml`, after the `spring-boot-starter-test` dependency, add:

```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

No version — managed by the Spring Boot BOM.

- [ ] **Step 2: Create src/test/resources/schema.sql**

Create `src/test/resources/schema.sql`:

```sql
CREATE SEQUENCE suite_user_id_seq START WITH 1;

CREATE TABLE suite_user (
    user_id BIGINT NOT NULL DEFAULT nextval('suite_user_id_seq'),
    uuid    TEXT   NOT NULL,
    CONSTRAINT pk_suite_user PRIMARY KEY (user_id)
);

INSERT INTO suite_user (uuid) VALUES ('Guest');

CREATE SEQUENCE conversation_id_seq START WITH 1;

CREATE TABLE conversation (
    conversation_id   BIGINT                   NOT NULL DEFAULT nextval('conversation_id_seq'),
    user_id           BIGINT                   NOT NULL,
    conversation_name TEXT                     NOT NULL,
    create_time       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    root_directory    TEXT,
    CONSTRAINT pk_conversation PRIMARY KEY (conversation_id),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id)
);

CREATE SEQUENCE message_id_seq START WITH 1;

CREATE TABLE message (
    message_id      BIGINT                   NOT NULL DEFAULT nextval('message_id_seq'),
    user_id         BIGINT                   NOT NULL,
    conversation_id BIGINT                   NOT NULL,
    type            TEXT                     NOT NULL,
    message         TEXT                     NOT NULL,
    message_time    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_message PRIMARY KEY (message_id),
    CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES message (message_id)
);
```

Wait — the FK on message must reference `conversation`, not `message`. Use this corrected version:

```sql
CREATE SEQUENCE suite_user_id_seq START WITH 1;

CREATE TABLE suite_user (
    user_id BIGINT NOT NULL DEFAULT nextval('suite_user_id_seq'),
    uuid    TEXT   NOT NULL,
    CONSTRAINT pk_suite_user PRIMARY KEY (user_id)
);

INSERT INTO suite_user (uuid) VALUES ('Guest');

CREATE SEQUENCE conversation_id_seq START WITH 1;

CREATE TABLE conversation (
    conversation_id   BIGINT                   NOT NULL DEFAULT nextval('conversation_id_seq'),
    user_id           BIGINT                   NOT NULL,
    conversation_name TEXT                     NOT NULL,
    create_time       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    root_directory    TEXT,
    CONSTRAINT pk_conversation PRIMARY KEY (conversation_id),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id)
);

CREATE SEQUENCE message_id_seq START WITH 1;

CREATE TABLE message (
    message_id      BIGINT                   NOT NULL DEFAULT nextval('message_id_seq'),
    user_id         BIGINT                   NOT NULL,
    conversation_id BIGINT                   NOT NULL,
    type            TEXT                     NOT NULL,
    message         TEXT                     NOT NULL,
    message_time    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT pk_message PRIMARY KEY (message_id),
    CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES suite_user (user_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (conversation_id)
);
```

- [ ] **Step 3: Verify compilation**

```bash
./mvnw.cmd compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/resources/schema.sql
git commit -m "test: add H2 dependency and schema.sql for jOOQ slice tests"
```

---

### Task 2: Fix MessageRepository secondary sort

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`

H2 in-memory inserts can complete within the same millisecond, making `ORDER BY message_time ASC` non-deterministic when timestamps collide. Adding `message_id ASC` as a tie-breaker guarantees insertion order, which is also the correct production behaviour.

- [ ] **Step 1: Add secondary sort to findByConversationId**

In `MessageRepository.java`, change the `findByConversationId` method from:

```java
    public List<MessageRecord> findByConversationId(long conversationId) {
        return dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .orderBy(MESSAGE.MESSAGE_TIME.asc())
                .fetch();
    }
```

To:

```java
    public List<MessageRecord> findByConversationId(long conversationId) {
        return dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .orderBy(MESSAGE.MESSAGE_TIME.asc(), MESSAGE.MESSAGE_ID.asc())
                .fetch();
    }
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw.cmd compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git commit -m "fix: add message_id as secondary sort in findByConversationId for stable ordering"
```

---

### Task 3: RepositoryTest

**Files:**
- Create: `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`

- [ ] **Step 1: Create RepositoryTest.java**

Create `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`:

```java
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
```

- [ ] **Step 2: Run the new tests**

```bash
./mvnw.cmd test -pl . -Dtest=RepositoryTest -q
```

Expected: `BUILD SUCCESS`, 8 tests passing.

- [ ] **Step 3: Run the full suite to check for regressions**

```bash
./mvnw.cmd test -q
```

Expected: `BUILD SUCCESS`, all tests passing (76 existing + 8 new = 84 total).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/agentsuite/jooq/RepositoryTest.java
git commit -m "test: add RepositoryTest for SuiteUser, Conversation, and Message repositories"
```

---

### Task 4: ConversationServiceTest

**Files:**
- Create: `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

- [ ] **Step 1: Create ConversationServiceTest.java**

Create `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`:

```java
package com.example.agentsuite.jooq;

import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.service.ConversationService;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JooqTest
@Import({ConversationRepository.class, MessageRepository.class})
@Transactional
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
        // Guest is seeded by schema.sql; add the second user
        dsl.insertInto(SUITE_USER).set(SUITE_USER.UUID, "someone@somewhere.com").execute();
        guestId = dsl.select(SUITE_USER.USER_ID).from(SUITE_USER)
                .where(SUITE_USER.UUID.eq("Guest")).fetchOne(SUITE_USER.USER_ID);
        someoneId = dsl.select(SUITE_USER.USER_ID).from(SUITE_USER)
                .where(SUITE_USER.UUID.eq("someone@somewhere.com")).fetchOne(SUITE_USER.USER_ID);

        service = new ConversationService(conversationRepo, messageRepo);

        // Guest conversation: two model switches, two system prompts, user/assistant pairs
        guestConvId = service.createConversation(guestId, "Guest Chat", "/projects");
        service.addMessage(guestConvId, guestId, "model_changed",  "deepseek-v4-pro");
        service.addMessage(guestConvId, guestId, "SYSTEM",         "You are a helpful assistant.");
        service.addMessage(guestConvId, guestId, "USER",           "Hello, what can you do?");
        service.addMessage(guestConvId, guestId, "ASSISTANT",      "I can help with many things.");
        service.addMessage(guestConvId, guestId, "model_changed",  "sonnet-4.6");
        service.addMessage(guestConvId, guestId, "SYSTEM",         "You are a concise assistant.");
        service.addMessage(guestConvId, guestId, "USER",           "Summarise that.");
        service.addMessage(guestConvId, guestId, "ASSISTANT",      "I assist.");

        // someone@somewhere.com conversation
        someoneConvId = service.createConversation(someoneId, "Coding Chat", "/code");
        service.addMessage(someoneConvId, someoneId, "model_changed",  "gemini-2.5-pro");
        service.addMessage(someoneConvId, someoneId, "SYSTEM",         "You are a coding assistant.");
        service.addMessage(someoneConvId, someoneId, "USER",           "Write hello world in Java.");
        service.addMessage(someoneConvId, someoneId, "ASSISTANT",      "System.out.println(\"Hello, world!\");");
    }

    @Test
    void createConversationReturnsId() {
        long id = service.createConversation(guestId, "New Conv", null);
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
        assertThat(msgs.get(0).getType()).isEqualTo("model_changed");
        assertThat(msgs.get(msgs.size() - 1).getType()).isEqualTo("ASSISTANT");
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
```

- [ ] **Step 2: Run the new tests**

```bash
./mvnw.cmd test -pl . -Dtest=ConversationServiceTest -q
```

Expected: `BUILD SUCCESS`, 8 tests passing.

- [ ] **Step 3: Run the full suite**

```bash
./mvnw.cmd test -q
```

Expected: `BUILD SUCCESS`, all tests passing (84 existing + 8 new = 92 total).

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java
git commit -m "test: add ConversationServiceTest with Guest and someone@somewhere.com message histories"
```
