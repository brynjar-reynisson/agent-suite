# Conversation Name in Header Design

**Date:** 2026-06-22  
**Status:** Approved

## Goal

Display the active conversation's display name (`customName ?? name`) to the right of the "AgentSuite Chat" banner in the page header. Show nothing when no conversation is loaded.

## State

`useConversation.ts` gains:

- `activeConvDisplayName: string | null` — initialized to `null`
- Set to `conv.customName ?? conv.name` inside `loadConversation` after the detail is fetched
- Cleared to `null` inside `resetConversation`
- `updateActiveConvDisplayName(externalId: string, displayName: string): void` — compares `externalId` against the hook's internal `conversationId`; updates `activeConvDisplayName` if they match
- Both `activeConvDisplayName` and `updateActiveConvDisplayName` are added to the hook's return value

No state is added to `App.tsx`. Logic stays in the hook that already owns conversation lifecycle.

## Header

`App.tsx` header changes — wrap the `<h1>` and the optional name in a left-side flex group:

```tsx
<div className="flex items-baseline gap-3 min-w-0">
  <h1 className="text-xl font-bold text-gray-800 flex-shrink-0">AgentSuite Chat</h1>
  {activeConvDisplayName && (
    <span className="text-sm text-gray-400 truncate">{activeConvDisplayName}</span>
  )}
</div>
```

- `flex-shrink-0` on `<h1>` prevents it from ever compressing.
- `truncate` (CSS `text-overflow: ellipsis`) on the span handles long names.
- Nothing renders when `activeConvDisplayName` is `null`.

`App.tsx` also passes `onRename={updateActiveConvDisplayName}` to `ConversationPanel`.

## Rename Sync

`ConversationPanel.tsx` gains an optional `onRename?: (externalId: string, displayName: string) => void` prop. After a successful `saveEdit`, it calls:

```ts
onRename?.(conv.externalId, trimmedValue || conv.name);
```

This keeps the header in sync when the user renames the currently-loaded conversation from the panel.

## Files Changed

| File | Change |
|------|--------|
| `frontend/src/useConversation.ts` | Add `activeConvDisplayName`, `updateActiveConvDisplayName`, wire into `loadConversation` / `resetConversation` |
| `frontend/src/App.tsx` | Destructure new hook values; update header JSX; pass `onRename` to ConversationPanel |
| `frontend/src/ConversationPanel.tsx` | Add optional `onRename` prop; call it after successful save |

No new files.

## Out of Scope

- Editing the name from the header (rename is panel-only)
- Showing the name on the new-conversation state
- Any truncation length cap beyond CSS `truncate`
