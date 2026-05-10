# Prompt Bank Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the plain system-prompt text input with an editable combobox that shows a dropdown of named prompts, resolving the selected name to its full text before sending to the API.

**Architecture:** A `PromptCombobox` component is added inline in `App.tsx` — it wraps an `<input>` with a toggle button and an absolutely-positioned dropdown list. The `prompt` state continues to hold what's displayed (a preset name or free text). `handleSend` resolves a matching preset name to its full text before calling `chatStream`.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4, Vite 8

---

## File Map

| File | Action | What changes |
|---|---|---|
| `frontend/src/App.tsx` | Modify | Add `PROMPT_BANK` constant; add `PromptCombobox` component; replace system-prompt `<input>` with `<PromptCombobox>`; resolve name→text in `handleSend` |

No new files. No new dependencies.

---

### Task 1: Add PROMPT_BANK constant and PromptCombobox component

**Files:**
- Modify: `frontend/src/App.tsx`

> No frontend test framework exists in this project. Verification is manual (dev server).

- [ ] **Step 1: Add PROMPT_BANK constant after the MODELS array (after line 28)**

In `frontend/src/App.tsx`, insert after the closing `];` of `MODELS`:

```typescript
const PROMPT_BANK = [
  {
    name: 'Code-request classifier',
    text: 'You are a coding assistant and will use the available tools on the selected codebase to classify the coding requests you receive. Respond in json format 1) intent, which shall be either bug-fix, enhancement, new-feature or unknown, 2) confidence in the classification (percentages)',
  },
];
```

- [ ] **Step 2: Add PromptCombobox component before the App function (before `function App()`)**

In `frontend/src/App.tsx`, insert immediately before `function App() {`:

```typescript
interface PromptComboboxProps {
  value: string;
  onChange: (v: string) => void;
}

function PromptCombobox({ value, onChange }: PromptComboboxProps) {
  const [isOpen, setIsOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleMouseDown = (e: MouseEvent) => {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, []);

  return (
    <div ref={wrapperRef} className="relative">
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="System instructions..."
        className="w-full border rounded px-3 py-2 pr-8 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
      />
      <button
        type="button"
        onClick={() => setIsOpen((o) => !o)}
        className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
      >
        ▾
      </button>
      {isOpen && (
        <ul className="absolute z-10 w-full bg-white border rounded shadow-sm mt-1">
          {PROMPT_BANK.map((entry) => (
            <li
              key={entry.name}
              onMouseDown={() => {
                onChange(entry.name);
                setIsOpen(false);
              }}
              className="px-3 py-2 text-sm cursor-pointer hover:bg-gray-100"
            >
              {entry.name}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
```

> `onMouseDown` is used on list items (not `onClick`) so the selection fires before the click-outside `mousedown` listener closes the dropdown.

---

### Task 2: Wire PromptCombobox into the settings panel and add resolution in handleSend

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Replace the system-prompt `<input>` with `<PromptCombobox>`**

Find this block in the Settings Panel section (around line 207):

```tsx
<input 
  type="text" 
  value={prompt} 
  onChange={(e) => setPrompt(e.target.value)}
  placeholder="System instructions..."
  className="w-full border rounded px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500 outline-none"
/>
```

Replace it with:

```tsx
<PromptCombobox value={prompt} onChange={setPrompt} />
```

- [ ] **Step 2: Add prompt resolution in handleSend**

Find the `chatStream` call inside `handleSend`. It currently passes `prompt: prompt`. Add the resolution line immediately before `chatStream(` and update the prompt argument:

Before (around line 86):
```typescript
    try {
      await chatStream(
        {
          message: message,
          prompt: prompt,
          rootDirectory: rootDirectory,
          model: model,
        },
```

After:
```typescript
    const resolvedPrompt = PROMPT_BANK.find(p => p.name === prompt)?.text ?? prompt;
    try {
      await chatStream(
        {
          message: message,
          prompt: resolvedPrompt,
          rootDirectory: rootDirectory,
          model: model,
        },
```

- [ ] **Step 3: Start the dev server and verify manually**

```bash
cd frontend && npm run dev
```

Open `http://localhost:5176` and verify:

1. The SYSTEM PROMPT field looks the same as before (same width, same border/padding).
2. A small `▾` button appears at the right edge of the field.
3. Clicking `▾` opens a dropdown showing "Code-request classifier".
4. Clicking "Code-request classifier" closes the dropdown and puts the name in the input field.
5. The input field remains editable — you can type over the name.
6. Clicking outside the combobox closes the dropdown without changing the value.
7. Send a message with "Code-request classifier" selected and confirm in the browser network tab (or server logs) that the full prompt text is transmitted, not the name.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat: replace system prompt input with editable combobox (prompt bank)"
```
