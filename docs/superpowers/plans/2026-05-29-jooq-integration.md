# jOOQ Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add jOOQ type-safe SQL persistence for conversation history with repository and service layers.

**Architecture:** jOOQ DSL classes are generated once against the local Supabase PostgreSQL instance and committed to source control. Three `@Repository` beans (`SuiteUserRepository`, `ConversationRepository`, `MessageRepository`) use `DSLContext` directly. A `ConversationService` composes the repositories and is the primary entry point for callers.

**Tech Stack:** Spring Boot 3.5 / jOOQ 3.19 (managed by Spring Boot BOM) / PostgreSQL JDBC driver / local Supabase on port 54322

---

### Task 1: Add dependencies and DataSource configuration

**Files:**
- Modify: `pom.xml` (lines 71–72, inside `<dependencies>`)
- Modify: `pom.xml` (lines 74–81, inside `<build><plugins>`)
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/resources/application.properties`

- [ ] **Step 1: Add runtime dependencies to pom.xml**

Inside the `<dependencies>` block in `pom.xml`, after the `spring-boot-starter-test` dependency (line 71), add:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jooq</artifactId>
        </dependency>

        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Add jOOQ codegen Maven plugin to pom.xml**

Inside `<build><plugins>` in `pom.xml`, after the `spring-boot-maven-plugin` block, add:

```xml
            <plugin>
                <groupId>org.jooq</groupId>
                <artifactId>jooq-codegen-maven</artifactId>
                <version>${jooq.version}</version>
                <configuration>
                    <jdbc>
                        <driver>org.postgresql.Driver</driver>
                        <url>jdbc:postgresql://127.0.0.1:54322/postgres</url>
                        <user>postgres</user>
                        <password>postgres</password>
                    </jdbc>
                    <generator>
                        <database>
                            <name>org.jooq.meta.postgres.PostgresDatabase</name>
                            <inputSchema>public</inputSchema>
                            <includes>suite_user|conversation|message</includes>
                        </database>
                        <target>
                            <packageName>com.example.agentsuite.jooq.generated</packageName>
                            <directory>src/main/java</directory>
                        </target>
                    </generator>
                </configuration>
            </plugin>
```

Note: This plugin has no `<executions>` block, so it will NOT run during normal `mvn compile` or `mvn package`. It is only invoked explicitly via `./mvnw jooq-codegen:generate`.

- [ ] **Step 3: Add DataSource config to main application.properties**

Append to `src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:54322/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres
```

- [ ] **Step 4: Add DataSource config to test application.properties**

Append to `src/test/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:54322/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres
```

- [ ] **Step 5: Verify compilation**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS` with no errors.

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.properties src/test/resources/application.properties
git commit -m "feat: add jOOQ and PostgreSQL dependencies with DataSource config"
```

---

### Task 2: Generate jOOQ DSL classes

**Files:**
- Create: `src/main/java/com/example/agentsuite/jooq/generated/` (all generated files)

Prerequisites: Local Supabase must be running (`supabase start` if not already up). All four migrations must have been applied.

- [ ] **Step 1: Run jOOQ codegen**

```bash
./mvnw jooq-codegen:generate
```

Expected: `BUILD SUCCESS`. The following directories will be created under `src/main/java/com/example/agentsuite/jooq/generated/`:
- `tables/` — `SuiteUser.java`, `Conversation.java`, `Message.java`
- `tables/records/` — `SuiteUserRecord.java`, `ConversationRecord.java`, `MessageRecord.java`
- `DefaultCatalog.java`, `DefaultSchema.java`, `Keys.java`, `Tables.java`

- [ ] **Step 2: Verify compilation with generated classes**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit generated classes**

```bash
git add src/main/java/com/example/agentsuite/jooq/generated/
git commit -m "feat: add jOOQ-generated DSL classes for suite_user, conversation, message"
```

---

### Task 3: SuiteUserRepository

**Files:**
- Create: `src/main/java/com/example/agentsuite/jooq/repository/SuiteUserRepository.java`

- [ ] **Step 1: Create SuiteUserRepository**

Create `src/main/java/com/example/agentsuite/jooq/repository/SuiteUserRepository.java`:

```java
package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.SUITE_USER;

@Repository
public class SuiteUserRepository {

    private final DSLContext dsl;

    public SuiteUserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<SuiteUserRecord> findByUuid(String uuid) {
        return dsl.selectFrom(SUITE_USER)
                .where(SUITE_USER.UUID.eq(uuid))
                .fetchOptional();
    }

    public Optional<SuiteUserRecord> findGuest() {
        return findByUuid("Guest");
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/SuiteUserRepository.java
git commit -m "feat: add SuiteUserRepository with findByUuid and findGuest"
```

---

### Task 4: ConversationRepository

**Files:**
- Create: `src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java`

- [ ] **Step 1: Create ConversationRepository**

Create `src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java`:

```java
package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.CONVERSATION;

@Repository
public class ConversationRepository {

    private final DSLContext dsl;

    public ConversationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long userId, String name, String model, String rootDirectory) {
        return dsl.insertInto(CONVERSATION)
                .set(CONVERSATION.USER_ID, userId)
                .set(CONVERSATION.CONVERSATION_NAME, name)
                .set(CONVERSATION.MODEL, model)
                .set(CONVERSATION.ROOT_DIRECTORY, rootDirectory)
                .returning(CONVERSATION.CONVERSATION_ID)
                .fetchOne()
                .getConversationId();
    }

    public Optional<ConversationRecord> findById(long conversationId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.CONVERSATION_ID.eq(conversationId))
                .fetchOptional();
    }

    public List<ConversationRecord> findByUserId(long userId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.USER_ID.eq(userId))
                .orderBy(CONVERSATION.CREATE_TIME.desc())
                .fetch();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java
git commit -m "feat: add ConversationRepository with insert, findById, findByUserId"
```

---

### Task 5: MessageRepository

**Files:**
- Create: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`

- [ ] **Step 1: Create MessageRepository**

Create `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`:

```java
package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.example.agentsuite.jooq.generated.Tables.MESSAGE;

@Repository
public class MessageRepository {

    private final DSLContext dsl;

    public MessageRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(long conversationId, long userId, String type, String message) {
        dsl.insertInto(MESSAGE)
                .set(MESSAGE.CONVERSATION_ID, conversationId)
                .set(MESSAGE.USER_ID, userId)
                .set(MESSAGE.TYPE, type)
                .set(MESSAGE.MESSAGE, message)
                .execute();
    }

    public List<MessageRecord> findByConversationId(long conversationId) {
        return dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .orderBy(MESSAGE.MESSAGE_TIME.asc())
                .fetch();
    }
}
```

> **Note:** `MESSAGE.MESSAGE` refers to the `message` column on the `message` table — the first `MESSAGE` is the table reference (static import from `Tables`), the second is the column field on that table class. If the generated code names this field differently (e.g. `MESSAGE_`), update accordingly by inspecting `src/main/java/com/example/agentsuite/jooq/generated/tables/Message.java`.

- [ ] **Step 2: Verify compilation**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git commit -m "feat: add MessageRepository with insert and findByConversationId"
```

---

### Task 6: ConversationService

**Files:**
- Create: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`

- [ ] **Step 1: Create ConversationService**

Create `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`:

```java
package com.example.agentsuite.jooq.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public long createConversation(long userId, String name, String model, String rootDirectory) {
        return conversationRepository.insert(userId, name, model, rootDirectory);
    }

    @Transactional
    public void addMessage(long conversationId, long userId, String type, String message) {
        messageRepository.insert(conversationId, userId, type, message);
    }

    @Transactional(readOnly = true)
    public List<MessageRecord> getMessages(long conversationId) {
        return messageRepository.findByConversationId(conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationRecord getConversation(long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }
}
```

- [ ] **Step 2: Verify full build and tests pass**

```bash
./mvnw test -q
```

Expected: `BUILD SUCCESS`. The existing `AgentSuiteApplicationTests.contextLoads` test should pass (Spring context loads successfully with the new beans).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git commit -m "feat: add ConversationService with createConversation, addMessage, getMessages, getConversation"
```
