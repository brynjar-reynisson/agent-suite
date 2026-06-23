# Database Optimizations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add missing FK indexes confirmed by the Supabase performance advisor, and eliminate the N+1 query in `getConversationSummaries` that loads all message content for every conversation.

**Architecture:** Two independent changes. Task 1 is pure DDL: a migration + matching schema.sql update. Task 2 replaces the N per-conversation `findByConversationId` calls in `ConversationService.getConversationSummaries` with a single bulk query on `MessageRepository` that fetches only `model_change` and `system_prompt` rows; the mapping from rows to DTO fields is done in Java.

**Tech Stack:** Spring Boot 3.5, jOOQ 3.x, PostgreSQL (prod), H2 (tests), JUnit 5, AssertJ.

## Global Constraints

- Migration filename: `supabase/migrations/20260622000001_add_missing_fk_indexes.sql`
- Index names exactly: `idx_conversation_user_id`, `idx_message_conversation_id`, `idx_message_user_id`
- New repository method signature exactly: `public Map<Long, String[]> findFirstMetaByConversationIds(List<Long> conversationIds)`
- Return value convention: `arr[0]` = first `model_change` value (or `""`), `arr[1]` = first `system_prompt` value (or `""`)
- Empty list guard: if `conversationIds.isEmpty()`, return `Map.of()` without querying
- Query uses only standard SQL `IN (...)` — no PostgreSQL-specific syntax (must work on H2 in tests)
- `getConversationSummaries` total queries after fix: 2 (one for conversations, one for all meta messages)
- Do not change `getConversationDetail` — it intentionally loads all messages

---

### Task 1: Add missing FK indexes

**Files:**
- Create: `supabase/migrations/20260622000001_add_missing_fk_indexes.sql`
- Modify: `src/test/resources/schema.sql`

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: nothing consumed by other tasks (indexes are transparent to Java code)

- [ ] **Step 1: Create the migration file**

Create `supabase/migrations/20260622000001_add_missing_fk_indexes.sql` with this exact content:

```sql
CREATE INDEX idx_conversation_user_id    ON conversation(user_id);
CREATE INDEX idx_message_conversation_id ON message(conversation_id);
CREATE INDEX idx_message_user_id         ON message(user_id);
```

- [ ] **Step 2: Add the same indexes to the H2 test schema**

`src/test/resources/schema.sql` currently ends with the `user_role` table definition (line 45). Add these three lines at the very end of the file:

```sql
CREATE INDEX IF NOT EXISTS idx_conversation_user_id    ON "conversation"("user_id");
CREATE INDEX IF NOT EXISTS idx_message_conversation_id ON "message"("conversation_id");
CREATE INDEX IF NOT EXISTS idx_message_user_id         ON "message"("user_id");
```

Use `IF NOT EXISTS` and quoted identifiers to match the rest of the H2 schema style.

- [ ] **Step 3: Run the test suite to verify no regressions**

```
.\mvnw test
```

Expected: `Tests run: 264, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```
git add supabase/migrations/20260622000001_add_missing_fk_indexes.sql src/test/resources/schema.sql
git commit -m "feat: add missing FK indexes on conversation.user_id, message.conversation_id, message.user_id"
```

---

### Task 2: Fix N+1 in getConversationSummaries

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 (indexes don't affect Java interfaces)
- Produces: `MessageRepository.findFirstMetaByConversationIds(List<Long>) → Map<Long, String[]>`

**Background for the implementer:**

`ConversationService.getConversationSummaries` (lines 80–104) currently runs 1 query for N conversations then N calls to `messageRepository.findByConversationId(convId)` — each loading ALL columns of ALL messages in a conversation — just to extract the first `model_change` and first `system_prompt` values. The fix replaces those N calls with one bulk query.

The test class `ConversationServiceTest` (H2 integration test) already autowires both `conversationRepo` and `messageRepo` directly — the new repository method can be tested there. Key note from the existing test setup comment: "All addMessage calls share the same @Transactional now() timestamp in H2, so messages are ordered by `message_id ASC` (secondary sort) = insertion order." This means in tests, the first-inserted message of each type is reliably first.

- [ ] **Step 1: Write the failing test**

Add this test to `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`. It calls `messageRepo.findFirstMetaByConversationIds(...)` which does not exist yet — the build will fail to compile, confirming the test is driving new code:

```java
@Test
void findFirstMetaByConversationIds_returnsFirstModelAndPromptPerConversation() {
    // guestConvId: first model_change = "deepseek-v4-pro",
    //              first system_prompt = "You are a helpful assistant."
    // someoneConvId: first model_change = "gemini-2.5-pro",
    //                first system_prompt = "You are a coding assistant."
    Map<Long, String[]> meta = messageRepo.findFirstMetaByConversationIds(
            List.of(guestConvId, someoneConvId));

    assertThat(meta).containsKey(guestConvId);
    assertThat(meta.get(guestConvId)[0]).isEqualTo("deepseek-v4-pro");
    assertThat(meta.get(guestConvId)[1]).isEqualTo("You are a helpful assistant.");

    assertThat(meta).containsKey(someoneConvId);
    assertThat(meta.get(someoneConvId)[0]).isEqualTo("gemini-2.5-pro");
    assertThat(meta.get(someoneConvId)[1]).isEqualTo("You are a coding assistant.");
}
```

Add the import at the top of `ConversationServiceTest.java`:

```java
import java.util.Map;
```

- [ ] **Step 2: Verify the test fails to compile**

```
.\mvnw test -pl . -Dtest=ConversationServiceTest
```

Expected: `BUILD FAILURE` with a compile error about `findFirstMetaByConversationIds` not being found on `MessageRepository`.

- [ ] **Step 3: Implement `findFirstMetaByConversationIds` in `MessageRepository`**

Add these imports to `MessageRepository.java`:

```java
import java.util.HashMap;
import java.util.Map;
```

Add the new method to `MessageRepository.java` (after the existing `findLastSystemPrompt` method):

```java
public Map<Long, String[]> findFirstMetaByConversationIds(List<Long> conversationIds) {
    if (conversationIds.isEmpty()) return Map.of();
    Map<Long, String[]> result = new HashMap<>();
    dsl.select(MESSAGE.CONVERSATION_ID, MESSAGE.TYPE, MESSAGE.MESSAGE_)
            .from(MESSAGE)
            .where(MESSAGE.CONVERSATION_ID.in(conversationIds))
            .and(MESSAGE.TYPE.in("model_change", "system_prompt"))
            .orderBy(MESSAGE.MESSAGE_TIME.asc(), MESSAGE.MESSAGE_ID.asc())
            .forEach(r -> {
                long convId = r.value1();
                String type = r.value2();
                String msg = r.value3();
                result.computeIfAbsent(convId, k -> new String[]{"", ""});
                String[] arr = result.get(convId);
                if ("model_change".equals(type) && arr[0].isEmpty()) arr[0] = msg;
                else if ("system_prompt".equals(type) && arr[1].isEmpty()) arr[1] = msg;
            });
    return result;
}
```

- [ ] **Step 4: Run the new test alone to verify it passes**

```
.\mvnw test -pl . -Dtest=ConversationServiceTest#findFirstMetaByConversationIds_returnsFirstModelAndPromptPerConversation
```

Expected: `Tests run: 1, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

- [ ] **Step 5: Refactor `getConversationSummaries` in `ConversationService`**

Add this import to `ConversationService.java`:

```java
import java.util.Map;
```

Replace `getConversationSummaries` (currently lines 79–104) with:

```java
@Transactional(readOnly = true)
public List<ConversationSummaryDto> getConversationSummaries(long userId) {
    List<ConversationRecord> convs = conversationRepository.findByUserId(userId);
    if (convs.isEmpty()) return List.of();
    List<Long> ids = convs.stream().map(ConversationRecord::getConversationId).toList();
    Map<Long, String[]> meta = messageRepository.findFirstMetaByConversationIds(ids);
    return convs.stream()
            .map(conv -> {
                String[] m = meta.getOrDefault(conv.getConversationId(), new String[]{"", ""});
                return new ConversationSummaryDto(
                        conv.getExternalId(),
                        conv.getConversationName(),
                        conv.getCustomName(),
                        conv.getCreateTime().toString(),
                        m[0],
                        m[1]
                );
            })
            .toList();
}
```

- [ ] **Step 6: Run the full test suite**

```
.\mvnw test
```

Expected: `Tests run: 265, Failures: 0, Errors: 0` and `BUILD SUCCESS`. (265 = previous 264 + 1 new test.)

- [ ] **Step 7: Commit**

```
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git add src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java
git commit -m "perf: replace N+1 in getConversationSummaries with single bulk meta query"
```
