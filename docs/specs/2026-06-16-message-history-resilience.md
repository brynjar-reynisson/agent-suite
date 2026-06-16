---
title: Message History Resilience — Retry, Error Surfacing, MCP Reconnect, Prompt Fix
date: 2026-06-16
status: draft
---

# Message History Resilience

## Summary

Conversation 8 (local dev DB) showed a pattern of dozens of duplicate user messages, each immediately following a failed tool call, with long silent gaps in history. Root-cause analysis traced this to four distinct, addressable bugs:

1. The frontend's SSE client (`fetchEventSource`) silently auto-reconnects and replays the same POST whenever a turn's stream closes — including a clean, intentional close after an error — because no `onclose` handler overrides the library default.
2. When a turn ends in `ChatEvent.Error`, nothing about that turn is persisted — not even tool calls/results that already completed successfully before the failure — so reloading a conversation shows a confusing gap instead of partial progress.
3. A corrupted MCP stdio connection is never detected or replaced; every subsequent call to that server hangs for the full configured timeout (90s) and fails, forever, until the application is restarted.
4. The backend bakes `"Working directory: X"` into the system prompt it persists, the frontend echoes that persisted value back as editable prompt text on reload, and the backend appends another copy on top — producing an ever-growing prompt across reload/continue cycles.

This spec fixes all four, plus the user-facing presentation of backend/tool-call failures.

## User Problem

A user was working in conversation 8 against a `computer-control` MCP server. At some point that server's stdio channel got corrupted (a non-JSON-RPC banner line desynced the parser). Every subsequent tool call to that server then hung for 90 seconds and failed. The frontend, with no visibility into this, silently re-sent the user's last message over and over as its SSE connection kept closing and auto-reconnecting — invisible in the live UI (React state is only updated once per manual send), but visible as dozens of duplicate rows only on reloading the conversation. The user had no indication, live or in history, that anything was wrong.

## Success Criteria

1. A turn that ends in error never causes the frontend to automatically resend the same request.
2. If a duplicate submission reaches the backend anyway (e.g. a non-fetchEventSource retry source), it is detected and not persisted as a second user message.
3. When a turn fails, any tool calls/results and partial assistant text that completed before the failure are persisted — only the failure reason itself is not.
4. The user sees a transient toast/banner when a turn ends in error, while live in the session.
5. A corrupted/dead MCP server connection self-heals: the call that hits it still fails (cost is bounded to that one call), but the connection is reconnected so subsequent calls succeed.
6. The persisted system prompt never grows across reload/continue cycles; it always reflects exactly what the user typed, with the working directory applied fresh per turn rather than baked in.

## Scope

### In Scope

- `frontend/src/api.ts`: add an `onclose` handler to stop auto-retry; handle the `'error'` SSE event type.
- `frontend/src/App.tsx`: generate and pass a per-send `requestId`; render a transient error toast/banner.
- `AiController`: accept `requestId`; stop concatenating `rootDirectory` into the persisted prompt.
- `ChatOrchestrationService`: dedupe by `requestId`; persist partial turn results on `Error`, not just `Done`; apply the working-directory line fresh at LLM-call time instead of persisting it.
- `McpToolBridge`: detect a failed call, mark that server's connection unhealthy, and reconnect it (relaunch the stdio process) before the next call, guarded by a per-server lock.

### Out of Scope

- Resumable SSE streams (`Last-Event-ID`) — turns remain one-shot; a failed turn is resent only by explicit user action, not automatically.
- Persisting the failure *reason* as chat history (toast is live-only, per product decision — see Design Notes).
- Circuit-breaking / cooldown for repeatedly-failing MCP servers — only reconnect-on-failure is in scope; a server that fails every call will simply reconnect every call.
- Active/background health-checking of MCP connections — detection is purely reactive (on call failure).
- Changes to the `compact` truncation mechanism or message schema beyond the additions below.

## Design Notes

### A. Eliminate silent retry + idempotency safety-net

**Frontend root-cause fix.** `fetchEventSource`'s default behavior treats *any* stream close without an `onerror` throw as a signal to reconnect and replay the original request — including a clean close after the backend sends a `done` or `error` event and calls `emitter.complete()`. Since this backend's protocol has no resumability, a finished turn should never be replayed. Add:

```typescript
onclose() {
  throw new Error('Stream closed');
}
```

Throwing inside `onclose` is the library's documented mechanism for opting out of auto-retry entirely.

**Backend safety-net.** Even with the frontend fixed, other retry sources (e.g. browser-level resends, intermediate proxies) could in principle resubmit the same logical send. `App.tsx`'s `handleSend()` generates one `crypto.randomUUID()` per logical send (not per retry) and passes it as a new `requestId` param on `/ai/chat`. `ChatOrchestrationService` tracks the last-seen `requestId` per conversation; if a request arrives with a `requestId` matching the most recent one already processed for that conversation, it's treated as a duplicate and the user-message insert is skipped (the rest of the turn does not re-run either — the request is a no-op, since by definition the original attempt is the one that should produce the response).

### B. Error presentation (toast) + partial-progress persistence

**Toast.** `ChatEvent.Error` already reaches the frontend as an `'error'` SSE event; `api.ts`'s `onmessage` simply doesn't handle it today. Add a case that invokes a new `onError` callback, which `App.tsx` surfaces as a transient, auto-dismissing banner. This is not persisted — it's a live signal only, by design: a reader of history sees that a turn produced no further response, not a recorded reason why.

**Partial-progress persistence.** `ChatOrchestrationService.chatStream`'s event switch currently calls `persistTurnResult` only in the `Done` branch; the `Error` branch forwards the event and saves nothing, discarding any tool calls/results and partial assistant text that completed before the failure. Fix: call `persistTurnResult(convId, userId, toolBatchBuffer, contentBuffer.toString())` in the `Error` branch too, before emitting. This means reloading a conversation that hit a mid-turn failure shows the real work that happened, followed by silence — not a duplicated message with no explanation.

### C. MCP connection resilience (reconnect-on-failure)

Today `McpToolBridge` connects every server once at startup and captures the resulting `McpSyncClient` directly inside each tool's executor closure. A connection that desyncs (e.g. a stray non-JSON-RPC line on stdout) never recovers — every future call to that server hangs for the full `mcp.call-timeout-seconds` (default 90s) and fails, indefinitely, until the whole application restarts.

**Fix:** hold each server's client behind a mutable reference (e.g. `AtomicReference<McpSyncClient>`) instead of capturing it directly in the executor closure. In `callMcpTool`, on catching an exception:

1. Return the error string as today — the in-flight call already paid its timeout; it cannot be un-hung.
2. Mark that server's connection unhealthy and synchronously recreate it, reusing the same `clientFactory`/`defaultCreateClient` logic used at startup (relaunching the stdio process, or reconnecting the HTTP client, for *that server only*).
3. Swap the reference so the next call to that server uses the fresh client.

A per-server lock serializes reconnect attempts: if multiple calls to the same dead server are in flight when the failure is detected, only the first reconnects; the others wait briefly on the lock and then read the already-replaced client rather than each independently relaunching the process.

This bounds the cost of a corrupted connection to exactly one failed call, rather than every call thereafter. If the underlying problem persists (e.g. the external application the MCP server drives is still broken), the next call simply fails and reconnects again — self-correcting once the root cause clears, never worse than today's permanent hang.

### D. System prompt accumulation fix

**Root cause.** `AiController` computes `effectivePrompt = prompt + "\nWorking directory: " + rootDirectory` and `ChatOrchestrationService` persists that concatenated value as the `system_prompt` row. `GET /ai/conversations/{externalId}` returns that same persisted value as `systemPrompt`, and the frontend sets its editable prompt textarea to it on load. If the user continues chatting without manually clearing the box, the contaminated value is sent back as `prompt` on the next turn, and the controller appends *another* copy of the directory line — one more line per reload/continue cycle.

**Fix.** Stop persisting the directory line as part of `system_prompt`:

- `AiController` passes the frontend's `prompt` through to `ChatOrchestrationService.chatStream` unchanged (no concatenation).
- `ChatOrchestrationService.resolveConversation` persists that pure value — `system_prompt` rows, and therefore `detail.systemPrompt`, never contain a directory line and so can never accumulate one.
- The directory line is applied fresh, in memory only, at the point `loadHistory` builds the `HistoryMessage.SystemPrompt` actually sent to the LLM for the current turn, using the current request's `rootDirectory` — never a previously-saved value.

The LLM still sees the working directory on every turn; the persisted/displayed prompt just never contains it, so the round-trip that caused duplication is structurally impossible.

## Affected Files

| File | Change |
|------|--------|
| `frontend/src/api.ts` | Add `onclose` (throws, stops auto-retry); handle `'error'` SSE event → `onError` callback; add `requestId` to `ChatRequest` |
| `frontend/src/App.tsx` | Generate per-send `requestId`; add transient error toast/banner state + rendering |
| `src/main/java/.../controller/AiController.java` | Accept `requestId` param, pass through; stop building `effectivePrompt` (pass `prompt` raw) |
| `src/main/java/.../service/ChatOrchestrationService.java` | Track last-seen `requestId` per conversation and no-op duplicates; persist partial results on `Error`; apply working-directory line fresh in `loadHistory` instead of persisting it |
| `src/main/java/.../tools/McpToolBridge.java` | Hold clients behind a mutable per-server reference; reconnect on call failure with per-server locking |

## Testing

- Unit test `ChatOrchestrationService`: a turn ending in `Error` after N successful tool calls persists those N tool_call/tool_result pairs and any partial assistant text, but no error message.
- Unit test `ChatOrchestrationService`: a repeated `requestId` for the same conversation does not insert a second `user` row.
- Unit test the working-directory injection: `system_prompt` row content never contains `"Working directory:"` regardless of `rootDirectory`; the in-memory history passed to the LLM does.
- Unit test `McpToolBridge`: a client that throws on `callTool` gets replaced (verify the factory is invoked again) and the next call uses the new client; concurrent failures on the same server only trigger one reconnect.
- Manual test (frontend): force a backend error mid-turn (e.g. stop the backend process during a tool call) and confirm the browser does not resend the request, and a toast appears.
