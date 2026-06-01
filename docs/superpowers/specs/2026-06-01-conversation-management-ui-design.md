# Conversation Management UI Design

**Date:** 2026-06-01
**Status:** Approved

## Overview

Add conversation management to the AgentSuite Chat frontend: a `+` button to start a new conversation and a history button (`☰`) to browse and load past conversations. Loading a past conversation restores the full message history, the model, and the system prompt. Future work will replace the full history display with a summary + expand option.

---

## Backend API

Two new REST endpoints added to `AiController`. Both use Guest user (`user_id = 1`) for now; a user ID parameter will be added when multi-user support is introduced.

### `GET /ai/conversations`

Returns all conversations for the Guest user, ordered by `create_time DESC`.

**Response:**
```json
[
  {
    "externalId": "f5987f09-...",
    "name": "Project Hail Mary",
    "createTime": "2026-05-31T22:55:07Z",
    "lastModel": "deepseek-v4-pro",
    "systemPrompt": "You have access to search and fetch..."
  }
]
```

`lastModel` is the value of the first `model_change` message row for the conversation (the model that started it). `systemPrompt` is the value of the `system_prompt` message row. Both are fixed at conversation creation — mid-conversation model changes are not reflected here.

**Error cases:**
- Guest user has no conversations → `[]`

### `GET /ai/conversations/{externalId}`

Returns the full conversation with messages reconstructed for the frontend.

**Response:**
```json
{
  "externalId": "f5987f09-...",
  "name": "Project Hail Mary",
  "lastModel": "deepseek-v4-pro",
  "systemPrompt": "You have access to search and fetch...",
  "messages": [
    { "role": "user", "content": "Tell me about the book and movie Project Hail Mary." },
    {
      "role": "ai",
      "content": "Here's a comprehensive overview...",
      "toolCalls": [
        { "name": "webSearch", "arguments": "{\"query\": \"Project Hail Mary...\"}" }
      ]
    }
  ]
}
```

**Message reconstruction rules** (applied in `message_time ASC, message_id ASC` order):

| DB type | Action |
|---|---|
| `model_change` | Extracted for `lastModel` metadata; not added to `messages` |
| `system_prompt` | Extracted for `systemPrompt` metadata; not added to `messages` |
| `user` | Appended as `{role: "user", content}` |
| `tool_call` | JSON parsed; each entry accumulated in a buffer for the next `assistant` row |
| `tool_result` | Skipped — not shown in the UI |
| `assistant` | Appended as `{role: "ai", content, toolCalls: [buffered tool calls]}` then buffer cleared |

**Error cases:**
- Unknown `externalId` → HTTP 404

### Backend implementation

- `ConversationService` gets a new `getConversationDetail(String externalId)` method that loads the conversation record and its messages, applies the reconstruction rules above, and returns a `ConversationDetailDto`.
- `AiController` gets two new `@GetMapping` methods for the endpoints.
- New response DTOs: `ConversationSummaryDto` and `ConversationDetailDto` (plain Java records).

---

## Frontend

### Header layout

Current: `[AgentSuite Chat] ............. [model ▾]`

New: `[AgentSuite Chat] ............. [model ▾] [+] [☰]`

The `+` and `☰` buttons sit to the right of the model selector. Both are disabled while `loading` is true (streaming in progress).

### New conversation (`+`)

Calls `startNewConversation()`:
1. Assigns `crypto.randomUUID()` to `conversationId.current` (ref, no re-render)
2. Clears `messages` → `[]`
3. Resets `model` → `'deepseek-v4-pro'`
4. Clears `prompt` → `''`

No confirmation dialog — messages are already persisted to the database.

### History panel (`☰`) — `ConversationPanel.tsx`

A new component extracted to `frontend/src/ConversationPanel.tsx`.

**Props:** `isOpen: boolean`, `onClose: () => void`, `onSelect: (conv: ConversationSummary) => void`

**Behaviour:**
- When `isOpen` becomes true: fetches `GET /ai/conversations`
- Renders a fixed-position panel on the right side of the screen (`w-72`), sliding in over the chat
- Semi-transparent backdrop behind the panel; clicking it calls `onClose`
- Esc key calls `onClose`
- Shows a loading state while fetching
- On fetch failure: shows "Failed to load conversations" inline

**List item format:**
```
Tell me about Project Hail Mary            31 May
```
- Conversation name (bold, top-left) — this is already the first user message truncated to 80 chars, making it the best identifier
- Date right-aligned (formatted as day + abbreviated month, e.g. "31 May")
- No second line — clean and compact

**Loading a conversation:**
1. Fetch `GET /ai/conversations/{externalId}`
2. On success:
   - Set `conversationId.current` to the loaded `externalId`
   - Set `messages` from the response
   - Set `model` from `lastModel`
   - Set `prompt` to the raw `systemPrompt` text (displayed in the system prompt combobox as free-form text; if it matches a PROMPT_BANK entry's text, the user sees the preset name on next interaction)
   - Close the panel
3. On failure: show error inline in the panel, do not close

### `api.ts` additions

```ts
export interface ConversationSummary {
  externalId: string;
  name: string;
  createTime: string;
  lastModel: string;
  systemPrompt: string;
}

export interface ConversationDetail extends ConversationSummary {
  messages: Message[];
}

export const getConversations = (): Promise<ConversationSummary[]>
export const getConversationDetail = (externalId: string): Promise<ConversationDetail>
```

`Message` is the existing `{ role: 'user' | 'ai', content: string, toolCalls?: ToolCall[] }` interface already defined in `App.tsx`. It will be moved to `api.ts` so `ConversationPanel` can import it.

---

## Known Limitations (v1)

- **System prompt restoration**: the raw prompt text is restored, not the PROMPT_BANK preset name. If the user used a preset, the combobox will show the full text rather than the preset name.
- **Mid-conversation model changes**: `lastModel` reflects the model at conversation start, not the most recent model change.
- **Full history display**: all messages are shown on load. Summarisation with expand/collapse is deferred to a future iteration.
- **Single user**: Guest user (`user_id = 1`) is hardcoded. Multi-user support will add an optional user ID parameter to both endpoints.

---

## Testing

**Backend:**
- `GET /ai/conversations` returns correct shape and order for Guest user
- `GET /ai/conversations/{externalId}` reconstructs messages correctly: tool_call rows grouped onto assistant turns, model_change and system_prompt excluded from message list, tool_result skipped
- Unknown `externalId` returns 404

**Frontend:**
- Manual verification: open history panel, load a conversation, verify messages display correctly, model and prompt are restored, new conversation resets state
