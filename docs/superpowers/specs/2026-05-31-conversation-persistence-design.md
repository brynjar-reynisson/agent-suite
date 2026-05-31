# Conversation Persistence Design

**Date:** 2026-05-31
**Branch:** feature branch (TBD)
**Status:** Approved

## Overview

Record all agent-suite chat messages to the database — including system prompts, model selections, tool calls, and AI responses — and replay the full conversation history to the LLM on each subsequent turn.

---

## API & Protocol

The `/ai/chat` endpoint gains one new optional request parameter: `conversationId` (a client-generated UUID string).

**If `conversationId` is absent or blank**, the backend behaves as today — stateless, no DB interaction. This preserves backwards compatibility.

**On the first message of a new conversation** (UUID not yet in DB):
1. Insert a `conversation` row: `user_id=1` (Guest), `conversation_name` = first 80 chars of user message, `root_directory` from the request, `external_id` = the client UUID.
2. Insert a `model_change` message with the model name.
3. Insert a `system_prompt` message (empty string if none provided).
4. Load history (returns `[system_prompt]`; `model_change` is excluded).
5. Insert the `user` message.
6. Call LLM with history + current user message.
7. On `Done`, persist `assistant`, `tool_call`, and `tool_result` rows in one transaction.

**On subsequent messages** (UUID already in DB):
1. Look up conversation by `external_id`.
2. If the model differs from the last `model_change` row, insert a new `model_change` message.
3. Load full history (all prior messages except `model_change` rows).
4. Insert the `user` message.
5. Call LLM with history + current user message.
6. On `Done`, persist `assistant`, `tool_call`, and `tool_result` rows in one transaction.

History is always loaded **after** any pre-turn metadata inserts (model_change, system_prompt) but **before** inserting the current user message. This ensures the current user message is passed as the `userMessage` argument and never appears in the history list, preventing duplication.

---

## Database Schema Change

One new migration adds `external_id TEXT UNIQUE NOT NULL` to the `conversation` table. The table is empty in all environments so no backfill is needed.

---

## Message Types

| Type | Sent to LLM? | Stored as |
|---|---|---|
| `system_prompt` | Yes — `SystemMessage` | Plain text |
| `user` | Yes — `UserMessage` | Plain text |
| `assistant` | Yes — `AiMessage` (text) | Plain text |
| `tool_call` | Yes — `AiMessage` (with tool requests) | JSON array: `[{name, arguments}]` — one row per agentic iteration |
| `tool_result` | Yes — `ToolExecutionResultMessage` | JSON array: `[{name, result}]` — one row per agentic iteration, parallel to `tool_call` |
| `model_change` | No | Model name string |

History is loaded ordered by `message_time ASC, message_id ASC`. `model_change` rows are skipped during LLM history reconstruction. The `system_prompt` row maps to `SystemMessage` and supersedes any `systemPrompt` argument passed to the chat service for that turn.

`tool_call` and `tool_result` are stored one row per agentic iteration (not per individual tool call). A single `tool_call` row may represent multiple tool invocations from one LLM response; its JSON array mirrors LangChain4j's `AiMessage` structure.

During history reconstruction, `tool_call` and `tool_result` rows are paired by position: each `tool_call` row is immediately followed in `message_id` order by its corresponding `tool_result` row. The reconstructed `ToolExecutionRequest` IDs within a pair are generated at reconstruction time (not persisted); they only need to be consistent within the reconstructed pair.

---

## New Service: ChatOrchestrationService

Lives in `com.example.agentsuite.service`. Injected into `AiController` in place of direct `ChatService` use.

### Responsibilities

- Resolve or create the conversation record
- Detect and record model changes between turns
- Persist inbound user message before calling LLM
- Load and reconstruct message history
- Invoke `ChatService.chatStreamWithHistory()`
- Buffer tool call/result events during streaming
- Persist assistant response, tool calls, and tool results on `Done` (single transaction)
- Emit SSE events to the controller's emitter throughout

### Flow

```
chatStream(conversationId, model, systemPrompt, userMessage, rootDirectory, tools, emitter)
  ├─ resolveConversation(...)
  │    ├─ new:      INSERT conversation, model_change, system_prompt
  │    └─ existing: INSERT model_change if model changed
  ├─ loadHistory(conversationId) → List<ChatMessage>   ← BEFORE inserting user
  ├─ INSERT user message
  ├─ chatService.chatStreamWithHistory(history, userMessage, event → {
  │    ├─ ToolCall  → emit SSE + buffer
  │    ├─ Content   → emit SSE + buffer
  │    └─ Done      → persist assistant + tool_call + tool_result; emit done
  │  }, tools)
```

---

## ChatService Interface Extension

`ChatService` gains one new method:

```java
void chatStreamWithHistory(List<ChatMessage> history, String userMessage,
                           Consumer<ChatEvent> emitter, Object... tools);
```

The existing `chatStream(systemPrompt, userMessage, emitter, tools)` is kept unchanged and delegates to the new method with an empty history. Both `AbstractLangChain4jChatService` and `DeepSeekService` implement the new method by prepending history messages before the current user message in the message list they send to the LLM. The `system_prompt` message is already first in the history list, so no separate `systemPrompt` string parameter is needed in the new method.

---

## Frontend Changes

- `App.tsx`: generate a UUID once per session with `crypto.randomUUID()`, stored in a `useRef`. Pass it as `conversationId` in every `chatStream` call.
- `api.ts`: add `conversationId?: string` to `ChatRequest`; include it in URL params when present.

No other frontend changes required.

---

## Error Handling

| Scenario | Behaviour |
|---|---|
| `conversationId` absent/blank | Stateless mode — no DB interaction, existing behaviour |
| DB failure on conversation resolve / pre-turn insert | Emit SSE `error` event; do not proceed to LLM |
| DB failure on post-turn persist | Log error; do not surface to user (LLM already responded) |

---

## Testing

- Unit test `ChatOrchestrationService` with mocked `ChatService` and `ConversationService`:
  - First-turn: conversation created, model_change + system_prompt + user rows inserted, history empty
  - Subsequent turn, same model: no model_change inserted, history contains prior messages
  - Subsequent turn, model changed: model_change inserted before user message
  - Tool call turn: tool_call + tool_result rows persisted on Done
- Integration test history reconstruction: insert rows manually, verify correct `List<ChatMessage>` is produced
- Extend existing `ConversationServiceTest` for the `external_id` lookup
