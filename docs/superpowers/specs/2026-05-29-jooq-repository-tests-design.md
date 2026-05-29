# jOOQ Repository and Service Tests Design

**Date:** 2026-05-29
**Branch:** feature/apply_jooq_orm

## Goal

Add unit tests for the jOOQ persistence layer covering `SuiteUserRepository`, `ConversationRepository`, `MessageRepository`, and `ConversationService`. Tests use H2 in-memory database via Spring Boot's `@JooqTest` slice and exercise realistic message histories including `model_changed` and `SYSTEM` message types.

## Infrastructure

### H2 Dependency

Add to `pom.xml` in the test-scoped dependencies block:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

No version needed — managed by the Spring Boot BOM.

### Schema File

Create `src/test/resources/schema.sql` with H2-compatible DDL. Key differences from the PostgreSQL migrations:
- `START WITH 1` instead of `START 1` (H2 syntax)
- No `ALTER SEQUENCE ... OWNED BY` (unsupported in H2)
- Includes the Guest user seed row

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

### @JooqTest Slice

`@JooqTest` (Spring Boot 3.5) auto-configures:
- An H2 in-memory embedded datasource (replaces any configured datasource)
- jOOQ `DSLContext` with H2 dialect
- Automatic schema initialisation from `schema.sql`

The `@Repository` beans are not picked up by `@JooqTest` automatically — they are imported explicitly via `@Import` on each test class.

## Test Classes

Both test classes live under `src/test/java/com/example/agentsuite/jooq/`.

### RepositoryTest

`src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`

Annotation: `@JooqTest @Import({SuiteUserRepository.class, ConversationRepository.class, MessageRepository.class})`

**@BeforeEach:** Insert `someone@somewhere.com` into `suite_user` so both users are available for every test.

**Test cases:**

| Test | What it verifies |
|------|-----------------|
| `findGuestReturnsGuestUser` | `findGuest()` returns Optional with uuid = "Guest" |
| `findByUuidReturnsCorrectUser` | `findByUuid("someone@somewhere.com")` returns that user |
| `findByUuidUnknownReturnsEmpty` | `findByUuid("nobody")` returns `Optional.empty()` |
| `insertConversationReturnsId` | `ConversationRepository.insert()` returns a positive ID |
| `findConversationByIdRoundTrip` | Insert then `findById()` returns same name and rootDirectory |
| `findConversationsByUserId` | Two conversations for Guest, zero for someone@somewhere.com → `findByUserId` returns correct counts |
| `insertMessagesReturnedInOrder` | Insert three messages via `DSLContext` directly with explicit `message_time` values spaced 1 second apart; `findByConversationId` returns them ascending by time (cannot rely on `DEFAULT now()` — H2 in-memory inserts can share the same millisecond) |
| `messageTypesRoundTrip` | Insert messages of types `model_changed`, `SYSTEM`, `USER`, `ASSISTANT`; verify type field preserved on retrieval |

### ConversationServiceTest

`src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

Annotation: `@JooqTest @Import({ConversationRepository.class, MessageRepository.class})`

**@BeforeEach:** Insert `someone@somewhere.com` into `suite_user` (Guest is already seeded by `schema.sql`); construct `ConversationService` manually from the autowired repos.

**Test data — Guest conversation:**

```
model_changed  → "deepseek-v4-pro"
SYSTEM         → "You are a helpful assistant."
USER           → "Hello, what can you do?"
ASSISTANT      → "I can help with many things."
model_changed  → "sonnet-4.6"
SYSTEM         → "You are a concise assistant."
USER           → "Summarise that."
ASSISTANT      → "I assist."
```

**Test data — someone@somewhere.com conversation:**

```
model_changed  → "gemini-2.5-pro"
SYSTEM         → "You are a coding assistant."
USER           → "Write hello world in Java."
ASSISTANT      → "System.out.println(\"Hello, world!\");"
```

**Test cases:**

| Test | What it verifies |
|------|-----------------|
| `createConversationReturnsId` | `createConversation()` returns a positive ID |
| `getConversationReturnsRecord` | `getConversation(id)` returns record with correct name and rootDirectory |
| `getConversationThrowsForUnknownId` | `getConversation(-1)` throws `IllegalArgumentException` |
| `guestMessageHistoryHasCorrectCount` | Guest conversation has 8 messages after full setup |
| `guestMessageHistoryInOrder` | First message is `model_changed`, last is `ASSISTANT` |
| `modelChangedEventsPreserveValue` | Both `model_changed` rows carry the correct model name in `message` field |
| `systemMessagePreservesPrompt` | `SYSTEM` messages carry the correct prompt text |
| `someoneConversationIsIsolated` | `getMessages()` for someone@somewhere.com returns 4 messages, none from Guest conversation |

## Error Handling

`getConversation()` throws `IllegalArgumentException` — tested by `getConversationThrowsForUnknownId` using JUnit 5 `assertThrows`.

## Out of Scope

- `SuiteUserRepository` is not tested via `ConversationServiceTest` (it has its own coverage in `RepositoryTest`)
- No Testcontainers or external database in these tests
- No tests for `ConversationService.addMessage` called with null/blank strings (not a current validation boundary)
