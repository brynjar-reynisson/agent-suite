# Prompt Bank — Design Spec

**Date:** 2026-05-07  
**Branch:** feature/prompt-bank  
**Scope:** Frontend only (`frontend/src/App.tsx`)

---

## Goal

Replace the plain system-prompt text input with an editable combobox that lets users pick from a curated list of named prompts (the "prompt bank") while still allowing free-text entry.

---

## Data Model

A hardcoded `PROMPT_BANK` array in `App.tsx`, co-located with the existing `MODELS` constant:

```typescript
const PROMPT_BANK = [
  {
    name: 'Code-request classifier',
    text: 'You are a coding assistant and will use the available tools on the selected codebase to classify the coding requests you receive. Respond in json format 1) intent, which shall be either bug-fix, enhancement, new-feature or unknown, 2) confidence in the classification (percentages)',
  },
];
```

- `name` — displayed in the dropdown and in the input field after selection.
- `text` — the actual system prompt sent to the API.

The `prompt` state variable (already exists) holds whatever is shown in the input: either a preset name or free-typed text.

**Resolution at send time** — `handleSend` resolves the display value to prompt text before calling `chatStream`:

```typescript
const resolvedPrompt = PROMPT_BANK.find(p => p.name === prompt)?.text ?? prompt;
```

If `prompt` matches a preset name, `resolvedPrompt` is the preset's full text. Otherwise `resolvedPrompt` equals `prompt` verbatim (free-text case).

---

## UI Component — `PromptCombobox`

A small inline component defined in `App.tsx` (no new file needed). Replaces the `<input type="text">` in the settings panel. Props: `value: string` and `onChange: (v: string) => void`, wired to the existing `prompt` state.

### Structure

```
┌─────────────────────────────────────────┬──┐
│  <input> (editable, full width)         │▾ │
└─────────────────────────────────────────┴──┘
  ┌─────────────────────────────────────────┐
  │ Code-request classifier                 │  ← <ul> dropdown, shown when open
  └─────────────────────────────────────────┘
```

- Outer wrapper: `relative` positioned div, same class footprint as the current input.
- `<input>`: bound to `prompt`/`setPrompt`, with right padding to clear the button.
- `▾` button: absolutely positioned at the right edge, toggles `isOpen` state.
- `<ul>` dropdown: `absolute`, full width, `z-10`, hidden when `isOpen === false`. One `<li>` per `PROMPT_BANK` entry.
- Click-outside: `useEffect` adds a `mousedown` listener on `document`; clears `isOpen` when the click target is outside the wrapper (via a `useRef` on the wrapper div).

### Interactions

| Action | Result |
|---|---|
| Click `▾` | Toggle dropdown open/closed |
| Click a list item | Set `prompt` to item's `name`, close dropdown |
| Type in input | Normal text edit; dropdown stays closed |
| Click outside | Close dropdown |

### Styling

Inherits the existing input classes (`border rounded px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none`). The `▾` button uses `text-gray-400 hover:text-gray-600`. Dropdown list uses `bg-white border rounded shadow-sm` with `hover:bg-gray-100` on items. Visual footprint is identical to the current input.

---

## Changes Required

| File | Change |
|---|---|
| `frontend/src/App.tsx` | Add `PROMPT_BANK` constant; add `PromptCombobox` component; replace system-prompt `<input>` with `<PromptCombobox>`; resolve prompt name→text in `handleSend` |

No new files. No new dependencies.

---

## Out of Scope

- Backend storage or API for prompt bank entries (deferred).
- Populating the prompt bank from an external source (deferred).
- Deleting, reordering, or editing entries from the UI (deferred).
