# Turn Token Usage Display — Design

**Date:** 2026-07-12

## Summary

Display per-turn LLM token usage (input, output, and — where the provider reports it — prompt-cache read/write tokens) as a subtle footer on each assistant message. Phase 1 only: capture, transport, and render. No database changes — the `TurnUsage` shape is deliberately DB-column-friendly so persisting it to the `message` table is a straightforward follow-up later.

## Background / Investigation Notes

- `chatStreamWithHistory` in `AbstractLangChain4jChatService` is not token-level streaming — it makes one synchronous `AssistantService.chat(...)` call via LangChain4j's `AiServices`, which internally handles the whole tool-calling loop and returns a single `String`. SSE "streaming" here refers to the `tool_call`/`content`/`done` event sequence, not incremental model output.
- Changing `AssistantService.chat(...)`'s return type from `String` to LangChain4j's `Result<String>` exposes `.tokenUsage()` (aggregated across the tool-calling loop) and `.intermediateResponses()` (per-iteration `ChatResponse`s) without restructuring the loop itself.
- Anthropic's LangChain4j integration exposes prompt-cache stats via `AnthropicTokenUsage extends TokenUsage`, adding `cacheCreationInputTokens()` / `cacheReadInputTokens()`. Google (Gemini) and DeepSeek (which goes through LangChain4j's `OpenAiChatModel`, not a hand-rolled client — `docs/dev/architecture.md` is stale on this point) only expose the base `TokenUsage` (input/output/total), no cache breakdown.
- `ChatEvent.Done` currently carries no payload and has ~30 call sites across `ChatOrchestrationService` (non-LLM directive paths: `/clear`, `!stop`, `!erase-last`, `model_change`, `compact`) and tests. It's a record, so adding a `usage` field with a no-arg overload defaulting to `null` keeps every existing call site compiling unchanged.
- `ChatOrchestrationService.chatStream` already forwards the `ChatEvent.Done` instance it receives from the `ChatService` straight to its own emitter (`emitter.accept(event)`), so no change is needed there — the usage payload rides through for free.

## Data Shape

```java
public record TurnUsage(int inputTokens, int outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens) {}
```

`cacheReadTokens`/`cacheWriteTokens` are boxed `Integer` and `null` when the provider doesn't report cache stats (Google, DeepSeek). The frontend must treat `null` as "not available," not zero — a `0` means the provider reported cache activity of zero (e.g. first turn, nothing cached yet), which is a meaningfully different state from "this provider doesn't do caching."

Raw counts only in this phase — no cost estimation, no pricing table.

## Backend — Capture Point

`AbstractLangChain4jChatService`:

1. Change the private `AssistantService` interface method from `String chat(...)` to `Result<String> chat(...)` (import `dev.langchain4j.service.Result`).
2. In `chatStreamWithHistory`, after `Result<String> result = svc.chat(userMessage)`:
   - `String response = result.content();`
   - Build a `TurnUsage` from `result.tokenUsage()`; if the returned `TokenUsage` is an `AnthropicTokenUsage`, populate `cacheReadTokens`/`cacheWriteTokens` from it, otherwise leave them `null`.
   - `emitter.accept(new ChatEvent.Content(response));`
   - `emitter.accept(new ChatEvent.Done(usage));`
3. The existing `catch (Exception e)` branch keeps emitting `new ChatEvent.Done()` (usage `null`) — no usage data when the call itself failed.
4. `ChatEvent.Done` gains the field plus a compatibility constructor:
   ```java
   record Done(TurnUsage usage) implements ChatEvent {
       public Done() { this(null); }
   }
   ```
5. `ChatService.chat(...)` (the separate synchronous method backing `/ai/tools`, which never emits `ChatEvent.Done`) is unaffected by this change beyond needing `.content()` instead of the bare string where its `tools.length > 0` branch calls `svc.chat(userMessage)`.

Exact aggregation behavior of `Result.tokenUsage()` across a multi-iteration tool-calling loop (sum vs. final-call-only) should be confirmed empirically during implementation (e.g. a temporary log line comparing `intermediateResponses().size()` against reported totals on a multi-tool-call turn); if it turns out not to aggregate as expected, fall back to manually summing `TokenUsage` across `result.intermediateResponses()` plus `result.finalResponse()`.

## Backend — Transport

`AiController`'s SSE switch changes:

```java
case ChatEvent.Done d -> {
    sendEvent(emitter, "done", d.usage() != null ? d.usage() : "");
    emitter.complete();
}
```

Jackson serializes the `TurnUsage` record to JSON the same way `tool_call`'s `Map.of(...)` payload is already serialized today. When `usage` is `null`, the event keeps today's empty-string payload — no behavior change for directive paths that never carry usage.

## Frontend

**`api.ts`**
- Add a `TokenUsage` type mirroring the backend record (`inputTokens`, `outputTokens`, `cacheReadTokens: number | null`, `cacheWriteTokens: number | null`).
- `chatStream()`'s `onmessage` handler: on the `done` event, attempt to parse `ev.data` as JSON; if it parses to a non-empty object, call a new `onDone?.(usage: TokenUsage)` callback before `controller.abort()`. Empty-string payloads (directive paths) skip the callback.

**`Message` interface**
- Add `usage?: TokenUsage`.

**`useConversation.ts`**
- Add `onDone: (usage) => { /* attach usage to the trailing 'ai' message in state */ }`, following the same last-message-mutation pattern as the existing `onToolCall`/`onContent` callbacks.

**`MessageList.tsx`**
- Render a subtle gray-text footer under the AI bubble's content, visually the same tier as the existing tool-call header block, e.g.:
  - `1.2k in · 340 out` always, when `usage` is present.
  - `· 812 cached` appended only when `cacheReadTokens` is non-null (omit entirely for providers that don't report it — no "N/A" clutter).
  - Token counts abbreviate at the thousands (`1.2k`) to stay compact; exact counts are fine below 1000.

## Testing

- Existing `ChatOrchestrationServiceTest` / `AiControllerTest` call sites using `new ChatEvent.Done()` continue to compile and pass unchanged (usage defaults to `null`).
- Add a focused test on `AbstractLangChain4jChatService` (or its existing test class) asserting that a successful `chatStreamWithHistory` call emits a `Done` event with non-null `usage` containing plausible input/output counts, using a fake/mock `ChatModel` if the existing test harness supports one — otherwise mock at the `Result`/`TokenUsage` boundary.
- Add a frontend test (or extend an existing Playwright/unit test) confirming the token-usage footer renders when `usage` is present on a message and is absent when it isn't.

## Out of Scope

- Persisting usage to the `message` table or any other storage (Phase 2 — deferred; the `TurnUsage` field names are chosen to map cleanly onto future `input_tokens`/`output_tokens`/`cache_read_tokens`/`cache_write_tokens` columns).
- Estimated dollar cost / pricing tables.
- Per-tool-iteration usage display (only the final aggregated `Done` event carries usage).
- Fixing the stale "DeepSeek is a hand-rolled REST client" claim in `docs/dev/architecture.md` (noted here for awareness; not part of this feature's diff).
