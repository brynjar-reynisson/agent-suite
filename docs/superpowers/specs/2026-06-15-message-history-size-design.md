# Message History Size Indicator Design

**Date:** 2026-06-15
**Branch:** feature/show_message_history_size_in_megabytes
**Status:** Approved

## Overview

Display the current message history size in megabytes in the bottom-right corner of the chat message area. Helps users know when to run `/compact` (target: 2–3 MB).

## Scope

Frontend only. No backend changes. One `useMemo` and one JSX element added to `App.tsx`.

## Calculation

Size is computed client-side from the in-memory `messages` array using `useMemo`. Only messages from the most recent `compact` record onward are counted (inclusive of the compact itself, since its summary is sent to the LLM as context). If no compact exists, all messages are counted.

```ts
const historySizeBytes = useMemo(() => {
  const lastCompactIdx = messages.reduce(
    (acc, msg, i) => (msg.role === 'compact' ? i : acc), -1
  );
  const relevant = lastCompactIdx >= 0 ? messages.slice(lastCompactIdx) : messages;
  return new TextEncoder().encode(JSON.stringify(relevant)).length;
}, [messages]);
```

Displayed value: `(historySizeBytes / 1_048_576).toFixed(2) + ' MB'`

The size reflects what the LLM will receive on the next turn. After a `/compact` it resets to near-zero and climbs as the conversation continues.

## UI

- **Position:** Bottom-right of the `<main>` chat area, `sticky` so it stays visible as the user scrolls.
- **Element:** Small semi-transparent pill — `rounded-full px-2 py-0.5 text-xs font-mono bg-gray-900/60`.
- **Hidden when:** `messages.length === 0` (no conversation started).

## Color Thresholds

| Size | Class | Signal |
|---|---|---|
| < 1.5 MB | `text-gray-400` | Fine |
| 1.5 MB – 2.5 MB | `text-amber-400` | Getting large |
| ≥ 2.5 MB | `text-red-400` | Time to `/compact` |

## Testing

Manual: start a conversation, send several large messages, verify the indicator appears and climbs. Run `/compact`, verify it resets. Verify it is hidden before the first message.

No unit tests needed — it is a pure derived display value with no logic branching beyond the threshold colour pick.
