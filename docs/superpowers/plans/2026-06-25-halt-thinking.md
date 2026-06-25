# halt_thinking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three stream-interruption behaviors: sending a new message while the agent is responding aborts the current stream and starts a fresh one; `!stop` aborts without sending; `!erase-last` soft-deletes the last user+AI turn from the DB and removes it from local state.

**Architecture:** Frontend-only abort via `AbortController` (backend terminates naturally on `IOException` when the client disconnects). A generation counter in `useConversation` prevents the old stream's `finally` block from clearing `loading` after a new stream has already started. Soft-delete uses an `erased` column on the `message` table, filtered out in `MessageRepository` so both LLM history and frontend display exclude erased rows automatically.

**Tech Stack:** Spring Boot 3.5 / jOOQ / PostgreSQL / React 19 / TypeScript / `@microsoft/fetch-event-source`

## Global Constraints

- Java 21, Spring Boot 3.5, LangChain4j 1.16.2
- jOOQ codegen targets `com.example.agentsuite.jooq.generated`; regenerate with `.\mvnw.cmd jooq-codegen:generate` after any migration (requires local Supabase running)
- Migrations live in `supabase/migrations/` with timestamp prefix `YYYYMMDDHHmmss`
- Tests: `.\mvnw.cmd test`; backend uses real DB via `@JooqTest` + `@AutoConfigureTestDatabase(replace=NONE)` — local Supabase must be running
- Windows build: `build.cmd`; frontend dev server: `npm run dev` inside `frontend/`
- Spec: `docs/superpowers/specs/2026-06-25-halt-thinking-design.md`

---

## File Map

| File | Change |
|------|--------|
| `supabase/migrations/20260625000000_add_erased_to_message.sql` | **Create** — adds `erased` column |
| `src/main/java/.../jooq/generated/tables/Message.java` | **Regenerated** — gains `ERASED` field |
| `src/main/java/.../jooq/generated/tables/records/MessageRecord.java` | **Regenerated** — gains `getErased()`/`setErased()` |
| `src/main/java/.../jooq/repository/MessageRepository.java` | **Modify** — add `erased=false` filter + `eraseLastTurn()` |
| `src/main/java/.../jooq/service/ConversationService.java` | **Modify** — add `eraseLastTurn(externalId, userId)` |
| `src/main/java/.../controller/AiController.java` | **Modify** — add `POST /ai/conversations/{id}/erase-last` |
| `src/test/java/.../jooq/RepositoryTest.java` | **Modify** — add erased filter + eraseLastTurn tests |
| `src/test/java/.../jooq/service/ConversationServiceTest.java` | **Modify** — add eraseLastTurn tests |
| `src/test/java/.../controller/AiControllerTest.java` | **Modify** — add erase-last endpoint tests |
| `frontend/src/api.ts` | **Modify** — `chatStream` abort param + `eraseLastTurn()` |
| `frontend/src/useConversation.ts` | **Modify** — refs + full `handleSend` refactor |
| `frontend/src/App.tsx` | **Modify** — remove `disabled={loading}` from Send button |

---

## Task 1: DB migration and jOOQ regeneration

**Files:**
- Create: `supabase/migrations/20260625000000_add_erased_to_message.sql`
- Modified (auto): `src/main/java/com/example/agentsuite/jooq/generated/tables/Message.java`
- Modified (auto): `src/main/java/com/example/agentsuite/jooq/generated/tables/records/MessageRecord.java`

**Interfaces:**
- Produces: `MESSAGE.ERASED` column constant and `MessageRecord.getErased()` / `MessageRecord.setErased(Boolean)` — used in Tasks 2 and 3

- [ ] **Step 1: Write the migration**

Create `supabase/migrations/20260625000000_add_erased_to_message.sql`:

```sql
ALTER TABLE message ADD COLUMN erased BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 2: Apply migration to local Supabase**

In a terminal with the local Supabase instance running:

```bash
npx supabase db push
```

Expected output ends with: `Done`

- [ ] **Step 3: Regenerate jOOQ classes**

```
.\mvnw.cmd jooq-codegen:generate
```

Expected: `BUILD SUCCESS`. The files `Message.java` and `MessageRecord.java` in `src/main/java/com/example/agentsuite/jooq/generated/` are now updated.

- [ ] **Step 4: Verify the column appeared**

Open `src/main/java/com/example/agentsuite/jooq/generated/tables/Message.java`. Confirm it contains a field like:

```java
public final TableField<MessageRecord, Boolean> ERASED = createField(DSL.name("erased"), SQLDataType.BOOLEAN.nullable(false).defaultValue(DSL.field(DSL.raw("false"), SQLDataType.BOOLEAN)), this, "");
```

Open `src/main/java/com/example/agentsuite/jooq/generated/tables/records/MessageRecord.java`. Confirm it has:

```java
public void setErased(Boolean value) { ... }
public Boolean getErased() { ... }
```

- [ ] **Step 5: Commit**

```
git add supabase/migrations/20260625000000_add_erased_to_message.sql
git add src/main/java/com/example/agentsuite/jooq/generated/
git commit -m "feat: add erased column to message table, regenerate jOOQ"
```

---

## Task 2: MessageRepository — erased filter and eraseLastTurn

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`

**Interfaces:**
- Consumes: `MESSAGE.ERASED` from Task 1
- Produces:
  - `MessageRepository.findByConversationId(long)` — now excludes erased rows (existing callers unaffected by signature)
  - `MessageRepository.eraseLastTurn(long conversationId)` — throws `IllegalArgumentException` if no non-erased user message exists

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java` (inside the class, after existing tests):

```java
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
```

Add the import at the top of `RepositoryTest.java`:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

- [ ] **Step 2: Run tests to confirm they fail**

```
.\mvnw.cmd test -pl . -Dtest=RepositoryTest#findByConversationId_excludesErasedMessages+eraseLastTurn_erasesUserAndSubsequentMessages+eraseLastTurn_throwsWhenNoUserMessage
```

Expected: compilation error (method `eraseLastTurn` does not exist yet) or test failure.

- [ ] **Step 3: Implement erased filter and eraseLastTurn in MessageRepository**

Replace the body of `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java` with:

```java
package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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
                .set(MESSAGE.MESSAGE_, message)
                .execute();
    }

    public List<MessageRecord> findByConversationId(long conversationId) {
        return dsl.selectFrom(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.ERASED.isFalse())
                .orderBy(MESSAGE.MESSAGE_TIME.asc(), MESSAGE.MESSAGE_ID.asc())
                .fetch();
    }

    public Optional<String> findLastModelChange(long conversationId) {
        return dsl.select(MESSAGE.MESSAGE_)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.TYPE.eq("model_change"))
                .orderBy(MESSAGE.MESSAGE_TIME.desc(), MESSAGE.MESSAGE_ID.desc())
                .limit(1)
                .fetchOptional(MESSAGE.MESSAGE_);
    }

    public Optional<String> findLastSystemPrompt(long conversationId) {
        return dsl.select(MESSAGE.MESSAGE_)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.TYPE.eq("system_prompt"))
                .orderBy(MESSAGE.MESSAGE_TIME.desc(), MESSAGE.MESSAGE_ID.desc())
                .limit(1)
                .fetchOptional(MESSAGE.MESSAGE_);
    }

    public void eraseLastTurn(long conversationId) {
        Long lastUserMsgId = dsl.select(MESSAGE.MESSAGE_ID)
                .from(MESSAGE)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.TYPE.eq("user"))
                .and(MESSAGE.ERASED.isFalse())
                .orderBy(MESSAGE.MESSAGE_ID.desc())
                .limit(1)
                .fetchOneInto(Long.class);
        if (lastUserMsgId == null) {
            throw new IllegalArgumentException("No user message found to erase");
        }
        dsl.update(MESSAGE)
                .set(MESSAGE.ERASED, true)
                .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
                .and(MESSAGE.MESSAGE_ID.greaterOrEqual(lastUserMsgId))
                .execute();
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```
.\mvnw.cmd test -pl . -Dtest=RepositoryTest
```

Expected: `BUILD SUCCESS`, all `RepositoryTest` tests green.

- [ ] **Step 5: Commit**

```
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git add src/test/java/com/example/agentsuite/jooq/RepositoryTest.java
git commit -m "feat: filter erased messages in MessageRepository, add eraseLastTurn"
```

---

## Task 3: ConversationService.eraseLastTurn and AiController endpoint

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

**Interfaces:**
- Consumes: `MessageRepository.eraseLastTurn(long)` from Task 2
- Produces:
  - `ConversationService.eraseLastTurn(String externalId, long userId)` — throws `NoSuchElementException` for unknown/unauthorized conversation, propagates `IllegalArgumentException` from repository
  - `POST /ai/conversations/{externalId}/erase-last` → 200 OK / 404 / 400

- [ ] **Step 1: Write failing tests for ConversationService**

Add to `src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java` (inside the class):

```java
@Test
void eraseLastTurn_delegatesToRepository() {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(5L);
    when(conv.getUserId()).thenReturn(1L);
    when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));

    conversationService.eraseLastTurn("ext-1", 1L);

    verify(messageRepository).eraseLastTurn(5L);
}

@Test
void eraseLastTurn_throwsWhenConversationNotFound() {
    when(conversationRepository.findByExternalId("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> conversationService.eraseLastTurn("missing", 1L))
            .isInstanceOf(NoSuchElementException.class);
}

@Test
void eraseLastTurn_throwsWhenWrongUser() {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(5L);
    when(conv.getUserId()).thenReturn(99L);
    when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));

    assertThatThrownBy(() -> conversationService.eraseLastTurn("ext-1", 1L))
            .isInstanceOf(NoSuchElementException.class);
}
```

Add imports at the top of the file:

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.NoSuchElementException;
```

- [ ] **Step 2: Run ConversationService tests to confirm they fail**

```
.\mvnw.cmd test -pl . -Dtest="com.example.agentsuite.jooq.service.ConversationServiceTest"
```

Expected: compilation error (method `eraseLastTurn` not yet defined).

- [ ] **Step 3: Implement ConversationService.eraseLastTurn**

Add the following method to `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java` (after `compactMerge` or at the end before the closing brace):

```java
@Transactional
public void eraseLastTurn(String externalId, long userId) {
    ConversationRecord conv = conversationRepository.findByExternalId(externalId)
            .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));
    if (!conv.getUserId().equals(userId)) {
        throw new NoSuchElementException("Conversation not found: " + externalId);
    }
    messageRepository.eraseLastTurn(conv.getConversationId());
}
```

- [ ] **Step 4: Run ConversationService tests to confirm they pass**

```
.\mvnw.cmd test -pl . -Dtest="com.example.agentsuite.jooq.service.ConversationServiceTest"
```

Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 5: Write failing AiController test for erase-last**

Add to `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` (inside the class, after the existing compact tests):

```java
@Test
void eraseLastTurn_returnsOk() throws Exception {
    mockMvc.perform(post("/ai/conversations/conv-123/erase-last"))
            .andExpect(status().isOk());
    verify(conversationService).eraseLastTurn(eq("conv-123"), anyLong());
}

@Test
void eraseLastTurn_returnsNotFoundWhenConversationMissing() throws Exception {
    doThrow(new NoSuchElementException("not found"))
            .when(conversationService).eraseLastTurn(eq("missing-conv"), anyLong());

    mockMvc.perform(post("/ai/conversations/missing-conv/erase-last"))
            .andExpect(status().isNotFound());
}

@Test
void eraseLastTurn_returnsBadRequestWhenNoUserMessage() throws Exception {
    doThrow(new IllegalArgumentException("No user message found to erase"))
            .when(conversationService).eraseLastTurn(eq("conv-123"), anyLong());

    mockMvc.perform(post("/ai/conversations/conv-123/erase-last"))
            .andExpect(status().isBadRequest());
}
```

Add missing import if needed:

```java
import java.util.NoSuchElementException;
```

- [ ] **Step 6: Run AiControllerTest erase tests to confirm they fail**

```
.\mvnw.cmd test -pl . -Dtest="AiControllerTest#eraseLastTurn_returnsOk+eraseLastTurn_returnsNotFoundWhenConversationMissing+eraseLastTurn_returnsBadRequestWhenNoUserMessage"
```

Expected: 404 or error (endpoint not yet defined).

- [ ] **Step 7: Add the erase-last endpoint to AiController**

Add the following method to `src/main/java/com/example/agentsuite/controller/AiController.java` (after the `compactMerge` endpoint):

```java
@PostMapping("/ai/conversations/{externalId}/erase-last")
public ResponseEntity<Void> eraseLastTurn(
        @PathVariable String externalId,
        HttpServletRequest request) {
    long userId = currentUserId(request);
    try {
        conversationService.eraseLastTurn(externalId, userId);
        return ResponseEntity.ok().build();
    } catch (NoSuchElementException e) {
        return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().build();
    }
}
```

Add import if not already present:

```java
import java.util.NoSuchElementException;
```

- [ ] **Step 8: Run all affected tests**

```
.\mvnw.cmd test -pl . -Dtest="AiControllerTest,com.example.agentsuite.jooq.service.ConversationServiceTest,RepositoryTest"
```

Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 9: Commit**

```
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: add erase-last endpoint and ConversationService.eraseLastTurn"
```

---

## Task 4: Frontend api.ts — chatStream abort parameter and eraseLastTurn

**Files:**
- Modify: `frontend/src/api.ts`

**Interfaces:**
- Produces:
  - `chatStream(params, callbacks, token?, abortController?)` — optional fourth param; if provided, uses it as the abort signal
  - `eraseLastTurn(conversationId: string, token?: string | null): Promise<void>` — `POST /ai/conversations/{id}/erase-last`, throws on non-2xx

- [ ] **Step 1: Add optional abortController param to chatStream**

In `frontend/src/api.ts`, change the `chatStream` signature and controller creation:

```typescript
export const chatStream = async (
  params: ChatRequest,
  callbacks: StreamCallbacks,
  token?: string | null,
  abortController?: AbortController,
): Promise<void> => {
  const controller = abortController ?? new AbortController();
  await fetchEventSource(`${API_BASE_URL}/ai/chat`, {
    // ... rest unchanged
```

Only the signature line and the `const controller = ...` line change. Everything else in `chatStream` stays identical.

- [ ] **Step 2: Add eraseLastTurn function**

Add the following after `compactMergeConversation` in `frontend/src/api.ts`:

```typescript
export const eraseLastTurn = async (
  conversationId: string,
  token?: string | null,
): Promise<void> => {
  const res = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(conversationId)}/erase-last`,
    { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!res.ok) throw new Error(`Erase failed (${res.status})`);
};
```

- [ ] **Step 3: Verify TypeScript compiles**

```
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```
git add frontend/src/api.ts
git commit -m "feat: add abortController param to chatStream, add eraseLastTurn API"
```

---

## Task 5: Frontend — halt_thinking, !stop, and Send button

**Files:**
- Modify: `frontend/src/useConversation.ts`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `chatStream(..., abortController?)` from Task 4
- Produces: refactored `handleSend` with interrupt logic; `abortRef` and `streamGenRef` added to hook internals

- [ ] **Step 1: Add refs to useConversation**

In `frontend/src/useConversation.ts`, add two new refs after the existing `toastTimerRef`:

```typescript
const abortRef = useRef<AbortController | null>(null);
const streamGenRef = useRef(0);
```

- [ ] **Step 2: Add eraseLastTurn to imports**

At the top of `frontend/src/useConversation.ts`, add `eraseLastTurn` to the import from `./api`:

```typescript
import {
  chatStream, compactConversation, compactMergeConversation, eraseLastTurn, execTool, execShellStream,
  getConversationDetail, type ConversationDetail, type ConversationSummary, type Message,
} from './api';
```

- [ ] **Step 3: Replace handleSend with the refactored version**

Replace the entire `handleSend` function (lines 92–298 in the current file) with the following. Key changes are marked with `// NEW` comments:

```typescript
  const handleSend = async (input: string) => {
    if (!input.trim()) return; // NEW: removed || loading

    // NEW: !stop — abort without adding to history
    if (input === '!stop') {
      abortRef.current?.abort();
      return;
    }

    // !edit — blocked while loading
    const editMatch = input.match(/^!edit\s+(.+)$/i);
    if (editMatch) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!rootDirectory) {
        showToast('Select a root directory first');
      } else if (!isAdmin) {
        setMessages(prev => [
          ...prev,
          { role: 'user', content: input },
          { role: 'ai', content: 'Error: Permission denied' },
        ]);
      } else {
        setEditorFile({ path: editMatch[1].trim(), rootDirectory });
      }
      return;
    }

    // !! direct shell — blocked while loading
    const execMatch = input.match(/^!!(.+)$/);
    if (execMatch) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!rootDirectory) {
        showToast('Select a root directory first');
        return;
      }
      const command = execMatch[1].trim();
      setMessages(prev => [
        ...prev,
        { role: 'user', content: input },
        { role: 'ai', content: '```\n```' },
      ]);
      setLoading(true);
      let accumulated = '';
      try {
        const token = await getAccessToken();
        await execShellStream(command, rootDirectory, {
          onOutput: (line) => {
            accumulated += line + '\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
          },
          onDone: (exitCode) => {
            if (exitCode !== 0) accumulated += '[exit ' + exitCode + ']\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
            setLoading(false);
          },
          onError: (message) => {
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + message };
              return msgs;
            });
            setLoading(false);
          },
        }, token);
      } catch (error: unknown) {
        const msg = error instanceof Error ? error.message : 'Exec failed';
        setMessages(prev => {
          const msgs = [...prev];
          msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + msg };
          return msgs;
        });
        setLoading(false);
      }
      return;
    }

    // /compact — blocked while loading
    if (input === '/compact') {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!conversationId.current) {
        setMessages(prev => [...prev, { role: 'ai', content: 'Start a conversation before compacting.' }]);
        return;
      }
      setLoading(true);
      try {
        const token = await getAccessToken();
        const { summary } = await compactConversation(conversationId.current, token);
        setMessages(prev => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact failed.';
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // /compact-merge — blocked while loading
    if (input === '/compact-merge') {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      if (!conversationId.current) {
        setMessages(prev => [...prev, { role: 'ai', content: 'Start a conversation before merging compacts.' }]);
        return;
      }
      setLoading(true);
      try {
        const token = await getAccessToken();
        const { summary } = await compactMergeConversation(conversationId.current, token);
        setMessages(prev => [...prev, { role: 'compact', content: summary }]);
      } catch (err: unknown) {
        const msg = err instanceof Error ? err.message : 'Compact merge failed.';
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // !exec (single ! commands other than !stop, !erase-last, !edit, !!) — blocked while loading
    if (input.startsWith('!')) {
      if (loading) { showToast('Wait for the response to finish'); return; } // NEW
      const metaMessages: Message[] = [];
      if (model !== lastSentModel.current) {
        metaMessages.push({ role: 'meta', content: 'model:' + model });
        lastSentModel.current = model;
      }
      if (prompt !== lastSentPrompt.current) {
        if (prompt) metaMessages.push({ role: 'meta', content: 'system:' + prompt });
        lastSentPrompt.current = prompt;
      }
      setMessages(prev => [...prev, ...metaMessages, { role: 'user', content: input }]);
      setLoading(true);
      try {
        const command = input.slice(1).trim();
        const token = await getAccessToken();
        const result = await execTool(command, rootDirectory, token);
        const lang = catFileLang(command);
        setMessages(prev => [...prev, lang
          ? { role: 'ai', content: result, sourceLanguage: lang }
          : { role: 'ai', content: '```\n' + result + '\n```' },
        ]);
      } catch (error: any) {
        setMessages(prev => [...prev, { role: 'ai', content: `Error: ${error.message}` }]);
      } finally {
        setLoading(false);
      }
      return;
    }

    // --- Normal chat (possibly interrupting an in-flight stream) ---

    // NEW: If loading, abort current stream and remove partial AI response
    if (loading) {
      abortRef.current?.abort();
      setMessages(prev => {
        const msgs = [...prev];
        if (msgs.length > 0 && msgs[msgs.length - 1].role === 'ai') {
          return msgs.slice(0, -1);
        }
        return msgs;
      });
    }

    // Build meta messages and add user message to state
    const metaMessages: Message[] = [];
    if (model !== lastSentModel.current) {
      metaMessages.push({ role: 'meta', content: 'model:' + model });
      lastSentModel.current = model;
    }
    if (prompt !== lastSentPrompt.current) {
      if (prompt) metaMessages.push({ role: 'meta', content: 'system:' + prompt });
      lastSentPrompt.current = prompt;
    }
    if (!input.startsWith('/')) {
      setMessages(prev => [...prev, ...metaMessages, { role: 'user', content: input }]);
    }

    // NEW: Generation counter prevents old stream's finally from clearing loading
    streamGenRef.current++;
    const gen = streamGenRef.current;
    const controller = new AbortController(); // NEW
    abortRef.current = controller;            // NEW
    setLoading(true);

    const matched = PROMPT_BANK.find(p => p.name === prompt);
    const resolvedPrompt = matched?.text ?? prompt;
    const enabledTools = availableTools.filter(t => !disabledTools.has(t)).join(',');
    try {
      const token = await getAccessToken();
      await chatStream(
        {
          message: input,
          prompt: resolvedPrompt,
          rootDirectory,
          model,
          tools: enabledTools,
          conversationId: conversationId.current,
          requestId: crypto.randomUUID(),
        },
        {
          onToolCall: (tc) => {
            setMessages(prev => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                msgs[msgs.length - 1] = { ...last, toolCalls: [...(last.toolCalls || []), tc] };
              } else {
                msgs.push({ role: 'ai', content: '', toolCalls: [tc] });
              }
              return msgs;
            });
          },
          onContent: (text) => {
            setMessages(prev => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                msgs[msgs.length - 1] = { ...last, content: text };
              } else {
                msgs.push({ role: 'ai', content: text });
              }
              return msgs;
            });
          },
          onError: showToast,
        },
        token,
        controller, // NEW: pass controller so it can be aborted externally
      );
    } catch (error: any) {
      if (gen === streamGenRef.current) { // NEW: only update state if this is still the active stream
        setMessages(prev => {
          const errorMessage: Message = { role: 'ai', content: `Error: ${error.message}` };
          const msgs = [...prev];
          const last = msgs[msgs.length - 1];
          if (last && last.role === 'ai' && last.content === '') {
            msgs[msgs.length - 1] = errorMessage;
          } else {
            msgs.push(errorMessage);
          }
          return msgs;
        });
      }
    } finally {
      if (gen === streamGenRef.current) { // NEW: only clear loading if this is still the active stream
        setLoading(false);
        abortRef.current = null;
      }
    }
  };
```

- [ ] **Step 4: Remove disabled={loading} from Send button in App.tsx**

In `frontend/src/App.tsx`, find the Send button and remove `disabled={loading}`:

```tsx
        <button
          onClick={() => {
            if (inputRef.current) {
              handleSend(inputRef.current.value);
              inputRef.current.value = '';
            }
          }}
          className="bg-blue-600 text-white px-6 py-2 rounded font-semibold hover:bg-blue-700 transition-colors"
        >
          Send
        </button>
```

(The `disabled={loading}` line is removed; the rest is unchanged.)

- [ ] **Step 5: Verify TypeScript compiles**

```
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 6: Manual test — halt_thinking**

Start the dev server (`npm run dev` in `frontend/`). In the browser:
1. Send a long-running message (e.g., "count from 1 to 100 slowly").
2. While the AI is mid-response, type a new message and press Enter.
3. **Expected:** the current stream stops, the partial AI message disappears, your new message appears and a new stream starts.

- [ ] **Step 7: Manual test — !stop**

1. Send a long-running message.
2. While the AI is mid-response, type `!stop` and press Enter.
3. **Expected:** the stream stops, the partial AI text remains visible, `loading` clears (Send button goes back to non-loading state if any visual indicator exists).

- [ ] **Step 8: Commit**

```
git add frontend/src/useConversation.ts frontend/src/App.tsx
git commit -m "feat: halt_thinking — interrupt stream on new message, add !stop command"
```

---

## Task 6: Frontend — !erase-last command

**Files:**
- Modify: `frontend/src/useConversation.ts`

**Interfaces:**
- Consumes: `eraseLastTurn(conversationId, token)` from Task 4
- Produces: `!erase-last` handler in `handleSend`

- [ ] **Step 1: Add !erase-last handler to handleSend**

In `frontend/src/useConversation.ts`, add the `!erase-last` block immediately after the `!stop` block (after the `abortRef.current?.abort(); return;` line):

```typescript
    // !erase-last — soft-delete last user+AI turn
    if (input === '!erase-last') {
      if (loading) { showToast('Use !stop first before erasing'); return; }
      const hasUser = messages.some(m => m.role === 'user');
      if (!hasUser) { showToast('Nothing to erase'); return; }
      try {
        const token = await getAccessToken();
        await eraseLastTurn(conversationId.current, token);
        setMessages(prev => {
          const msgs = [...prev];
          // Remove last 'ai' entry (skipping meta/compact/clear)
          for (let i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === 'ai') { msgs.splice(i, 1); break; }
          }
          // Remove last 'user' entry
          for (let i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].role === 'user') { msgs.splice(i, 1); break; }
          }
          return msgs;
        });
      } catch (err: unknown) {
        showToast(err instanceof Error ? err.message : 'Erase failed');
      }
      return;
    }
```

- [ ] **Step 2: Verify TypeScript compiles**

```
cd frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Manual test — !erase-last after normal conversation**

1. Send a message, wait for the AI to respond fully.
2. Type `!erase-last` and press Enter.
3. **Expected:** the last user message and AI response disappear from the chat UI.
4. Reload the page and reopen the same conversation.
5. **Expected:** the erased turn does not reappear.

- [ ] **Step 4: Manual test — !erase-last after !stop**

1. Send a long-running message.
2. Type `!stop` — stream stops, partial AI text visible.
3. Type `!erase-last`.
4. **Expected:** the stopped turn (user message + partial AI) is removed from the UI and from the DB.

- [ ] **Step 5: Manual test — !erase-last while loading**

1. Start a stream.
2. Type `!erase-last` while it is still loading.
3. **Expected:** toast "Use !stop first before erasing". Stream continues uninterrupted.

- [ ] **Step 6: Run full backend test suite**

```
.\mvnw.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```
git add frontend/src/useConversation.ts
git commit -m "feat: add !erase-last command — soft-delete last turn from DB and local state"
```
