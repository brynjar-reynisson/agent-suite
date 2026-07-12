# Turn Token Usage Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show per-turn LLM token usage (input, output, and — where the provider reports it — prompt-cache read/write tokens) as a subtle footer on each assistant message in the chat UI.

**Architecture:** LangChain4j's `AiServices` already runs the full tool-calling loop internally; switching the internal `AssistantService.chat(...)` return type from `String` to LangChain4j's `Result<String>` exposes `.tokenUsage()`, which LangChain4j aggregates across every LLM round-trip in the loop (confirmed via `TokenUsage.sum()`/`add()`, and `AnthropicTokenUsage.add()` which preserves cache-token fields through the sum). That aggregated `TokenUsage` is converted to a new provider-agnostic `TurnUsage` record, attached to a new `usage` field on `ChatEvent.Done`, forwarded unchanged through `ChatOrchestrationService`, serialized as JSON on the `done` SSE event by `AiController`, and rendered as a footer on the frontend's trailing AI message bubble.

**Tech Stack:** Spring Boot 3.5, LangChain4j 1.16.2 (`dev.langchain4j.service.Result`, `dev.langchain4j.model.output.TokenUsage`, `dev.langchain4j.model.anthropic.AnthropicTokenUsage`), Jackson (default record serialization, already used for `ChatEvent.ToolBatch.ToolExecution` and friends), React + TypeScript frontend, `@microsoft/fetch-event-source` for SSE, Playwright for e2e tests.

## Global Constraints

- Raw token counts only — no cost estimation, no pricing table (per spec).
- No database/migration changes in this phase (per spec) — `TurnUsage` field names are chosen to map cleanly onto a future `message` table schema, but no schema work happens here.
- Only the final aggregated `Done` event carries usage — no per-tool-iteration usage events (per spec).
- `cacheReadTokens`/`cacheWriteTokens` are `null` (not `0`) when the provider doesn't report cache stats — this distinction must be preserved end-to-end (backend nullable `Integer`, frontend `number | null`, UI omits the cache segment entirely rather than showing "0 cached").
- Every existing `new ChatEvent.Done()` call site (~30, spanning `ChatOrchestrationService`'s non-LLM directive paths and multiple test files) must keep compiling and behaving identically (usage defaults to `null`).

---

### Task 1: `TurnUsage` record + `ChatEvent.Done` payload

**Files:**
- Create: `src/main/java/com/example/agentsuite/service/TurnUsage.java`
- Modify: `src/main/java/com/example/agentsuite/service/ChatEvent.java`
- Test: `src/test/java/com/example/agentsuite/service/ChatEventTest.java` (new file)

**Interfaces:**
- Produces: `TurnUsage(int inputTokens, int outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens)` — a plain record, `cacheReadTokens`/`cacheWriteTokens` nullable.
- Produces: `ChatEvent.Done(TurnUsage usage)` with a no-arg constructor `Done()` that delegates to `Done(null)`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.agentsuite.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventTest {

    @Test
    void done_noArgConstructor_hasNullUsage() {
        ChatEvent.Done done = new ChatEvent.Done();
        assertThat(done.usage()).isNull();
    }

    @Test
    void done_withUsage_exposesUsage() {
        TurnUsage usage = new TurnUsage(120, 45, 30, 5);
        ChatEvent.Done done = new ChatEvent.Done(usage);
        assertThat(done.usage()).isEqualTo(usage);
        assertThat(done.usage().inputTokens()).isEqualTo(120);
        assertThat(done.usage().outputTokens()).isEqualTo(45);
        assertThat(done.usage().cacheReadTokens()).isEqualTo(30);
        assertThat(done.usage().cacheWriteTokens()).isEqualTo(5);
    }

    @Test
    void done_withNullCacheFields_allowsNulls() {
        TurnUsage usage = new TurnUsage(10, 5, null, null);
        assertThat(usage.cacheReadTokens()).isNull();
        assertThat(usage.cacheWriteTokens()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ChatEventTest`
Expected: FAIL — compile error, `TurnUsage` does not exist and `ChatEvent.Done` has no `usage()` accessor / no `(TurnUsage)` constructor.

- [ ] **Step 3: Create `TurnUsage.java`**

```java
package com.example.agentsuite.service;

public record TurnUsage(int inputTokens, int outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens) {
}
```

- [ ] **Step 4: Update `ChatEvent.java`'s `Done` record**

In `src/main/java/com/example/agentsuite/service/ChatEvent.java`, replace:

```java
    record Done() implements ChatEvent {}
```

with:

```java
    record Done(TurnUsage usage) implements ChatEvent {
        public Done() {
            this(null);
        }
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ChatEventTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Run the full backend test suite to confirm no existing `ChatEvent.Done` call site broke**

Run: `./mvnw test`
Expected: PASS — all existing tests (including the ~30 call sites using `new ChatEvent.Done()` in `AiControllerTest`, `ChatOrchestrationServiceTest`, and `AbstractLangChain4jChatServiceTest`) still compile and pass unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/TurnUsage.java src/main/java/com/example/agentsuite/service/ChatEvent.java src/test/java/com/example/agentsuite/service/ChatEventTest.java
git commit -m "feat: add TurnUsage record and usage payload on ChatEvent.Done"
```

---

### Task 2: Capture token usage in `AbstractLangChain4jChatService`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`
- Test: `src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java`

**Interfaces:**
- Consumes: `TurnUsage(int inputTokens, int outputTokens, Integer cacheReadTokens, Integer cacheWriteTokens)` from Task 1.
- Consumes: `ChatEvent.Done(TurnUsage usage)` from Task 1.
- Produces: `static TurnUsage toTurnUsage(dev.langchain4j.model.output.TokenUsage usage)` — package-private static helper, same visibility/convention as the existing `toUserFacingError` static method, directly testable and already used as a reference pattern in `AbstractLangChain4jChatServiceTest`.

- [ ] **Step 1: Write the failing test — usage flows into the `Done` event**

Add to `src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java` (add these imports alongside the existing ones: `import dev.langchain4j.model.anthropic.AnthropicTokenUsage;`, `import dev.langchain4j.model.output.TokenUsage;`):

```java
    @Test
    void chatStream_capturesTokenUsage_onDoneEvent() {
        ChatModel mockModel = mock(ChatModel.class);
        AiMessage aiResponse = AiMessage.from("hello there");
        TokenUsage usage = new TokenUsage(100, 40, 140);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).tokenUsage(usage).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new ArrayList<>();
        service.chatStream("", "hello", events::add);

        ChatEvent.Done done = (ChatEvent.Done) events.stream()
                .filter(e -> e instanceof ChatEvent.Done)
                .findFirst()
                .orElseThrow();
        assertThat(done.usage()).isNotNull();
        assertThat(done.usage().inputTokens()).isEqualTo(100);
        assertThat(done.usage().outputTokens()).isEqualTo(40);
        assertThat(done.usage().cacheReadTokens()).isNull();
        assertThat(done.usage().cacheWriteTokens()).isNull();
    }

    @Test
    void chatStream_anthropicCacheTokens_populateTurnUsage() {
        ChatModel mockModel = mock(ChatModel.class);
        AiMessage aiResponse = AiMessage.from("hello there");
        AnthropicTokenUsage usage = AnthropicTokenUsage.builder()
                .inputTokenCount(100)
                .outputTokenCount(40)
                .cacheReadInputTokens(30)
                .cacheCreationInputTokens(5)
                .build();
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).tokenUsage(usage).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new ArrayList<>();
        service.chatStream("", "hello", events::add);

        ChatEvent.Done done = (ChatEvent.Done) events.stream()
                .filter(e -> e instanceof ChatEvent.Done)
                .findFirst()
                .orElseThrow();
        assertThat(done.usage().cacheReadTokens()).isEqualTo(30);
        assertThat(done.usage().cacheWriteTokens()).isEqualTo(5);
    }

    @Test
    void toTurnUsage_nullInput_returnsNull() {
        assertThat(AbstractLangChain4jChatService.toTurnUsage(null)).isNull();
    }

    @Test
    void toTurnUsage_nullTokenCounts_defaultToZero() {
        TokenUsage usage = new TokenUsage(null, null, null);
        var turnUsage = AbstractLangChain4jChatService.toTurnUsage(usage);
        assertThat(turnUsage.inputTokens()).isEqualTo(0);
        assertThat(turnUsage.outputTokens()).isEqualTo(0);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=AbstractLangChain4jChatServiceTest`
Expected: FAIL — `toTurnUsage` doesn't exist yet, and the `Done` event's `usage()` is `null` because `chatStreamWithHistory` doesn't populate it.

- [ ] **Step 3: Change `AssistantService` to return `Result<String>`**

In `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`, add the import:

```java
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
```

Change:

```java
    private interface AssistantService {
        String chat(@dev.langchain4j.service.UserMessage String userMessage);
    }
```

to:

```java
    private interface AssistantService {
        Result<String> chat(@dev.langchain4j.service.UserMessage String userMessage);
    }
```

- [ ] **Step 4: Update the `chat()` method's tool-calling branch**

Change:

```java
        AssistantService svc = buildAiService(
                systemPrompt != null ? systemPrompt : "",
                List.of(),
                collector,
                tools
        );
        String content = svc.chat(userMessage);
        return new ChatResponse(collectedCalls, content);
```

to:

```java
        AssistantService svc = buildAiService(
                systemPrompt != null ? systemPrompt : "",
                List.of(),
                collector,
                tools
        );
        Result<String> result = svc.chat(userMessage);
        return new ChatResponse(collectedCalls, result.content());
```

- [ ] **Step 5: Update `chatStreamWithHistory` to capture and emit usage**

Change:

```java
        try {
            String response = svc.chat(userMessage);
            emitter.accept(new ChatEvent.Content(response));
            emitter.accept(new ChatEvent.Done());
        } catch (Exception e) {
            emitter.accept(new ChatEvent.Error(toUserFacingError(e)));
            emitter.accept(new ChatEvent.Done());
        }
```

to:

```java
        try {
            Result<String> result = svc.chat(userMessage);
            emitter.accept(new ChatEvent.Content(result.content()));
            emitter.accept(new ChatEvent.Done(toTurnUsage(result.tokenUsage())));
        } catch (Exception e) {
            emitter.accept(new ChatEvent.Error(toUserFacingError(e)));
            emitter.accept(new ChatEvent.Done());
        }
```

- [ ] **Step 6: Add the `toTurnUsage` helper**

Add this method next to `toUserFacingError` (same class, package-private static so the test can call it directly):

```java
    static TurnUsage toTurnUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        int input = usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int output = usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        Integer cacheRead = null;
        Integer cacheWrite = null;
        if (usage instanceof AnthropicTokenUsage anthropicUsage) {
            cacheRead = anthropicUsage.cacheReadInputTokens();
            cacheWrite = anthropicUsage.cacheCreationInputTokens();
        }
        return new TurnUsage(input, output, cacheRead, cacheWrite);
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw test -Dtest=AbstractLangChain4jChatServiceTest`
Expected: PASS (all tests, including the 4 new ones)

- [ ] **Step 8: Run the full backend test suite**

Run: `./mvnw test`
Expected: PASS — no other test depended on `AssistantService.chat`'s return type.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java
git commit -m "feat: capture LLM token usage and emit it on ChatEvent.Done"
```

---

### Task 3: Transport usage on the `done` SSE event (`AiController`)

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Test: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

**Interfaces:**
- Consumes: `ChatEvent.Done(TurnUsage usage)` from Task 1, populated per Task 2.
- Produces: `done` SSE event whose `data` is the JSON-serialized `TurnUsage` (field names `inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheWriteTokens`) when usage is present, or an empty string when it isn't (unchanged from today).

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` (add `import com.example.agentsuite.service.TurnUsage;` alongside the existing service imports):

```java
    @Test
    void chat_doneEventWithUsage_includesTokenCountsInSseBody() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done(new TurnUsage(120, 45, 30, 5)));
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("\"inputTokens\":120")))
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("\"outputTokens\":45")))
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("\"cacheReadTokens\":30")))
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("\"cacheWriteTokens\":5")));
    }

    @Test
    void chat_doneEventWithoutUsage_emitsEmptyDoneData() throws Exception {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatEvent> consumer = inv.getArgument(7);
            consumer.accept(new ChatEvent.Done());
            return null;
        }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(), any(),
                any(Consumer.class), any());

        MvcResult mvcResult = mockMvc.perform(get("/ai/chat"))
                .andExpect(request().asyncStarted()).andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("inputTokens"))));
    }
```

- [ ] **Step 2: Run tests to verify the first one fails**

Run: `./mvnw test -Dtest=AiControllerTest#chat_doneEventWithUsage_includesTokenCountsInSseBody`
Expected: FAIL — the `done` event's data is currently always an empty string, so none of the `containsString` assertions match.

- [ ] **Step 3: Update the `Done` case in `AiController`**

In `src/main/java/com/example/agentsuite/controller/AiController.java`, change:

```java
                                case ChatEvent.Done d -> {
                                    sendEvent(emitter, "done", "");
                                    emitter.complete();
                                }
```

to:

```java
                                case ChatEvent.Done d -> {
                                    sendEvent(emitter, "done", d.usage() != null ? d.usage() : "");
                                    emitter.complete();
                                }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=AiControllerTest`
Expected: PASS (all tests, including the 2 new ones)

- [ ] **Step 5: Run the full backend test suite**

Run: `./mvnw test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: serialize token usage onto the done SSE event"
```

---

### Task 4: Frontend — parse usage on the `done` event (`api.ts`)

**Files:**
- Modify: `frontend/src/api.ts`

**Interfaces:**
- Produces: `export interface TokenUsage { inputTokens: number; outputTokens: number; cacheReadTokens: number | null; cacheWriteTokens: number | null; }`
- Produces: `StreamCallbacks.onDone?: (usage: TokenUsage) => void`

No unit-test framework exists for frontend TypeScript modules in this project (only Playwright e2e, see Task 6) — this task's behavior is verified end-to-end there. This task is still its own commit because it's an independently reviewable, self-contained change.

- [ ] **Step 1: Add the `TokenUsage` type**

In `frontend/src/api.ts`, add after the `ToolCall` interface:

```ts
export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number | null;
  cacheWriteTokens: number | null;
}
```

- [ ] **Step 2: Extend `StreamCallbacks`**

Change:

```ts
export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
  onError?: (message: string) => void;
}
```

to:

```ts
export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
  onDone?: (usage: TokenUsage) => void;
  onError?: (message: string) => void;
}
```

- [ ] **Step 3: Parse the `done` event payload**

Change:

```ts
    onmessage(ev) {
      if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
      if (ev.event === 'content') callbacks.onContent(ev.data);
      if (ev.event === 'error') callbacks.onError?.(ev.data);
      if (ev.event === 'done') controller.abort();
    },
```

to:

```ts
    onmessage(ev) {
      if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
      if (ev.event === 'content') callbacks.onContent(ev.data);
      if (ev.event === 'error') callbacks.onError?.(ev.data);
      if (ev.event === 'done') {
        if (ev.data) {
          try {
            callbacks.onDone?.(JSON.parse(ev.data));
          } catch {
            // No usage payload (non-LLM directive path) — nothing to report
          }
        }
        controller.abort();
      }
    },
```

- [ ] **Step 4: Type-check**

Run: `cd frontend && npm run build`
Expected: PASS — `tsc -b` reports no type errors (no callers pass `onDone` yet, and it's optional, so existing call sites are unaffected).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat: parse token usage from the done SSE event"
```

---

### Task 5: Frontend — attach usage to the trailing AI message (`useConversation.ts`)

**Files:**
- Modify: `frontend/src/api.ts` (add `usage` to `Message`)
- Modify: `frontend/src/useConversation.ts`

**Interfaces:**
- Consumes: `TokenUsage`, `StreamCallbacks.onDone` from Task 4.
- Produces: `Message.usage?: TokenUsage` — the trailing `role: 'ai'` message in `messages` state gains a `usage` field once the stream's `done` event carries one.

- [ ] **Step 1: Add `usage` to the `Message` interface**

In `frontend/src/api.ts`, change:

```ts
export interface Message {
  role: 'user' | 'ai' | 'meta' | 'compact' | 'clear';
  content: string;
  toolCalls?: ToolCall[];
  sourceLanguage?: string;
}
```

to:

```ts
export interface Message {
  role: 'user' | 'ai' | 'meta' | 'compact' | 'clear';
  content: string;
  toolCalls?: ToolCall[];
  sourceLanguage?: string;
  usage?: TokenUsage;
}
```

- [ ] **Step 2: Add the `onDone` callback in `useConversation.ts`**

In `frontend/src/useConversation.ts`, in the `chatStream(...)` call's callbacks object, add `onDone` immediately after `onContent` (same file/location shown in the existing `onToolCall`/`onContent` block):

```ts
          onDone: (usage) => {
            setMessages(prev => {
              const msgs = [...prev];
              const last = msgs[msgs.length - 1];
              if (last && last.role === 'ai') {
                msgs[msgs.length - 1] = { ...last, usage };
              }
              return msgs;
            });
          },
```

- [ ] **Step 3: Type-check**

Run: `cd frontend && npm run build`
Expected: PASS

- [ ] **Step 4: Manual smoke test**

Run the dev servers (`./build.sh` / `build.cmd` per `docs/dev/build-and-run.md`), open `http://localhost:5177`, send a chat message, and confirm in the browser DevTools Network tab (SSE stream) that the `done` event now carries a JSON body with `inputTokens`/`outputTokens` fields. This confirms the plumbing works end-to-end before writing the Playwright test in Task 6, which asserts on rendered UI rather than network traffic.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api.ts frontend/src/useConversation.ts
git commit -m "feat: attach token usage to the trailing AI message"
```

---

### Task 6: Frontend — render the usage footer + e2e test (`MessageList.tsx`)

**Files:**
- Modify: `frontend/src/MessageList.tsx`
- Create: `frontend/e2e/turn-usage.spec.ts`
- Modify: `frontend/package.json` (add an e2e script for the new spec)

**Interfaces:**
- Consumes: `Message.usage?: TokenUsage` from Task 5.

- [ ] **Step 1: Write the failing e2e test**

Create `frontend/e2e/turn-usage.spec.ts`:

```ts
import { test, expect, type Page } from '@playwright/test';

const sseHeaders = {
  'Content-Type': 'text/event-stream',
  'Cache-Control': 'no-cache',
};

const sseWithUsage = (text: string, usage: object) =>
  `event: content\ndata: ${text}\n\nevent: done\ndata: ${JSON.stringify(usage)}\n\n`;

async function fillAndSend(page: Page, text: string) {
  const input = page.locator('footer input[type="text"]');
  await input.fill(text);
  await input.press('Enter');
}

test.beforeEach(async ({ page }) => {
  await page.route('**/ai/config/user', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ isAdmin: false, grantedToolGroups: ['web'] }),
    }),
  );
  await page.route('**/ai/conversations', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) }),
  );
  await page.route('**/ai/config/directories', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
  await page.route('**/ai/config/mcp-tools**', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  );
});

test.describe('turn token usage display', () => {
  test('shows input/output token counts on the AI message', async ({ page }) => {
    await page.route('**/ai/chat', route =>
      route.fulfill({
        status: 200,
        headers: sseHeaders,
        body: sseWithUsage('Hello!', {
          inputTokens: 1200,
          outputTokens: 340,
          cacheReadTokens: null,
          cacheWriteTokens: null,
        }),
      }),
    );

    await page.goto('/');
    await fillAndSend(page, 'hi');

    await expect(page.getByText('1.2k in')).toBeVisible();
    await expect(page.getByText('340 out')).toBeVisible();
    await expect(page.getByText(/cached/)).not.toBeVisible();
  });

  test('shows cache token count when the provider reports it', async ({ page }) => {
    await page.route('**/ai/chat', route =>
      route.fulfill({
        status: 200,
        headers: sseHeaders,
        body: sseWithUsage('Hello!', {
          inputTokens: 1200,
          outputTokens: 340,
          cacheReadTokens: 812,
          cacheWriteTokens: 0,
        }),
      }),
    );

    await page.goto('/');
    await fillAndSend(page, 'hi');

    await expect(page.getByText('812 cached')).toBeVisible();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx playwright test e2e/turn-usage.spec.ts`
Expected: FAIL — no usage footer is rendered yet, so `getByText('1.2k in')` etc. time out.

- [ ] **Step 3: Add a token-count formatting helper and render the footer**

In `frontend/src/MessageList.tsx`, add this helper near the top of the file (alongside `formatToolArgs`):

```tsx
function formatTokenCount(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}
```

Then, inside the message bubble render block, add the footer immediately after the closing `</div>` of the markdown-content block (i.e. after the `{msg.content && !msg.sourceLanguage && (...)}` block, still inside the outer bubble `<div>`):

```tsx
            {msg.role === 'ai' && msg.usage && (
              <div className="mt-2 pt-2 border-t border-gray-200 text-xs text-gray-400">
                {formatTokenCount(msg.usage.inputTokens)} in · {formatTokenCount(msg.usage.outputTokens)} out
                {msg.usage.cacheReadTokens != null && (
                  <> · {formatTokenCount(msg.usage.cacheReadTokens)} cached</>
                )}
              </div>
            )}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx playwright test e2e/turn-usage.spec.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: Add an npm script for the new spec**

In `frontend/package.json`, add alongside the existing `test:e2e` scripts:

```json
    "test:e2e:turn-usage": "playwright test e2e/turn-usage.spec.ts",
```

- [ ] **Step 6: Run the existing halt-thinking e2e suite to confirm no regression**

Run: `cd frontend && npm run test:e2e`
Expected: PASS — the footer only renders when `msg.usage` is present; `halt-thinking.spec.ts`'s `sseComplete` helper sends an empty `done` payload, so no usage is attached and no footer appears, matching prior behavior.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/MessageList.tsx frontend/e2e/turn-usage.spec.ts frontend/package.json
git commit -m "feat: render per-message token usage footer"
```

---

## Post-Implementation

- Update `docs/dev/api.md` — the `done` SSE event section (currently just lists event types) should note it may carry a JSON `TurnUsage` payload (`inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheWriteTokens`) when available.
- Update `docs/dev/architecture.md`'s `ChatEvent` bullet to mention `Done` now optionally carries `TurnUsage`.
