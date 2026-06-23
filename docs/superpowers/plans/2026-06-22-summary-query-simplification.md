# Summary Query Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `initialModel` and `systemPrompt` from `ConversationSummaryDto` so the conversation list loads with a single SELECT on the conversation table and no message involvement.

**Architecture:** `initialModel` and `systemPrompt` only matter when loading a conversation — `ConversationDetailDto` already returns them. The summary DTO carried them as a historical artifact of the N+1 code. Removing them eliminates the bulk message meta-query entirely and makes the frontend type hierarchy correct (`ConversationDetail` adds those fields; `ConversationSummary` does not).

**Tech Stack:** Spring Boot 3.5, jOOQ 3.x, Java 21 (backend); React 19, TypeScript, Vite (frontend).

## Global Constraints

- `ConversationSummaryDto` fields after change: `externalId`, `name`, `customName`, `createTime` — exactly 4, in that order
- `ConversationDetail` in `api.ts` must explicitly declare `initialModel: string` and `systemPrompt: string`
- `getConversationSummaries` must make exactly 1 database query after this change
- `findFirstMetaByConversationIds` is deleted entirely — do not leave it as a private or unused method
- `getConversationDetail` must NOT be touched

---

### Task 1: Strip initialModel/systemPrompt from backend DTO and clean up dead code

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: `ConversationSummaryDto` with 4 fields (`externalId`, `name`, `customName`, `createTime`) — consumed by Task 2

**Background for the implementer:**

`ConversationSummaryDto` is a Java record at `src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java`. Removing its last two fields (`initialModel`, `systemPrompt`) will cause compile errors in exactly four places:
1. `ConversationService.getConversationSummaries` — constructs the record with 6 args
2. `AiControllerTest` — constructs the record with 6 args and asserts `$[0].initialModel` in JSON
3. `ConversationServiceTest.getConversationSummaries_returnsGuestConversations` — calls `.initialModel()` and `.systemPrompt()` on a summary
4. `ConversationServiceTest.findFirstMetaByConversationIds_returnsFirstModelAndPromptPerConversation` — calls `messageRepo.findFirstMetaByConversationIds(...)` which will also be deleted

Fix cascade: shrink the DTO, then fix each compile error in turn.

- [ ] **Step 1: Shrink `ConversationSummaryDto` to 4 fields**

Replace the entire content of `src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java` with:

```java
package com.example.agentsuite.controller;

public record ConversationSummaryDto(
        String externalId,
        String name,
        String customName,
        String createTime
) {}
```

- [ ] **Step 2: Simplify `ConversationService.getConversationSummaries`**

In `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`:

Remove `import java.util.Map;` (no longer used after this change).

Replace the entire `getConversationSummaries` method body with:

```java
@Transactional(readOnly = true)
public List<ConversationSummaryDto> getConversationSummaries(long userId) {
    return conversationRepository.findByUserId(userId).stream()
            .map(conv -> new ConversationSummaryDto(
                    conv.getExternalId(),
                    conv.getConversationName(),
                    conv.getCustomName(),
                    conv.getCreateTime().toString()
            ))
            .toList();
}
```

- [ ] **Step 3: Delete `findFirstMetaByConversationIds` from `MessageRepository`**

In `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`:

Remove `import java.util.HashMap;` and `import java.util.Map;`.

Delete the entire `findFirstMetaByConversationIds` method (the one added in the previous feature — it starts with `public Map<Long, String[]> findFirstMetaByConversationIds` and ends with its closing `}`).

- [ ] **Step 4: Fix `AiControllerTest`**

In `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`, find the test around line 405 that stubs `getConversationSummaries`. Replace that block:

Before:
```java
when(conversationService.getConversationSummaries(anyLong())).thenReturn(List.of(
        new ConversationSummaryDto("ext-abc", "Hello world", null, "2026-06-01T10:00:00Z",
                "deepseek-v4-pro", "")
));

mockMvc.perform(get("/ai/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].externalId").value("ext-abc"))
        .andExpect(jsonPath("$[0].name").value("Hello world"))
        .andExpect(jsonPath("$[0].initialModel").value("deepseek-v4-pro"));
```

After:
```java
when(conversationService.getConversationSummaries(anyLong())).thenReturn(List.of(
        new ConversationSummaryDto("ext-abc", "Hello world", null, "2026-06-01T10:00:00Z")
));

mockMvc.perform(get("/ai/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].externalId").value("ext-abc"))
        .andExpect(jsonPath("$[0].name").value("Hello world"));
```

- [ ] **Step 5: Fix `ConversationServiceTest`**

In `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`:

**5a.** Remove `import java.util.Map;` from the import block.

**5b.** Delete the entire test method `findFirstMetaByConversationIds_returnsFirstModelAndPromptPerConversation` (the whole `@Test` block, including the annotation).

**5c.** Update `getConversationSummaries_returnsGuestConversations` — remove the two assertions that call `.initialModel()` and `.systemPrompt()`. The method should look exactly like this after the edit:

```java
@Test
void getConversationSummaries_returnsGuestConversations() {
    List<ConversationSummaryDto> summaries = service.getConversationSummaries(guestId);
    assertThat(summaries).isNotEmpty();
    assertThat(summaries.get(0).name()).isEqualTo("Guest Chat");
}
```

- [ ] **Step 6: Run the full test suite**

```
.\mvnw test
```

Expected: `Tests run: 264, Failures: 0, Errors: 0` and `BUILD SUCCESS`. (264 = previous 265 minus the deleted `findFirstMetaByConversationIds` test.)

- [ ] **Step 7: Commit**

```
git add src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git add src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java
git commit -m "perf: remove initialModel/systemPrompt from ConversationSummaryDto — single-query conversation list"
```

---

### Task 2: Update frontend TypeScript types

**Files:**
- Modify: `frontend/src/api.ts`

**Interfaces:**
- Consumes: `ConversationSummaryDto` now has 4 fields (from Task 1) — `initialModel` and `systemPrompt` are no longer in the JSON response from `GET /ai/conversations`
- Produces: `ConversationSummary` without `initialModel`/`systemPrompt`; `ConversationDetail` with them explicitly declared

**Background for the implementer:**

`ConversationDetail extends ConversationSummary` in `api.ts`. Currently `initialModel` and `systemPrompt` are declared on `ConversationSummary` and inherited by `ConversationDetail`. After Task 1, the `GET /ai/conversations` JSON no longer includes those fields. `GET /ai/conversations/{id}` (the detail endpoint) still returns them — so they need to move to `ConversationDetail` as explicit declarations. Nothing in the codebase reads `initialModel` or `systemPrompt` from a `ConversationSummary` variable — only from `ConversationDetail` (in `useConversation.ts` lines 55-56: `detail.initialModel`, `detail.systemPrompt`).

- [ ] **Step 1: Update `api.ts`**

In `frontend/src/api.ts`, replace the `ConversationSummary` and `ConversationDetail` interfaces (currently lines 16–28) with:

```typescript
export interface ConversationSummary {
  externalId: string;
  name: string;
  customName: string | null;
  createTime: string;
}

export interface ConversationDetail extends ConversationSummary {
  initialModel: string;
  systemPrompt: string;
  rootDirectory: string;
  messages: Message[];
}
```

- [ ] **Step 2: Verify TypeScript types are consistent**

```
cd C:\Users\Lenovo\IdeaProjects\agent-suite\frontend && npx tsc --noEmit
```

Expected: no output (no errors). If tsc is not installed locally, run `npx --yes tsc --noEmit`.

- [ ] **Step 3: Commit**

```
git add frontend/src/api.ts
git commit -m "refactor: move initialModel/systemPrompt from ConversationSummary to ConversationDetail type"
```
