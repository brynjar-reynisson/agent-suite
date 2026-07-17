# Conversation File Backsweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backfill `.md` mirror files for every conversation whose `md_file_name` is still `NULL` (conversations that predate the save-conversation-to-file feature), starting with the local dev database.

**Architecture:** A single manually-invoked JUnit test class, `ConversationFileBacksweepRunner`, reuses `ConversationFileService`, `ConversationRepository`, `MessageRepository`, and `SuiteUserRepository` exactly as they already exist — no production code changes. It connects to a real Postgres database (not the H2 test database) via `@DynamicPropertySource`-injected connection properties sourced from environment variables with dev-friendly defaults, finds every conversation missing a file, recreates it from message history, and persists the resolved filename.

**Tech Stack:** Spring Boot 3.5 `@JooqTest` slice, JUnit 5, jOOQ, real Postgres (local Supabase at `127.0.0.1:54322` by default).

## Global Constraints

- The class must be named so Surefire's default include patterns (`**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`) do **not** match it — a plain `mvnw test` (no `-Dtest` filter) must never run it. `ConversationFileBacksweepRunner` satisfies this.
- Connection properties come from environment variables with these exact defaults: `BACKSWEEP_DB_URL` → `jdbc:postgresql://127.0.0.1:54322/postgres`, `BACKSWEEP_DB_USERNAME` → `postgres`, `BACKSWEEP_DB_PASSWORD` → `postgres`, `BACKSWEEP_ENV_LABEL` → `dev`.
- No `@Transactional` on the class — every write must commit for real, not roll back.
- No `spring.sql.init.mode=always` (or any `sql.init` property) — that would attempt to run the H2-only `src/test/resources/schema.sql` (H2-specific DDL syntax) against the real Postgres connection and fail/corrupt nothing-but-error loudly. Simply never set it.
- `spring.datasource.driver-class-name` must be explicitly overridden to `org.postgresql.Driver` in the same `@DynamicPropertySource` block — the base test `application.properties` sets it to `org.h2.Driver` for every other test in this module, which would silently break the Postgres connection if left in place.
- Reuse `ConversationFileService`'s existing package-private constructor `ConversationFileService(Path baseDir, String envLabel, boolean enabled)` (same package: `com.example.agentsuite.service`) — do not modify `ConversationFileService` itself.
- Display name resolution must match `ConversationService.renameConversation`'s existing rule exactly: `conv.getCustomName()` if non-blank, else `conv.getConversationName()`.
- `ConversationFileService.appendMessage` already excludes `tool_result` messages — do not add separate filtering logic for this in the runner; just replay every message from `MessageRepository.findByConversationId` (which already excludes erased rows and is already ordered).
- Query scope: every `conversation` row where `md_file_name IS NULL`, ordered by `conversation_id` ascending — issued directly via the injected `DSLContext`, not a new `ConversationRepository` method (this is a one-off migration query, not permanent production code).
- `@JooqTest` is meta-annotated with `@Transactional` and rolls back every test method's DB writes by default (verified against Spring Boot 3.5.0's `JooqTest.class`) — the class **must** carry `@org.springframework.test.annotation.Commit` (class-level) or the `updateMdFileName` writes will silently roll back while the `.md` files (plain filesystem I/O, outside the JDBC transaction) still get written for real, leaving orphaned files with no matching DB update. This is already included in the code below — do not remove it.

---

### Task 1: Implement and run the conversation file backsweep runner

**Files:**
- Create: `src/test/java/com/example/agentsuite/service/ConversationFileBacksweepRunner.java`

**Interfaces:**
- Consumes: `ConversationFileService(Path, String, boolean)`, `ConversationFileService.createFile(String, String, String, OffsetDateTime) -> Optional<String>`, `ConversationFileService.appendMessage(String, String, String, OffsetDateTime) -> void`; `ConversationRepository.updateMdFileName(long, String) -> void`; `MessageRepository.findByConversationId(long) -> List<MessageRecord>`; `SuiteUserRepository.findById(long) -> Optional<SuiteUserRecord>`. All already exist — no other task depends on this one.

- [ ] **Step 1: Record the starting state**

Using any Postgres client pointed at `127.0.0.1:54322/postgres` (e.g. local Supabase Studio, `psql`, or a DB GUI — user `postgres`, password `postgres`), run:

```sql
SELECT count(*) AS total, count(*) FILTER (WHERE md_file_name IS NULL) AS missing FROM conversation;
```

Note the `missing` count — you'll confirm it drops to `0` after Step 4. (At the time this plan was written, local dev had 8 total conversations, 7 missing.)

- [ ] **Step 2: Write the runner class**

Create `src/test/java/com/example/agentsuite/service/ConversationFileBacksweepRunner.java`:

```java
package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.generated.tables.records.SuiteUserRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jooq.JooqTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.CONVERSATION;
import static org.assertj.core.api.Assertions.assertThat;

@JooqTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConversationRepository.class, MessageRepository.class, SuiteUserRepository.class})
@org.springframework.test.annotation.Commit
class ConversationFileBacksweepRunner {

    @Autowired DSLContext dsl;
    @Autowired ConversationRepository conversationRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired SuiteUserRepository suiteUserRepository;

    @DynamicPropertySource
    static void backsweepDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_URL", "jdbc:postgresql://127.0.0.1:54322/postgres"));
        registry.add("spring.datasource.username", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_USERNAME", "postgres"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("BACKSWEEP_DB_PASSWORD", "postgres"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void backfillMissingConversationFiles() {
        String envLabel = System.getenv().getOrDefault("BACKSWEEP_ENV_LABEL", "dev");
        ConversationFileService fileService = new ConversationFileService(Path.of("conversations"), envLabel, true);

        List<ConversationRecord> pending = dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.MD_FILE_NAME.isNull())
                .orderBy(CONVERSATION.CONVERSATION_ID.asc())
                .fetch();

        System.out.println("=== Conversation file backsweep ===");
        System.out.println("Environment label: " + envLabel);
        System.out.println("Conversations missing md_file_name: " + pending.size());

        List<String> failures = new ArrayList<>();
        int succeeded = 0;

        for (ConversationRecord conv : pending) {
            String externalId = conv.getExternalId();
            try {
                String email = suiteUserRepository.findById(conv.getUserId())
                        .map(SuiteUserRecord::getEmail).orElse(null);
                String displayName = (conv.getCustomName() != null && !conv.getCustomName().isBlank())
                        ? conv.getCustomName() : conv.getConversationName();

                Optional<String> fileNameOpt = fileService.createFile(
                        email, displayName, externalId, conv.getCreateTime());
                if (fileNameOpt.isEmpty()) {
                    failures.add(externalId + ": createFile returned empty");
                    continue;
                }
                String fileName = fileNameOpt.get();

                List<MessageRecord> messages = messageRepository.findByConversationId(conv.getConversationId());
                for (MessageRecord msg : messages) {
                    fileService.appendMessage(fileName, msg.getType(), msg.getMessage(), msg.getMessageTime());
                }

                conversationRepository.updateMdFileName(conv.getConversationId(), fileName);
                System.out.println("  [" + externalId + "] -> " + fileName + " (" + messages.size() + " messages)");
                succeeded++;
            } catch (Exception e) {
                failures.add(externalId + ": " + e.getMessage());
            }
        }

        System.out.println("Succeeded: " + succeeded);
        System.out.println("Failed: " + failures.size());
        failures.forEach(f -> System.out.println("  " + f));
        System.out.println("=== Done ===");

        assertThat(failures).as("backsweep failures: %s", failures).isEmpty();
    }
}
```

- [ ] **Step 3: Run the backsweep against local dev**

Run in PowerShell (not Bash — `mvnw.cmd` requires the project root as cwd):

```
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" test -Dtest=ConversationFileBacksweepRunner
```

No environment variables need to be set — the defaults point at local dev Supabase. Expected: `BUILD SUCCESS`, with console output showing one line per conversation (`[external-id] -> filename (N messages)`), `Succeeded:` matching the `missing` count from Step 1, and `Failed: 0`.

- [ ] **Step 4: Verify the ending state**

Re-run the Step 1 query:

```sql
SELECT count(*) AS total, count(*) FILTER (WHERE md_file_name IS NULL) AS missing FROM conversation;
```

Expected: `missing = 0`.

Check the `conversations/` directory (project root) — it should now contain one `.md` file per previously-missing conversation, named `<user>_<name>-dev.md`.

- [ ] **Step 5: Spot-check a generated file's content**

Open any one of the newly-created files under `conversations/` and confirm:
- It starts with a `# <name>` header followed by `- User:`, `- External ID:`, `- Created:`, `- Environment:` lines.
- It contains `### user`, `### assistant` (and `### tool_call` if that conversation used tools) blocks in chronological order.
- It contains **no** `### tool_result` block anywhere, even if the conversation used tools (check across all newly-created files, not just one, if any conversation had tool calls):

```
grep -rl "### tool_result" conversations/
```

Expected: no output (no matches).

- [ ] **Step 6: Verify idempotency**

Run the same command from Step 3 again:

```
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" test -Dtest=ConversationFileBacksweepRunner
```

Expected: `BUILD SUCCESS`, console shows `Conversations missing md_file_name: 0`, `Succeeded: 0`, `Failed: 0`. No new or duplicate files appear under `conversations/`.

- [ ] **Step 7: Commit**

```
git add src/test/java/com/example/agentsuite/service/ConversationFileBacksweepRunner.java
git commit -m "feat: add conversation file backsweep runner"
```

Note: `conversations/` itself is gitignored, so the `.md` files this run created are not part of this commit — only the runner class is.
