# `/compact-merge` Design

## Problem

When a user runs `/compact` on a conversation that already has a compact, the LLM tasked with summarising may produce a poor summary if the post-compact messages are noisy or repetitive. This is worsened by duplicate messages (now fixed by requestId dedupe). The new compact becomes the truncation point in `loadHistory`, making the older compact's content permanently inaccessible to future turns.

## Solution

A new `/compact-merge` slash command that finds the last two `compact` rows in a conversation and concatenates them (older first, newer second, separated by `---`) into a single new `compact` row. No LLM is involved — concatenation guarantees both summaries survive verbatim. The merged row becomes the new truncation point, superseding both originals.

---

## Architecture

### Backend

**`ChatOrchestrationService.compactMerge(String externalId, long userId)`**

1. Load all messages for the conversation via `conversationService.getMessages(convDbId)`.
2. Scan backwards to find the last two rows with `type = 'compact'`. Call them `older` and `newer` (in chronological order).
3. If fewer than two compacts exist: throw `IllegalArgumentException("Need at least two compact messages to merge.")`.
4. Build merged text: `older.getMessage() + "\n\n---\n\n" + newer.getMessage()`
5. Persist: `conversationService.addMessage(convDbId, userId, "compact", mergedText)`
6. Return the merged text.

The older two compact rows are **not deleted** — they remain in the DB. Only the most recent compact is used by `loadHistory`, so they become inert history.

**`AiController` — new endpoint**

```
POST /ai/conversations/{externalId}/compact-merge
```

Same auth pattern as `/compact` (user must own the conversation). Returns `{ "summary": "<merged text>" }` on success, `400` if fewer than two compacts exist, `404` if conversation not found.

---

### Frontend

**`api.ts` — new function**

```typescript
export const compactMergeConversation = async (
  conversationId: string,
  token?: string | null,
): Promise<{ summary: string }>
```

Same shape as the existing `compactConversation` function; calls `POST /ai/conversations/{id}/compact-merge`.

**`App.tsx` — new slash command handler**

Alongside the existing `/compact` handler (around line 311), add a `/compact-merge` branch:

- Guard: if no active `conversationId`, show `"Start a conversation before merging compacts."`.
- Call `compactMergeConversation`, append `{ role: 'compact', content: summary }` to `messages`.
- On error, append an `ai` message with the error string.
- Reuses the existing `compact` message bubble rendering — no new UI component needed.

---

## Data Flow

```
User types /compact-merge
  → App.tsx handler
    → POST /ai/conversations/{id}/compact-merge  (with bearer token)
      → AiController.compactMerge()
        → ChatOrchestrationService.compactMerge()
          → conversationService.getMessages()      (all rows)
          → find last two 'compact' rows
          → concatenate older + "---" + newer
          → conversationService.addMessage("compact", merged)
          → return merged text
      → 200 { "summary": merged }
    → setMessages([...prev, { role: 'compact', content: merged }])
```

---

## Error Cases

| Condition | Backend response | Frontend display |
|-----------|-----------------|------------------|
| Fewer than 2 compacts | `400 { "error": "Need at least two compact messages to merge." }` | Error appended as `ai` message |
| Conversation not found / not owned | `404` | Error appended as `ai` message |
| No active conversation | (not sent) | `"Start a conversation before merging compacts."` as `ai` message |

---

## Testing

- `compactMerge_mergesLastTwoCompacts` — inserts two compact rows, calls `compactMerge`, verifies a third `compact` row is added containing both summaries and the `---` separator.
- `compactMerge_onlyOneCompact_throwsIllegalArgumentException` — inserts one compact row, verifies the exception message.
- `compactMerge_noCompacts_throwsIllegalArgumentException` — no compacts in history, same exception.

---

## What is NOT in scope

- Deleting the old compact rows (intentional — append-only message model preserved).
- Merging more than two compacts at once.
- LLM synthesis of the merged text.
- Any change to the existing `/compact` behaviour.
