# `/compact-merge` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/compact-merge` slash command that concatenates the last two `compact` DB rows into a new merged compact, recovering context lost when a re-compact produced a poor summary.

**Architecture:** New `compactMerge()` method in `ChatOrchestrationService` scans the message list for the last two compacts, concatenates them with a `---` divider, and stores the result as a new `compact` row. A new `POST /ai/conversations/{externalId}/compact-merge` endpoint exposes it. The frontend adds a `compactMergeConversation()` API call and a `/compact-merge` slash command handler — identical pattern to the existing `/compact` command.

**Tech Stack:** Spring Boot 3.5 / Java 21, React 19 / TypeScript, existing `ConversationService.addMessage()` / `conversationService.getMessages()`.

---

## File Map

| File | Change |
|------|--------|
| `src/main/java/.../service/ChatOrchestrationService.java` | Add `compactMerge()` method |
| `src/main/java/.../controller/AiController.java` | Add `POST /ai/conversations/{externalId}/compact-merge` endpoint |
| `src/test/java/.../service/ChatOrchestrationServiceTest.java` | Add 5 new tests for `compactMerge()` |
| `frontend/src/api.ts` | Add `compactMergeConversation()` |
| `frontend/src/App.tsx` | Add `/compact-merge` slash command handler + import |
| `CLAUDE.md` | Document new endpoint in API section |

---

### Task 1: Backend — tests first, then implement

**Files:**
- Modify: `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`
- Modify: `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

#### Context

`ChatOrchestrationService` is in `src/main/java/com/example/agentsuite/service/`. Tests live in `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`. The test class has a `rec(String type, String message)` helper at the bottom that returns a mocked `MessageRecord`. Use it.

The existing `compact()` method (line ~210) is the pattern to follow. The `compactMerge()` method does NOT call the LLM — it purely does string concatenation.

`AiController.java` — the existing `compact` endpoint is at line ~180 and is the exact pattern for the new endpoint.

- [ ] **Step 1: Add 5 failing tests for `compactMerge()` at the end of `ChatOrchestrationServiceTest`**

  Add after the existing `compact` tests (after `buildTranscript_includesUserAssistantAndCompactRecords`):

  ```java
  // --- compactMerge() tests ---

  @Test
  void compactMerge_mergesLastTwoCompacts() {
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(30L);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
      when(conversationService.getMessages(30L)).thenReturn(List.of(
          rec("user", "hello"),
          rec("compact", "first summary"),
          rec("user", "follow up"),
          rec("compact", "second summary")
      ));

      String result = orchestration.compactMerge("abc", 1L);

      assertThat(result).isEqualTo("first summary\n\n---\n\nsecond summary");
      verify(conversationService).addMessage(30L, 1L, "compact", "first summary\n\n---\n\nsecond summary");
  }

  @Test
  void compactMerge_usesLastTwoWhenMoreExist() {
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(33L);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
      when(conversationService.getMessages(33L)).thenReturn(List.of(
          rec("compact", "old summary"),
          rec("compact", "second summary"),
          rec("compact", "third summary")
      ));

      String result = orchestration.compactMerge("abc", 1L);

      assertThat(result).isEqualTo("second summary\n\n---\n\nthird summary");
  }

  @Test
  void compactMerge_onlyOneCompact_throwsIllegalArgument() {
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(31L);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
      when(conversationService.getMessages(31L)).thenReturn(List.of(
          rec("user", "hello"),
          rec("compact", "only summary")
      ));

      assertThatThrownBy(() -> orchestration.compactMerge("abc", 1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Need at least two compact messages to merge.");
  }

  @Test
  void compactMerge_noCompacts_throwsIllegalArgument() {
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(32L);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
      when(conversationService.getMessages(32L)).thenReturn(List.of(
          rec("user", "hello"),
          rec("assistant", "hi")
      ));

      assertThatThrownBy(() -> orchestration.compactMerge("abc", 1L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Need at least two compact messages to merge.");
  }

  @Test
  void compactMerge_conversationNotFound_throwsNoSuchElement() {
      when(conversationService.findByExternalId("missing")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> orchestration.compactMerge("missing", 1L))
          .isInstanceOf(java.util.NoSuchElementException.class);
  }
  ```

- [ ] **Step 2: Run — expect compile failure**

  ```powershell
  cd C:\Users\Lenovo\IdeaProjects\agent-suite; .\mvnw.cmd test -Dtest=ChatOrchestrationServiceTest
  ```
  Expected: compile error — `compactMerge` not found.

- [ ] **Step 3: Add `compactMerge()` to `ChatOrchestrationService`**

  Add this method directly after the existing `compact()` method (after line ~232):

  ```java
  public String compactMerge(String externalId, long userId) {
      ConversationRecord conv = conversationService.findByExternalId(externalId)
              .filter(c -> c.getUserId().equals(userId))
              .orElseThrow(() -> new java.util.NoSuchElementException("Conversation not found: " + externalId));

      long convDbId = conv.getConversationId();
      List<MessageRecord> records = conversationService.getMessages(convDbId);

      List<MessageRecord> compacts = new ArrayList<>();
      for (int i = records.size() - 1; i >= 0 && compacts.size() < 2; i--) {
          if ("compact".equals(records.get(i).getType())) {
              compacts.add(0, records.get(i));
          }
      }

      if (compacts.size() < 2) {
          throw new IllegalArgumentException("Need at least two compact messages to merge.");
      }

      String merged = compacts.get(0).getMessage() + "\n\n---\n\n" + compacts.get(1).getMessage();
      conversationService.addMessage(convDbId, userId, "compact", merged);
      return merged;
  }
  ```

- [ ] **Step 4: Add the endpoint to `AiController`**

  Add this method directly after the existing `compact()` endpoint (after line ~195):

  ```java
  @PostMapping("/ai/conversations/{externalId}/compact-merge")
  public ResponseEntity<Map<String, String>> compactMerge(
          @PathVariable String externalId,
          HttpServletRequest request) {
      long userId = currentUserId(request);
      try {
          String merged = orchestrationService.compactMerge(externalId, userId);
          return ResponseEntity.ok(Map.of("summary", merged));
      } catch (NoSuchElementException e) {
          return ResponseEntity.notFound().build();
      } catch (IllegalArgumentException e) {
          return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
      }
  }
  ```

- [ ] **Step 5: Run all backend tests**

  ```powershell
  cd C:\Users\Lenovo\IdeaProjects\agent-suite; .\mvnw.cmd test
  ```
  Expected: all tests pass (previously 236, now 241).

- [ ] **Step 6: Commit**

  ```
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/AiController.java
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add compactMerge service method and POST /ai/conversations/{id}/compact-merge endpoint"
  ```

---

### Task 2: Frontend — api.ts + App.tsx

**Files:**
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx`

#### Context

`api.ts` — the existing `compactConversation` function (line ~141) is the exact pattern to copy. The new function calls `/compact-merge` instead of `/compact`.

`App.tsx` — the `/compact` handler starts at line ~311. Add `/compact-merge` immediately after the `/compact` block (after line ~331). Also add `compactMergeConversation` to the import from `./api` on line 3.

- [ ] **Step 1: Add `compactMergeConversation` to `api.ts`**

  Add after the existing `compactConversation` function (after line ~154):

  ```typescript
  export const compactMergeConversation = async (
    conversationId: string,
    token?: string | null,
  ): Promise<{ summary: string }> => {
    const res = await fetch(
      `${API_BASE_URL}/ai/conversations/${encodeURIComponent(conversationId)}/compact-merge`,
      { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {} },
    );
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error((body as { error?: string }).error ?? `Compact merge failed (${res.status})`);
    }
    return res.json();
  };
  ```

- [ ] **Step 2: Add `compactMergeConversation` to the import in `App.tsx`**

  The current import on line 3 reads:
  ```typescript
  chatStream, compactConversation, execTool, getDirectories, getConversationDetail, getUserConfig, type Message, type ConversationSummary,
  ```
  Add `compactMergeConversation` to it:
  ```typescript
  chatStream, compactConversation, compactMergeConversation, execTool, getDirectories, getConversationDetail, getUserConfig, type Message, type ConversationSummary,
  ```

- [ ] **Step 3: Add `/compact-merge` slash command handler in `App.tsx`**

  Add this block immediately after the `/compact` handler (after the `return;` on line ~331):

  ```typescript
  if (message === '/compact-merge') {
    if (!conversationId.current) {
      setMessages((prev) => [
        ...prev,
        { role: 'ai', content: 'Start a conversation before merging compacts.' },
      ]);
      setLoading(false);
      return;
    }
    try {
      const token = await getAccessToken();
      const { summary } = await compactMergeConversation(conversationId.current, token);
      setMessages((prev) => [...prev, { role: 'compact', content: summary }]);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Compact merge failed.';
      setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
    } finally {
      setLoading(false);
    }
    return;
  }
  ```

- [ ] **Step 4: TypeScript check**

  ```powershell
  cd C:\Users\Lenovo\IdeaProjects\agent-suite\frontend; npx tsc --noEmit
  ```
  Expected: no output (no errors).

- [ ] **Step 5: Commit**

  ```
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/api.ts frontend/src/App.tsx
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add /compact-merge slash command to frontend"
  ```

---

### Task 3: Update CLAUDE.md and final verification

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add the new endpoint to the API section in `CLAUDE.md`**

  In the API section, after the `/compact` entry, add:

  ```
  POST /ai/conversations/{externalId}/compact-merge
    Concatenates the last two compact rows for a conversation (older first, newer second,
    separated by ---) and stores the result as a new compact row. No LLM is involved.
    Auth required; caller must own the conversation (404 otherwise).
    Returns { "summary": "..." } on success, 400 if fewer than two compacts exist, 404 if not found.
  ```

- [ ] **Step 2: Run all backend tests one final time**

  ```powershell
  cd C:\Users\Lenovo\IdeaProjects\agent-suite; .\mvnw.cmd test
  ```
  Expected: all pass.

- [ ] **Step 3: Build frontend**

  ```powershell
  cd C:\Users\Lenovo\IdeaProjects\agent-suite\frontend; npm run build
  ```
  Expected: `✓ built in ...` with no TypeScript errors.

- [ ] **Step 4: Commit**

  ```
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add CLAUDE.md
  git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "docs: document /compact-merge endpoint in CLAUDE.md"
  ```
