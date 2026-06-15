# /compact Slash Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/compact` slash command that summarises the conversation via LLM, stores the result as a `compact` message type, and truncates pre-compact history on future LLM calls while keeping the full conversation visible in the UI.

**Architecture:** `ChatOrchestrationService` gains a `compact()` method (LLM call + DB write) and an updated `loadHistory` that uses the most recent compact record as a truncation point; `AiController` exposes `POST /ai/conversations/{externalId}/compact`; `ConversationService.getConversationDetail` maps `"compact"` records to the frontend; frontend intercepts `/compact` input, calls the endpoint, and renders a distinct visual block.

**Tech Stack:** Spring Boot 3.5, Java 21, MockMvc, AssertJ, Mockito, React 19, TypeScript.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java` | `loadHistory` truncation + `compact()` + `buildTranscript()` |
| Modify | `src/main/java/com/example/agentsuite/controller/AiController.java` | `POST /ai/conversations/{externalId}/compact` |
| Modify | `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java` | Add `"compact"` case to `getConversationDetail` switch |
| Modify | `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java` | Tests for `loadHistory` truncation and `compact()` |
| Modify | `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | Tests for compact endpoint (200, 404, 400) |
| Modify | `frontend/src/api.ts` | Add `'compact'` to `Message` role union + `compactConversation()` |
| Modify | `frontend/src/App.tsx` | `/compact` interceptor in `handleSubmit` + compact block renderer |
| Modify | `CLAUDE.md` | Document `compact` message type and new endpoint |

---

## Task 1: `loadHistory` compact truncation (TDD)

**Files:**
- Modify: `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`
- Modify: `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`

### Background

`loadHistory` is private. Tests drive it indirectly by stubbing `conversationService.getMessages()` and capturing the `List<HistoryMessage>` argument passed to `chatService.chatStreamWithHistory()` via `ArgumentCaptor` — the same pattern used in `firstTurn_historyPassedToLlmContainsOnlySystemPrompt`.

- [ ] **Step 1: Add three failing tests to `ChatOrchestrationServiceTest`**

Add these tests after `firstTurn_historyPassedToLlmContainsOnlySystemPrompt`:

```java
private MessageRecord rec(String type, String message) {
    MessageRecord r = mock(MessageRecord.class);
    when(r.getType()).thenReturn(type);
    when(r.getMessage()).thenReturn(message);
    return r;
}

@SuppressWarnings("unchecked")
private List<HistoryMessage> captureHistory(String externalId, long convDbId,
                                             List<MessageRecord> messages) {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(convDbId);
    when(conv.getUserId()).thenReturn(1L);
    when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
    when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
    when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of("sys"));
    when(conversationService.getMessages(convDbId)).thenReturn(messages);

    doAnswer(inv -> {
        Consumer<ChatEvent> emitter = inv.getArgument(2);
        emitter.accept(new ChatEvent.Content("ok"));
        emitter.accept(new ChatEvent.Done());
        return null;
    }).when(chatService).chatStreamWithHistory(any(), any(), any());

    orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "sys", "q", "", e -> {}, new Object[0]);

    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(chatService, atLeastOnce()).chatStreamWithHistory(captor.capture(), any(), any());
    return captor.getValue();
}

@Test
void loadHistory_noCompact_includesAllSubstantiveMessages() {
    String externalId = UUID.randomUUID().toString();
    List<MessageRecord> msgs = List.of(
        rec("system_prompt", "sys"),
        rec("user", "hello"),
        rec("assistant", "hi")
    );
    List<HistoryMessage> history = captureHistory(externalId, 50L, msgs);
    assertThat(history).hasSize(3);
    assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
    assertThat(history.get(1)).isInstanceOf(HistoryMessage.User.class);
    assertThat(history.get(2)).isInstanceOf(HistoryMessage.Assistant.class);
}

@Test
void loadHistory_compactInMiddle_dropsMessagesBeforeCompactAndEmitsCompactAsUser() {
    String externalId = UUID.randomUUID().toString();
    List<MessageRecord> msgs = List.of(
        rec("system_prompt", "sys"),
        rec("user", "old message"),
        rec("assistant", "old reply"),
        rec("compact", "summary of earlier"),
        rec("user", "new question"),
        rec("assistant", "new answer")
    );
    List<HistoryMessage> history = captureHistory(externalId, 51L, msgs);
    // SystemPrompt + User(compact) + User(new) + Assistant(new)
    assertThat(history).hasSize(4);
    assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
    assertThat(history.get(1)).isInstanceOf(HistoryMessage.User.class);
    assertThat(((HistoryMessage.User) history.get(1)).content())
            .startsWith("Previous conversation summary:\n\nsummary of earlier");
    assertThat(history.get(2)).isInstanceOf(HistoryMessage.User.class);
    assertThat(((HistoryMessage.User) history.get(2)).content()).isEqualTo("new question");
    assertThat(history.get(3)).isInstanceOf(HistoryMessage.Assistant.class);
}

@Test
void loadHistory_multipleCompacts_usesOnlyMostRecent() {
    String externalId = UUID.randomUUID().toString();
    List<MessageRecord> msgs = List.of(
        rec("compact", "first summary"),
        rec("user", "middle message"),
        rec("compact", "second summary"),
        rec("user", "latest question")
    );
    List<HistoryMessage> history = captureHistory(externalId, 52L, msgs);
    // No SystemPrompt (empty), User(compact2), User(latest)
    assertThat(history).hasSize(2);
    assertThat(((HistoryMessage.User) history.get(0)).content())
            .startsWith("Previous conversation summary:\n\nsecond summary");
    assertThat(((HistoryMessage.User) history.get(1)).content()).isEqualTo("latest question");
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
.\mvnw.cmd test "-Dtest=ChatOrchestrationServiceTest"
```
Expected: 3 new tests fail (history returned without compact truncation).

- [ ] **Step 3: Update `loadHistory` in `ChatOrchestrationService`**

Replace the existing `loadHistory` method and add a private helper:

```java
private List<HistoryMessage> loadHistory(long conversationDbId) {
    List<MessageRecord> records = conversationService.getMessages(conversationDbId);

    String lastSystemPrompt = null;
    for (MessageRecord r : records) {
        if ("system_prompt".equals(r.getType())) lastSystemPrompt = r.getMessage();
    }

    int compactIndex = -1;
    for (int i = records.size() - 1; i >= 0; i--) {
        if ("compact".equals(records.get(i).getType())) {
            compactIndex = i;
            break;
        }
    }

    List<HistoryMessage> history = new ArrayList<>();
    if (lastSystemPrompt != null && !lastSystemPrompt.isEmpty()) {
        history.add(new HistoryMessage.SystemPrompt(lastSystemPrompt));
    }

    if (compactIndex >= 0) {
        history.add(new HistoryMessage.User(
                "Previous conversation summary:\n\n" + records.get(compactIndex).getMessage()));
        for (int i = compactIndex + 1; i < records.size(); i++) {
            addIfSubstantive(history, records.get(i));
        }
    } else {
        for (MessageRecord r : records) {
            addIfSubstantive(history, r);
        }
    }

    return history;
}

private static void addIfSubstantive(List<HistoryMessage> history, MessageRecord r) {
    HistoryMessage msg = switch (r.getType()) {
        case "user"        -> new HistoryMessage.User(r.getMessage());
        case "assistant"   -> new HistoryMessage.Assistant(r.getMessage());
        case "tool_call"   -> new HistoryMessage.ToolCall(r.getMessage());
        case "tool_result" -> new HistoryMessage.ToolResult(r.getMessage());
        default            -> null;
    };
    if (msg != null) history.add(msg);
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```
.\mvnw.cmd test "-Dtest=ChatOrchestrationServiceTest"
```
Expected: BUILD SUCCESS, all tests pass (existing + 3 new).

- [ ] **Step 5: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: truncate history at most recent compact record in loadHistory"
```

---

## Task 2: `compact()` method in `ChatOrchestrationService` (TDD)

**Files:**
- Modify: `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`
- Modify: `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`

### Background

`compact(externalId, userId)` is a new public method on `ChatOrchestrationService`. It resolves the conversation, checks ownership (same 404-as-not-found pattern as `getConversationDetail`), builds a plain-text transcript from the message records, calls the LLM synchronously via `ChatService.chat()`, stores the result as a `"compact"` message, and returns the summary string.

`buildTranscript` is package-private static so it can also be tested directly.

- [ ] **Step 1: Add failing tests**

Add to `ChatOrchestrationServiceTest` (in the same file, `import java.util.function.Consumer;` is already there):

```java
// --- compact() tests ---

@Test
void compact_conversationNotFound_throwsNoSuchElement() {
    when(conversationService.findByExternalId("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> orchestration.compact("missing", 1L))
            .isInstanceOf(java.util.NoSuchElementException.class);
}

@Test
void compact_wrongOwner_throwsNoSuchElement() {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(99L);
    when(conv.getUserId()).thenReturn(2L); // different user
    when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
    assertThatThrownBy(() -> orchestration.compact("abc", 1L))
            .isInstanceOf(java.util.NoSuchElementException.class);
}

@Test
void compact_emptyHistory_throwsIllegalArgument() {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(20L);
    when(conv.getUserId()).thenReturn(1L);
    when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
    when(conversationService.getMessages(20L)).thenReturn(List.of(
        rec("system_prompt", "be helpful"),
        rec("model_change", "deepseek-v4-pro")
    ));
    when(conversationService.findLastModelChange(20L)).thenReturn(Optional.of("deepseek-v4-pro"));
    assertThatThrownBy(() -> orchestration.compact("abc", 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nothing to compact");
}

@Test
void compact_validConversation_callsLlmAndStoresSummary() {
    ConversationRecord conv = mock(ConversationRecord.class);
    when(conv.getConversationId()).thenReturn(21L);
    when(conv.getUserId()).thenReturn(1L);
    when(conversationService.findByExternalId("abc")).thenReturn(Optional.of(conv));
    when(conversationService.getMessages(21L)).thenReturn(List.of(
        rec("user", "Hello"),
        rec("assistant", "Hi there")
    ));
    when(conversationService.findLastModelChange(21L)).thenReturn(Optional.of("deepseek-v4-pro"));
    when(chatService.chat(anyString(), anyString())).thenReturn(ChatResponse.of("Compact summary"));

    String result = orchestration.compact("abc", 1L);

    assertThat(result).isEqualTo("Compact summary");
    verify(conversationService).addMessage(21L, 1L, "compact", "Compact summary");
}

@Test
void buildTranscript_includesUserAssistantAndCompactRecords() {
    List<MessageRecord> msgs = List.of(
        rec("system_prompt", "ignored"),
        rec("model_change", "ignored"),
        rec("user", "hi"),
        rec("assistant", "hello"),
        rec("compact", "earlier summary"),
        rec("tool_call", "[{\"name\":\"ls\"}]"),
        rec("tool_result", "[{\"result\":\"file\"}]")
    );
    String transcript = ChatOrchestrationService.buildTranscript(msgs);
    assertThat(transcript).contains("[User]: hi");
    assertThat(transcript).contains("[Assistant]: hello");
    assertThat(transcript).contains("[Summary]: earlier summary");
    assertThat(transcript).contains("[Tool call]:");
    assertThat(transcript).contains("[Tool result]:");
    assertThat(transcript).doesNotContain("ignored");
}
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` at the top of the test file.

- [ ] **Step 2: Run tests to confirm they fail**

```
.\mvnw.cmd test "-Dtest=ChatOrchestrationServiceTest"
```
Expected: BUILD FAILURE — `compact` method does not exist yet.

- [ ] **Step 3: Add `compact()` and `buildTranscript()` to `ChatOrchestrationService`**

Add these after the `chatStream` method. Add the import `import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;` at the top if not already present (check existing imports):

```java
private static final String SUMMARY_SYSTEM_PROMPT =
        "Summarise the conversation below concisely. Preserve the key context, decisions, " +
        "facts, and any ongoing tasks. Write in the third person and omit pleasantries.";

public String compact(String externalId, long userId) {
    ConversationRecord conv = conversationService.findByExternalId(externalId)
            .filter(c -> c.getUserId().equals(userId))
            .orElseThrow(() -> new java.util.NoSuchElementException("Conversation not found: " + externalId));

    long convDbId = conv.getConversationId();
    List<MessageRecord> records = conversationService.getMessages(convDbId);

    String transcript = buildTranscript(records);
    if (transcript.isBlank()) {
        throw new IllegalArgumentException("Nothing to compact.");
    }

    String model = conversationService.findLastModelChange(convDbId).orElse("deepseek-v4-pro");
    ChatService service = modelRegistry.get(model);
    if (service == null) service = modelRegistry.get("deepseek-v4-pro");

    String summary = service.chat(SUMMARY_SYSTEM_PROMPT, transcript).content();
    conversationService.addMessage(convDbId, userId, "compact", summary);
    return summary;
}

static String buildTranscript(List<MessageRecord> records) {
    StringBuilder sb = new StringBuilder();
    for (MessageRecord r : records) {
        String line = switch (r.getType()) {
            case "user"        -> "[User]: " + r.getMessage();
            case "assistant"   -> "[Assistant]: " + r.getMessage();
            case "tool_call"   -> "[Tool call]: " + r.getMessage();
            case "tool_result" -> "[Tool result]: " + r.getMessage();
            case "compact"     -> "[Summary]: " + r.getMessage();
            default            -> null;
        };
        if (line != null) sb.append(line).append('\n');
    }
    return sb.toString().trim();
}
```

`ChatOrchestrationService` already imports `ConversationRecord` via `com.example.agentsuite.jooq.generated.tables.records.ConversationRecord` (used in `resolveConversation`). `MessageRecord` should also already be imported (used in `loadHistory`). Verify imports are present; add any that are missing.

- [ ] **Step 4: Run tests to confirm they pass**

```
.\mvnw.cmd test "-Dtest=ChatOrchestrationServiceTest"
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add compact() method and buildTranscript() to ChatOrchestrationService"
```

---

## Task 3: `POST /ai/conversations/{externalId}/compact` endpoint (TDD)

**Files:**
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

### Background

`AiControllerTest` uses `@WebMvcTest(AiController.class)` with `@MockBean ChatOrchestrationService orchestrationService`. The new endpoint delegates entirely to `orchestrationService.compact(externalId, userId)` and maps exceptions to HTTP status codes:
- `NoSuchElementException` → 404
- `IllegalArgumentException` → 400 with `{ "error": "..." }`
- Success → 200 with `{ "summary": "..." }`

Read `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` to find the admin user mock setup (the test sets up `userId` via `UserResolverFilter.ATTR_USER_ID` on the mock request). Use the same pattern.

- [ ] **Step 1: Add failing tests to `AiControllerTest`**

Read the file first to find where `ATTR_USER_ID` is set in the test `setUp`. Then add these tests at the end of the class:

```java
@Test
void compact_validRequest_returns200WithSummary() throws Exception {
    when(orchestrationService.compact(eq("conv-123"), anyLong()))
            .thenReturn("This is the summary.");

    mockMvc.perform(post("/ai/conversations/conv-123/compact"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.summary").value("This is the summary."));
}

@Test
void compact_conversationNotFound_returns404() throws Exception {
    when(orchestrationService.compact(eq("unknown"), anyLong()))
            .thenThrow(new java.util.NoSuchElementException("Conversation not found"));

    mockMvc.perform(post("/ai/conversations/unknown/compact"))
            .andExpect(status().isNotFound());
}

@Test
void compact_nothingToCompact_returns400WithError() throws Exception {
    when(orchestrationService.compact(eq("empty-conv"), anyLong()))
            .thenThrow(new IllegalArgumentException("Nothing to compact."));

    mockMvc.perform(post("/ai/conversations/empty-conv/compact"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Nothing to compact."));
}
```

Add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;` and `import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;` if not already present.

- [ ] **Step 2: Run tests to confirm they fail**

```
.\mvnw.cmd test "-Dtest=AiControllerTest#compact_validRequest_returns200WithSummary+compact_conversationNotFound_returns404+compact_nothingToCompact_returns400WithError"
```
Expected: BUILD FAILURE — endpoint does not exist yet, 404 from Spring.

- [ ] **Step 3: Add the endpoint to `AiController`**

Add this method after `getConversationDetail`:

```java
@PostMapping("/ai/conversations/{externalId}/compact")
public ResponseEntity<Map<String, String>> compact(
        @PathVariable String externalId,
        HttpServletRequest request) {
    long userId = currentUserId(request);
    try {
        String summary = orchestrationService.compact(externalId, userId);
        return ResponseEntity.ok(Map.of("summary", summary));
    } catch (NoSuchElementException e) {
        return ResponseEntity.notFound().build();
    } catch (IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
```

`NoSuchElementException` and `Map` are already imported. No new imports needed.

- [ ] **Step 4: Run tests to confirm they pass**

```
.\mvnw.cmd test "-Dtest=AiControllerTest"
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/AiController.java src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add POST /ai/conversations/{externalId}/compact endpoint"
```

---

## Task 4: `compact` in `getConversationDetail` (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`

### Background

`ConversationService.getConversationDetail` iterates message records and maps them to `MessageDto`. It currently has a `default -> {}` case that silently drops unknown types. Adding `"compact"` maps it to `role: "compact"` so it appears in the UI when a conversation is loaded.

Test directly with a real `ConversationService` (no Spring context), mocked repositories. This test will fail before the switch is updated and pass after.

- [ ] **Step 1: Create `ConversationServiceTest` with a failing test**

Create `src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java`:

```java
package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationServiceTest {

    private ConversationRepository conversationRepository;
    private MessageRepository messageRepository;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        messageRepository = mock(MessageRepository.class);
        conversationService = new ConversationService(conversationRepository, messageRepository);
    }

    private MessageRecord rec(String type, String message) {
        MessageRecord r = mock(MessageRecord.class);
        when(r.getType()).thenReturn(type);
        when(r.getMessage()).thenReturn(message);
        return r;
    }

    @Test
    void getConversationDetail_compactRecord_mappedToCompactRole() {
        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(1L);
        when(conv.getUserId()).thenReturn(1L);
        when(conv.getExternalId()).thenReturn("ext-1");
        when(conv.getConversationName()).thenReturn("test");
        when(conv.getCreateTime()).thenReturn(java.time.OffsetDateTime.now());
        when(conv.getRootDirectory()).thenReturn("");
        when(conversationRepository.findByExternalId("ext-1")).thenReturn(Optional.of(conv));
        when(messageRepository.findByConversationId(1L)).thenReturn(List.of(
                rec("user", "hello"),
                rec("compact", "this is the summary"),
                rec("assistant", "hi")
        ));

        ConversationDetailDto detail = conversationService.getConversationDetail("ext-1", 1L);

        assertThat(detail.messages()).hasSize(3);
        assertThat(detail.messages().get(1).role()).isEqualTo("compact");
        assertThat(detail.messages().get(1).content()).isEqualTo("this is the summary");
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
.\mvnw.cmd test "-Dtest=ConversationServiceTest"
```
Expected: test fails — `"compact"` falls to `default -> {}` and the compact message is absent from the returned DTO (list has size 2, not 3).

- [ ] **Step 3: Add `"compact"` case to `ConversationService.getConversationDetail`**

In `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`, find the switch inside `getConversationDetail` and add before `default`:

```java
case "compact" ->
    messages.add(new ConversationDetailDto.MessageDto("compact", r.getMessage(), List.of()));
```

The full switch after the change:
```java
switch (r.getType()) {
    case "model_change" -> {
        initialModel = r.getMessage();
        if (!r.getMessage().isEmpty())
            messages.add(new ConversationDetailDto.MessageDto("meta", "model:" + r.getMessage(), List.of()));
    }
    case "system_prompt" -> {
        systemPrompt = r.getMessage();
        if (!r.getMessage().isEmpty())
            messages.add(new ConversationDetailDto.MessageDto("meta", "system:" + r.getMessage(), List.of()));
    }
    case "user" -> {
        toolCallBuffer.clear();
        messages.add(new ConversationDetailDto.MessageDto("user", r.getMessage(), List.of()));
    }
    case "tool_call"  -> toolCallBuffer.addAll(parseToolCalls(r.getMessage()));
    case "tool_result" -> {}
    case "assistant" -> {
        messages.add(new ConversationDetailDto.MessageDto(
                "ai", r.getMessage(), List.copyOf(toolCallBuffer)));
        toolCallBuffer.clear();
    }
    case "compact" ->
        messages.add(new ConversationDetailDto.MessageDto("compact", r.getMessage(), List.of()));
    default -> {}
}
```

- [ ] **Step 4: Run full suite**

```
.\mvnw.cmd test
```
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java src/test/java/com/example/agentsuite/jooq/service/ConversationServiceTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: include compact messages in getConversationDetail response"
```

---

## Task 5: Frontend — `api.ts` + `App.tsx`

**Files:**
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx`

### Step A: `api.ts` changes

- [ ] **Step 1: Add `'compact'` to `Message.role` and add `compactConversation()`**

In `src/api.ts`:

1. Change line 11:
```ts
// Before:
  role: 'user' | 'ai' | 'meta';
// After:
  role: 'user' | 'ai' | 'meta' | 'compact';
```

2. Add `compactConversation` after `getConversationDetail`:
```ts
export const compactConversation = async (
  conversationId: string,
  token?: string | null,
): Promise<{ summary: string }> => {
  const res = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(conversationId)}/compact`,
    { method: 'POST', headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { error?: string }).error ?? `Compact failed (${res.status})`);
  }
  return res.json();
};
```

### Step B: `App.tsx` changes

- [ ] **Step 2: Import `compactConversation` in `App.tsx`**

On line 2 of `App.tsx`, the import from `./api` already lists several exports. Add `compactConversation` to it:

```ts
// Before:
import {
  chatStream, execTool, getDirectories, getConversationDetail, getUserConfig, type Message, type ConversationSummary,
} from './api';
// After:
import {
  chatStream, compactConversation, execTool, getDirectories, getConversationDetail, getUserConfig, type Message, type ConversationSummary,
} from './api';
```

- [ ] **Step 3: Add `/compact` interceptor in `handleSubmit`**

In `handleSubmit`, the `!`-prefix block ends with `return;` at line ~294. Immediately after that `return;` (still inside the outer `if (message.startsWith('!'))` block but wait — actually after the entire `if` block), add:

```ts
if (message === '/compact') {
  if (!conversationId.current) {
    setMessages((prev) => [
      ...prev,
      { role: 'ai', content: 'Start a conversation before compacting.' },
    ]);
    setLoading(false);
    return;
  }
  try {
    const token = await getAccessToken();
    const { summary } = await compactConversation(conversationId.current, token);
    setMessages((prev) => [...prev, { role: 'compact', content: summary }]);
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : 'Compact failed.';
    setMessages((prev) => [...prev, { role: 'ai', content: `Error: ${msg}` }]);
  } finally {
    setLoading(false);
  }
  return;
}
```

Place this block **after** the `if (message.startsWith('!')) { … return; }` block and **before** the `const matched = PROMPT_BANK.find(...)` line.

- [ ] **Step 4: Add compact message rendering in the message list**

In the `messages.map((msg, i) => {` block (around line 405), there is currently:
```tsx
if (msg.role === 'meta') return <MetaMessage key={i} content={msg.content} />;
```

Add a compact branch immediately after it:
```tsx
if (msg.role === 'compact') {
  return (
    <div key={i} className="self-stretch rounded border border-gray-700 bg-gray-800/50 px-4 py-3 text-sm text-gray-300">
      <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Conversation compacted</p>
      <p className="whitespace-pre-wrap">{msg.content}</p>
    </div>
  );
}
```

- [ ] **Step 5: TypeScript check**

```
cd frontend && npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 6: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/api.ts frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: /compact slash command in frontend with inline compact block renderer"
```

---

## Task 6: Full build + CLAUDE.md docs

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Run the full test suite**

```
.\mvnw.cmd test
```
Expected: BUILD SUCCESS.

- [ ] **Step 2: Update CLAUDE.md — message types table**

Find the message types table in CLAUDE.md (the one with columns `Type | Sent to LLM? | Content`). Add a new row after `model_change`:

```
| `compact` | No | LLM-generated summary of conversation history up to this point |
```

- [ ] **Step 3: Update CLAUDE.md — API section**

After the `GET /ai/conversations/{externalId}` entry, add:

```
POST /ai/conversations/{externalId}/compact
  Summarises conversation history via LLM and stores result as a `compact` message.
  Auth required; caller must own the conversation (404 otherwise).
  Returns { "summary": "..." } on success, 400 if nothing to compact, 404 if not found.
```

- [ ] **Step 4: Update CLAUDE.md — Architecture section**

In `ChatOrchestrationService` description, add at the end:

```
`compact(externalId, userId)` summarises a conversation via the LLM and stores the result as a `compact` message. `loadHistory` uses the most recent `compact` record as a truncation point — messages before it are dropped, the compact summary is injected as a `HistoryMessage.User`, and subsequent messages accumulate normally.
```

- [ ] **Step 5: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add CLAUDE.md
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "docs: document /compact slash command in CLAUDE.md"
```
