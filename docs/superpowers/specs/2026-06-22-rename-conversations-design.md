# Rename Conversations Design

**Date:** 2026-06-22  
**Branch:** `feature/rename_conversations`  
**Status:** Approved

## Goal

Allow users to rename conversations inline within the conversation list panel. The custom name is optional — if not set, the auto-generated name (first 80 chars of the first user message) continues to be shown.

## Database

One migration adds a nullable column to the `conversation` table:

```sql
-- supabase/migrations/20260622000000_add_custom_name_to_conversation.sql
ALTER TABLE conversation ADD COLUMN custom_name TEXT;
```

- `custom_name` is `TEXT NULL` — no default, no constraint.
- `conversation_name` (the auto-generated name) is never modified after initial insert.
- Display name resolution everywhere: `custom_name` when non-null, otherwise `conversation_name`.
- Clearing to empty string via the API sets `custom_name = NULL`, reverting to the auto-name.

## Backend

### jOOQ codegen

Running `./build.cmd` after the migration regenerates `Conversation.java` and `ConversationRecord.java` with the new `CUSTOM_NAME` field.

### Repository

New method on `ConversationRepository`:

```java
public void updateCustomName(long conversationId, @Nullable String customName) {
    dsl.update(CONVERSATION)
       .set(CONVERSATION.CUSTOM_NAME, customName)
       .where(CONVERSATION.CONVERSATION_ID.eq(conversationId))
       .execute();
}
```

### Service

New method on `ConversationService`:

```java
@Transactional
public void renameConversation(String externalId, long userId, @Nullable String customName) {
    ConversationRecord conv = conversationRepository.findByExternalId(externalId)
            .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));
    if (!conv.getUserId().equals(userId)) {
        throw new NoSuchElementException("Conversation not found: " + externalId);
    }
    conversationRepository.updateCustomName(conv.getConversationId(), customName);
}
```

Ownership enforcement mirrors the pattern already used in `getConversationDetail`.

### DTOs

Both `ConversationSummaryDto` and `ConversationDetailDto` gain a `customName` field (`String`, nullable). The existing `name` field continues to carry the auto-generated name (`conv.getConversationName()`); `customName` maps from `conv.getCustomName()` (null if unset).

```java
// ConversationSummaryDto
public record ConversationSummaryDto(
        String externalId,
        String name,
        String customName,   // new — nullable; null means no custom name set
        String createTime,
        String initialModel,
        String systemPrompt
) {}
```

`ConversationDetailDto` gains the same `customName` field in the same position after `name`.

### Endpoint

New endpoint in `AiController`:

```
PATCH /ai/conversations/{externalId}
  ?customName=<string>   (empty string clears the custom name)
  Auth required; caller must own the conversation (404 otherwise).
  Returns 200 OK (no body) on success.
```

- Form param, consistent with the rest of the controller.
- Empty string → `null` in the service call (clears the custom name).

## Frontend

### `api.ts`

- `ConversationSummary` interface gains `customName: string | null`.
- New function:

```typescript
export const renameConversation = async (
  externalId: string,
  customName: string,
  token?: string | null,
): Promise<void> => {
  const res = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(externalId)}`,
    {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: new URLSearchParams({ customName }),
    },
  );
  if (!res.ok) throw new Error(`Rename failed (${res.status})`);
};
```

### `ConversationPanel.tsx`

**New state:**
- `editingId: string | null` — which conversation's row is in edit mode
- `editValue: string` — current input value while editing

**Pencil icon:** Always visible next to each conversation name. Clicking it sets `editingId` and pre-fills `editValue` with `conv.customName ?? conv.name`.

**Edit mode row:** When `editingId === conv.externalId`, the name area renders an `<input>` instead of the display span. The outer row `<button>` is disabled while that row is in edit mode (prevents accidental conversation load).

**Keyboard handling on the input:**
- `Enter` or `blur` → save: call `renameConversation`, update `conversations` state optimistically (set `customName` to the trimmed value, or `null` if empty), clear `editingId`. On API error, revert and show error via the existing `selectError` state.
- `Escape` → cancel: clear `editingId` without calling the API.

**Display name helper (inline):** `conv.customName ?? conv.name`

**No new files.** All changes are confined to the two existing files (`api.ts`, `ConversationPanel.tsx`) plus the new migration and backend changes.

## Error Handling

- API failure on save: revert optimistic update, surface error in the existing `selectError` banner already present in the panel.
- 404 (conversation not found / not owned): treated as a generic save failure.

## Out of Scope

- Rename from anywhere other than the conversation list panel.
- Showing the custom name in the active chat header (there is no visible conversation title in the chat view).
- Bulk rename or rename via keyboard shortcut.
