# Tool Indicators Design

**Date:** 2026-06-09  
**Branch:** feature/rbac  
**Status:** Approved

## Overview

Add a visual tool strip to the chat UI that shows which tool groups are available for the current session configuration, with click-to-toggle per tool (visual only in this iteration).

## User Problem

Users cannot see which tools are active for the selected system prompt and root directory combination. The tool set is resolved silently in `handleSend` — there is no feedback until tools actually fire during a conversation.

## Success Criteria

- User can glance at the strip and know exactly which tools are in scope before sending a message
- User can click any tool icon to visually disable/enable it within the session
- Strip updates reactively when system prompt or root directory changes
- Strip is invisible when no tools are active (no prompt selected, no root directory)

## Design

### Placement

A slim horizontal strip inserted between the settings panel and the input footer in `App.tsx`. Separated from the settings panel by a subtle top border. Appears/disappears based on whether any tools are available.

### Visual Style

- **Active tool:** compact icon button with blue background (`bg-blue-100`, `text-blue-700`)
- **Disabled tool:** same button with `filter: grayscale(1)`, reduced opacity, and a diagonal slash overlay (`<span>` absolutely positioned, rotated ~−20°)
- **Tooltip:** native `title` attribute — no extra dependency — shows tool name and the individual tools it includes

### Tool Metadata

```ts
const TOOL_META: Record<string, { icon: string; tooltip: string }> = {
  'unix':      { icon: '📁', tooltip: 'unix: ls · cat · grep' },
  'md-writer': { icon: '✏️', tooltip: 'md-writer: write markdown files' },
  'web':       { icon: '🌐', tooltip: 'web: search · fetch' },
};
```

Unknown tool group names (future additions) fall back to a generic `🔧` icon with the group name as the tooltip.

### State

**`availableTools: string[]`** — derived with `useMemo` from `prompt` + `rootDirectory`:

```ts
const availableTools = useMemo(() => {
  const matched = PROMPT_BANK.find(p => p.name === prompt);
  const toolSet = new Set(matched?.tools ?? []);
  if (rootDirectory) toolSet.add('unix');
  return [...toolSet];
}, [prompt, rootDirectory]);
```

**`disabledTools: Set<string>`** — `useState`, toggled by clicking an icon. Reset to empty set when `availableTools` changes (prompt or directory switch clears manual overrides). A `useEffect` with `availableTools` as its dependency calls `setDisabledTools(new Set())` to enforce this.

**Backend impact:** none in this iteration. The tools sent on `handleSend` are unchanged. The `disabledTools` state is purely visual scaffolding for a future wiring step.

### Component Interface

```ts
// frontend/src/ToolStrip.tsx
interface ToolStripProps {
  availableTools: string[];
  disabledTools: Set<string>;
  onToggle: (tool: string) => void;
}
```

Renders `null` when `availableTools` is empty.

## Files Changed

| File | Change |
|---|---|
| `frontend/src/ToolStrip.tsx` | New component |
| `frontend/src/App.tsx` | Add `availableTools` memo, `disabledTools` state, `toggleTool`, render `<ToolStrip>` |

## Out of Scope

- Wiring `disabledTools` into the tools sent to the backend (next iteration)
- SVG icons (emoji used for now)
- Animated transitions on toggle
- Tool strip in the conversation history view
