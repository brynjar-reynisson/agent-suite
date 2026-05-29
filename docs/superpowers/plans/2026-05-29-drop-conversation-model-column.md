# Drop conversation.model Column Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the `model` column from the `conversation` table so that model tracking happens exclusively via `model_changed` type rows in the `message` table.

**Architecture:** A single migration drops the column. The jOOQ DSL classes are regenerated and committed. `ConversationRepository.insert()` and `ConversationService.createConversation()` lose their `model` parameter. Callers start a conversation by creating it and then immediately inserting a `model_changed` message.

**Tech Stack:** PostgreSQL / Supabase migrations, jOOQ 3.19, Spring Boot 3.5 / Java 21 / Maven

---

### Task 1: Add migration to drop conversation.model

**Files:**
- Create: `supabase/migrations/20260529120000_drop_conversation_model_column.sql`

- [ ] **Step 1: Create the migration file**

Create `supabase/migrations/20260529120000_drop_conversation_model_column.sql` with:

```sql
ALTER TABLE conversation
    DROP COLUMN model;
```

- [ ] **Step 2: Apply the migration via Supabase MCP**

Run the migration using the Supabase MCP tool (`mcp__supabase__apply_migration`) or via the Supabase CLI:

```bash
npx supabase db push
```

Verify the column is gone by running:

```sql
SELECT column_name FROM information_schema.columns
WHERE table_name = 'conversation' ORDER BY ordinal_position;
```

Expected columns: `conversation_id`, `user_id`, `conversation_name`, `create_time`, `root_directory` — no `model`.

- [ ] **Step 3: Commit the migration file**

```bash
git add supabase/migrations/20260529120000_drop_conversation_model_column.sql
git commit -m "feat: drop model column from conversation table"
```

---

### Task 2: Regenerate jOOQ DSL classes

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/generated/tables/Conversation.java` (regenerated — `MODEL` field removed)
- Modify: `src/main/java/com/example/agentsuite/jooq/generated/tables/records/ConversationRecord.java` (regenerated — `getModel()`/`setModel()` removed)

Prerequisites: Task 1 must be complete and the migration applied. Local Supabase must be running on port 54322.

- [ ] **Step 1: Run jOOQ codegen**

```bash
./mvnw.cmd jooq-codegen:generate
```

Expected: `BUILD SUCCESS`. The regenerated `Conversation.java` will no longer contain a `MODEL` field. The `ConversationRecord.java` will no longer have `getModel()` or `setModel()`.

- [ ] **Step 2: Verify the MODEL field is gone**

Check that `src/main/java/com/example/agentsuite/jooq/generated/tables/Conversation.java` does NOT contain the string `MODEL` as a field declaration. The file should still contain `CONVERSATION_ID`, `USER_ID`, `CONVERSATION_NAME`, `CREATE_TIME`, `ROOT_DIRECTORY`.

- [ ] **Step 3: Attempt compilation — expect failure**

```bash
./mvnw.cmd compile -q
```

Expected: `BUILD FAILURE` — `ConversationRepository.java` still references `CONVERSATION.MODEL` on line 25. This is expected and confirms the codegen worked.

- [ ] **Step 4: Commit regenerated classes**

```bash
git add src/main/java/com/example/agentsuite/jooq/generated/
git commit -m "feat: regenerate jOOQ DSL classes after dropping conversation.model"
```

---

### Task 3: Update ConversationRepository

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java`

- [ ] **Step 1: Remove the model parameter from insert()**

Replace the entire `insert` method. Current content (lines 21–30):

```java
    public long insert(long userId, String name, String model, String rootDirectory) {
        return dsl.insertInto(CONVERSATION)
                .set(CONVERSATION.USER_ID, userId)
                .set(CONVERSATION.CONVERSATION_NAME, name)
                .set(CONVERSATION.MODEL, model)
                .set(CONVERSATION.ROOT_DIRECTORY, rootDirectory)
                .returning(CONVERSATION.CONVERSATION_ID)
                .fetchSingle()
                .getConversationId();
    }
```

New content:

```java
    public long insert(long userId, String name, String rootDirectory) {
        return dsl.insertInto(CONVERSATION)
                .set(CONVERSATION.USER_ID, userId)
                .set(CONVERSATION.CONVERSATION_NAME, name)
                .set(CONVERSATION.ROOT_DIRECTORY, rootDirectory)
                .returning(CONVERSATION.CONVERSATION_ID)
                .fetchSingle()
                .getConversationId();
    }
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw.cmd compile -q
```

Expected: `BUILD FAILURE` — `ConversationService` still calls `insert(userId, name, model, rootDirectory)` with four arguments. This confirms the repository change was applied.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java
git commit -m "feat: remove model parameter from ConversationRepository.insert"
```

---

### Task 4: Update ConversationService

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`

- [ ] **Step 1: Remove the model parameter from createConversation()**

Replace the `createConversation` method. Current content (lines 24–27):

```java
    @Transactional
    public long createConversation(long userId, String name, String model, String rootDirectory) {
        return conversationRepository.insert(userId, name, model, rootDirectory);
    }
```

New content:

```java
    @Transactional
    public long createConversation(long userId, String name, String rootDirectory) {
        return conversationRepository.insert(userId, name, rootDirectory);
    }
```

- [ ] **Step 2: Verify full build and tests pass**

```bash
./mvnw.cmd test -q
```

Expected: `BUILD SUCCESS`, 76 tests, 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git commit -m "feat: remove model parameter from ConversationService.createConversation"
```
