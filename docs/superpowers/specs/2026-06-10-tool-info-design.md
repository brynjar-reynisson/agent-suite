# Tool Info Modal — Design Spec

## Goal

Add an info button to the `ToolStrip` that opens a modal listing all currently available tools with their enabled/disabled status and a short description of each.

## Architecture

Three focused changes, no new state management needed:

- **`ToolStrip.tsx`** — add an ℹ️ button as the last item after the tool icons; accept an `onInfo` callback prop
- **`ToolInfoModal.tsx`** (new) — self-contained modal component; receives `availableTools`, `disabledTools`, and `onClose`; owns the Escape-key listener and backdrop-click handler
- **`App.tsx`** — add `isToolInfoOpen` boolean state; wire `onInfo` → `setIsToolInfoOpen(true)` and `onClose` → `setIsToolInfoOpen(false)`

## ToolStrip changes

The ℹ️ button is appended directly after the last tool icon — no spacer between them. It uses the same `p-1.5 rounded-md text-base leading-none` sizing as the tool buttons, with `bg-blue-50` background (`#eff6ff`) — halfway between the tool icon `bg-blue-100` and white — making it visually part of the strip but distinct from active tools.

```
[🌐] [📁̶] [✏️] [ℹ️]
```

The ℹ️ button is always shown whenever the strip is visible (i.e. `availableTools.length > 0`).

## ToolInfoModal component

A centered modal with a dark semi-transparent backdrop (`bg-black/45`). Dismissal: backdrop click, × button, or Escape key. The Escape listener is added on mount and removed on unmount.

Modal header: "Available tools" + × close button.

Each tool entry shows:
- Icon (emoji, same as `TOOL_META`)
- Name (bold)
- Status badge: green "enabled" or red "disabled"
- One-line description (from a `TOOL_DESCRIPTIONS` map in `ToolInfoModal.tsx`)
- Disabled entries rendered at 50% opacity

Tool descriptions:
| Tool | Description |
|---|---|
| `web` | Search the web and fetch URLs. |
| `unix` | Browse, read and search files in the selected project. |
| `md-writer` | Write spec and plan markdown files to the project. |
| _(unknown)_ | _(tool name only, no description)_ |

Tools are listed in `availableTools` order (server-granted order: web, md-writer, then unix if rootDirectory set).

## App.tsx changes

Add `const [isToolInfoOpen, setIsToolInfoOpen] = useState(false)`.

Pass to `ToolStrip`:
```tsx
<ToolStrip
  availableTools={availableTools}
  disabledTools={disabledTools}
  onToggle={toggleTool}
  onInfo={() => setIsToolInfoOpen(true)}
/>
```

Render modal after the strip:
```tsx
{isToolInfoOpen && (
  <ToolInfoModal
    availableTools={availableTools}
    disabledTools={disabledTools}
    onClose={() => setIsToolInfoOpen(false)}
  />
)}
```

## Styling reference

| Element | Tailwind classes |
|---|---|
| Info button | `p-1.5 rounded-md text-base leading-none cursor-pointer bg-blue-50` |
| Backdrop | `fixed inset-0 bg-black/45 z-50 flex items-center justify-center` |
| Modal panel | `bg-white rounded-xl w-[380px] max-w-[90vw] shadow-2xl overflow-hidden` |
| Modal header | `px-5 py-4 border-b border-gray-100 flex justify-between items-center` |
| Status badge — enabled | `text-xs bg-green-100 text-green-800 px-2 py-0.5 rounded-full font-medium` |
| Status badge — disabled | `text-xs bg-red-100 text-red-800 px-2 py-0.5 rounded-full font-medium` |
| Disabled tool row | `opacity-50` |

## Out of scope

- Toggling tools from within the modal (toggle stays on the strip)
- Backend changes (purely frontend)
- Persisting modal open state across sessions
