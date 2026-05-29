# jOOQ Integration Design

**Date:** 2026-05-29
**Branch:** current feature branch

## Goal

Add type-safe SQL persistence for conversation history using jOOQ. The generated DSL classes are committed once and not rebuilt during normal Maven builds. A repository/service layer sits on top for use by the rest of the application.

## Dependencies

Add to `pom.xml`:
- `org.springframework.boot:spring-boot-starter-jooq` — pulls in jOOQ and Spring's `DSLContext` autoconfiguration
- `org.postgresql:postgresql` — JDBC driver for local Supabase PostgreSQL

## DataSource Configuration

Add to `src/main/resources/application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:54322/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres
```

Add matching entries to `src/test/resources/application.properties`.

## Package Structure

All new code lives under `com.example.agentsuite.jooq`:

```
com.example.agentsuite.jooq
├── generated/
│   └── tables/           # jOOQ-generated Table and Record classes
│       └── records/      # SuiteUserRecord, ConversationRecord, MessageRecord
├── repository/
│   ├── SuiteUserRepository.java
│   ├── ConversationRepository.java
│   └── MessageRepository.java
└── service/
    └── ConversationService.java
```

## Code Generation

Run jOOQ codegen once against the local Supabase database (`127.0.0.1:54322`). Generated classes are committed to source control. When the schema changes, regenerate and recommit. The Maven codegen plugin is not wired into the build lifecycle.

Tables to generate: `suite_user`, `conversation`, `message`.

## Repositories

All repositories are Spring `@Repository` beans injecting `DSLContext`.

### SuiteUserRepository
- `findByUuid(String uuid)` → `Optional<SuiteUserRecord>`
- `findGuest()` → `Optional<SuiteUserRecord>` — convenience method, calls `findByUuid("Guest")`

### ConversationRepository
- `insert(long userId, String name, String model, String rootDirectory)` → `long` (conversationId)
- `findById(long conversationId)` → `Optional<ConversationRecord>`
- `findByUserId(long userId)` → `List<ConversationRecord>`

### MessageRepository
- `insert(long conversationId, long userId, String type, String message)` → void
- `findByConversationId(long conversationId)` → `List<MessageRecord>` (ordered by `message_time`)

## Service Layer

`ConversationService` is a Spring `@Service` bean with `@Transactional` on its methods. It composes the repositories and is the primary entry point for callers.

### Methods
- `createConversation(long userId, String name, String model, String rootDirectory)` → `long` (conversationId)
- `addMessage(long conversationId, long userId, String type, String message)` → void
- `getMessages(long conversationId)` → `List<MessageRecord>`
- `getConversation(long conversationId)` → `ConversationRecord`

## Error Handling

Repositories return `Optional` for single-row lookups. `ConversationService` methods that fetch a required record throw `IllegalArgumentException` if not found. No silent null returns.

## Testing

No automated tests in scope for this task. The jOOQ codegen and datasource can be manually verified by running the application against local Supabase.
