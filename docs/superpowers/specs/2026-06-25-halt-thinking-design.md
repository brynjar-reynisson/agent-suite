# halt_thinking — Design

**Date:** 2026-06-25

## Summary

Three related behaviors for interrupting an in-flight agent response:

1. **halt_thinking** — Sending a new message while the agent is responding aborts the current stream and immediately starts a new one with the new message.
2. **`!stop`** — Aborts the current stream without sending a new message; partial AI content stays visible.
3. **`!erase-last`** — Soft-deletes the last user+AI turn from the DB and removes it from local state.

## Conversation History Semantics

When a stream is interrupted by a new message (halt_thinking):
- The original user message is **kept** in history.
- The partial AI response is **discarded** from local state (the trailing AI message is popped).
- The new user message is appended and a new stream starts.
- From the LLM's perspective on the next turn: `[..., user: original, user: new, AI: ...]`.

`!erase-last` gives manual control to remove the previous stopped turn entirely when it should not be part of history at all.

## Frontend — Stream Control (`api.ts`, `useConversation.ts`)

### `api.ts`

`chatStream()` gains an optional fourth parameter `abortController?: AbortController`. If provided, it is used as the SSE signal; otherwise a new one is created internally. Backward-compatible — no callers change unless they want to abort.

A new API function `eraseLastTurn(conversationId: string, token?: string | null): Promise<void>` calls `POST /ai/conversations/{conversationId}/erase-last`.

### `useConversation.ts`

Two new refs:

- `abortRef = useRef<AbortController | null>(null)` — holds the controller for the active stream.
- `streamGenRef = useRef(0)` — generation counter to prevent the old stream's `finally` from calling `setLoading(false)` after a new stream has already started.

**`handleSend` changes:**

| Input | `loading = false` | `loading = true` |
|-------|-------------------|------------------|
| Normal message | Start stream (existing behaviour) | Abort current stream, pop trailing AI message from state, start new stream |
| `!stop` | No-op (nothing to stop) | Abort current stream; partial AI content stays visible |
| `!erase-last` | Erase last turn from DB + local state | Toast: "Use !stop first" |
| Other `!`/`/` commands | Existing behaviour | Return early (unchanged) |

**Stream startup sequence (normal + interrupt):**

```
abortRef.current?.abort()           // no-op if null
streamGenRef.current++
const gen = streamGenRef.current
const controller = new AbortController()
abortRef.current = controller
setLoading(true)

try {
  await chatStream(..., token, controller)
} catch (err) {
  if (gen === streamGenRef.current) { /* update error state */ }
} finally {
  if (gen === streamGenRef.current) {
    setLoading(false)
    abortRef.current = null
  }
}
```

**`!erase-last` sequence:**

1. Call `eraseLastTurn(conversationId.current, token)`.
2. On success, walk `messages` state backwards: remove the trailing AI message (if present), then the user message before it.
3. On API error, show toast.

**`!stop` sequence:**

1. Call `abortRef.current?.abort()`.
2. The stream's `finally` block handles `setLoading(false)` — no extra state change needed.

## Backend — DB Migration

```sql
ALTER TABLE message ADD COLUMN erased BOOLEAN NOT NULL DEFAULT FALSE;
```

All existing rows default to `false`. jOOQ codegen regenerates `MessageRecord` (gains `erased` field) and `Message` table DSL (gains `ERASED` column constant).

## Backend — Erased Filtering (`MessageRepository`)

The query that loads messages for a conversation gains a `AND erased = FALSE` condition (jOOQ: `.and(MESSAGE.ERASED.isFalse())`). Both consumers of this query — `ChatOrchestrationService.loadHistory()` (LLM history replay) and `ConversationService.getConversationDetail()` (frontend display) — automatically exclude erased rows with no further changes.

## Backend — `erase-last` Endpoint

**`POST /ai/conversations/{externalId}/erase-last`** in `AiController`.

Delegates to `ConversationService.eraseLastTurn(externalId, userId)`:

1. Find the last message row where `role = 'user'` for this conversation.
2. Collect its `id` plus all message `id`s with a higher `id` in the same conversation (the AI response, any tool calls, tool results that followed).
3. `UPDATE message SET erased = TRUE WHERE id IN (...)`.

If no user message exists, throws `IllegalArgumentException` → endpoint returns 400.

Returns 200 with empty body on success.

## Error Cases

| Scenario | Behaviour |
|----------|-----------|
| `!erase-last` while `loading` | Toast: "Use !stop first before erasing" |
| `!erase-last` on empty conversation | Toast: "Nothing to erase" |
| `!stop` while not loading | Silent no-op |
| `erase-last` API call fails | Toast with error message |
| Interrupt (new message) while `loading` | Old stream aborted; partial AI dropped; new stream starts |

## Out of Scope

- Server-side LLM/tool-call cancellation (the backend stops naturally when it tries to emit to the closed SSE connection and gets `IOException`).
- A visual Stop button (can be added later independently).
- Undelete / undo for `!erase-last`.
