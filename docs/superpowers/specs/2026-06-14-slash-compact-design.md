# /compact Slash Command Design

**Date:** 2026-06-14
**Branch:** feature/slash_compact
**Status:** Approved

## Overview

Add a `/compact` slash command that summarises the current conversation via an LLM call, stores the result as a new `compact` message type, and from that point forward sends only `[system_prompt, compact_summary, …messages_since_compact…, new_user_message]` to the LLM instead of the full history. When no `compact` message exists, behaviour is unchanged.

## User Flow

1. User is mid-conversation and types `/compact`.
2. Frontend intercepts the input (before the chat path), displays `{ role: 'user', content: '/compact' }` in the message list, and `POST`s to `/ai/conversations/{externalId}/compact` with the Supabase bearer token.
3. Backend loads the full message history, formats a transcript, calls the LLM synchronously (no tools), stores the summary as a `compact` message, and returns `{ "summary": "…" }`.
4. Frontend pushes `{ role: 'compact', content: summary }` into the message list and renders it as a distinct visual block.
5. On the next chat turn, `loadHistory` finds the most recent `compact` record, drops everything before it, and emits the compact as a `HistoryMessage.User` with a "Previous conversation summary:" prefix — so all downstream `ChatService` implementations are unchanged.
6. Messages after the compact accumulate normally (compact is a truncation checkpoint, not a permanent reset).

## Backend

### `POST /ai/conversations/{externalId}/compact`

New mapping in `AiController` (alongside the existing `GET /ai/conversations/{externalId}`).

- Extract `userId` from `UserResolverFilter.ATTR_USER_ID`.
- Look up the conversation via `conversationService.findByExternalId(externalId)`.
- If not found, return `404`.
- If `conversation.userId != userId`, return `404` (same behaviour as `getConversationDetail` — never confirm existence to a non-owner).
- Load all messages via `conversationService.getMessages(convId)`.
- If no user or assistant messages exist yet, return `400 { "error": "Nothing to compact." }`.
- Determine the model: `conversationService.findLastModelChange(convId)` — default to `"deepseek-v4-pro"` if absent.
- Build the summarisation prompt (see below), call `modelRegistry.get(model).chat(SUMMARY_SYSTEM_PROMPT, transcript, new Object[0])` synchronously.
- Store result: `conversationService.addMessage(convId, userId, "compact", summary)`.
- Return `200 { "summary": "…" }`.

**Summarisation system prompt (constant):**
```
Summarise the conversation below concisely. Preserve the key context, decisions, facts, and any ongoing tasks. Write in the third person and omit pleasantries.
```

**Transcript format** — iterate message records in order, skipping `system_prompt` and `model_change` records only. Include `compact` records as `[Summary]: …` so a re-compact captures prior summaries alongside subsequent exchanges:
```
[User]: …
[Assistant]: …
[Tool call: ls ./src]
[Tool result: …][Summary]: … (prior compact content)
```

### `ChatOrchestrationService.loadHistory` update

After loading all `MessageRecord`s, find the index of the **most recent** record with `type = "compact"`. If found:

1. Emit `HistoryMessage.SystemPrompt` from the last `system_prompt` in the **full** list (unchanged — system prompt is never truncated).
2. Emit the compact record as `HistoryMessage.User("Previous conversation summary:\n\n" + content)`.
3. Iterate only the records **after** the compact index, emitting `user`, `assistant`, `tool_call`, `tool_result` as before.

If no `compact` record exists, `loadHistory` behaves exactly as it does today.

No changes to `HistoryMessage` sealed interface or any `ChatService` implementation.

### `ChatService.chat()` (synchronous, no tools)

All providers already implement `chat(systemPrompt, userMessage, tools)` — the compact path calls it with an empty tools array. No new interface method needed.

## Frontend

### `Message` type

Add `'compact'` to the union: `role: 'user' | 'ai' | 'meta' | 'compact'`.

### `handleSubmit` interceptor

After the `!`-prefix block and before the regular `chatStream` call:

```ts
if (message === '/compact') {
  if (!conversationId.current) {
    setMessages(prev => [...prev, { role: 'ai', content: 'Start a conversation before compacting.' }]);
    setLoading(false);
    return;
  }
  try {
    const token = await getAccessToken();
    const { summary } = await compactConversation(conversationId.current, token);
    setMessages(prev => [...prev, { role: 'compact', content: summary }]);
  } catch (err: any) {
    setMessages(prev => [...prev, { role: 'ai', content: `Error: ${err.message}` }]);
  } finally {
    setLoading(false);
  }
  return;
}
```

### `api.ts`

```ts
export async function compactConversation(conversationId: string, token: string): Promise<{ summary: string }> {
  const res = await fetch(`/ai/conversations/${conversationId}/compact`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error ?? `Compact failed (${res.status})`);
  }
  return res.json();
}
```

### Compact message rendering

In the message list render, add a branch for `msg.role === 'compact'`:

```tsx
if (msg.role === 'compact') {
  return (
    <div key={i} className="rounded border border-gray-700 bg-gray-800/50 px-4 py-3 text-sm text-gray-300">
      <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-gray-500">Conversation compacted</p>
      <p className="whitespace-pre-wrap">{msg.content}</p>
    </div>
  );
}
```

### Conversation history replay

`getConversationDetail` currently maps message types into `ConversationDetailDto.MessageDto`. Add a `"compact"` case that maps to `role: 'compact'` so that when a user reopens a past conversation the compact block renders correctly in the UI.

## Error Handling

| Scenario | Backend | Frontend |
|---|---|---|
| Conversation not found | 404 | Error message in chat |
| Caller does not own conversation | 404 | Error message in chat |
| No substantive messages yet | 400 `"Nothing to compact."` | Error message in chat |
| Unknown model | 500 | Error message in chat |
| LLM call fails | 500 | Error message in chat |
| No conversation started (stateless) | — | Client-side guard before any request |

## Testing

### `ChatOrchestrationServiceTest`

Extract `loadHistory` to package-private visibility and test directly with a mocked `ConversationService`:

- No `compact` record → full history returned unchanged.
- One `compact` record in the middle → messages before it dropped; compact emitted as `HistoryMessage.User`; messages after it included.
- Multiple `compact` records → only the most recent is the truncation point; everything before it (including older compacts) is dropped.
- `compact` record is the last message (nothing after it) → only `[SystemPrompt, User(compact)]` returned.

### `AiControllerTest` (compact endpoint)

Using `standaloneSetup` or `@WebMvcTest`:

- `POST /ai/conversations/{id}/compact` with valid owner → 200 and `summary` field in body.
- Non-existent conversation → 404.
- Wrong owner → 404.
- No substantive messages → 400.

### Frontend

Manual test: start a conversation, type `/compact`, confirm the compact block appears, send another message, confirm the LLM responds in context of the summary.

## Out of Scope

- Re-compacting after a prior compact (the new compact will summarise the compact + subsequent messages — this is correct naturally).
- Visual diff of what was dropped.
- Admin-only restriction (any authenticated user who owns a conversation may compact it).
- Compact triggered automatically by token count.
