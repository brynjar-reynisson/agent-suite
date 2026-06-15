# Message History Size Indicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the size of message history since the last `/compact` (or from the start) as a colour-coded MB pill in the bottom-right of the chat area.

**Architecture:** One `useMemo` computes bytes from the relevant slice of `messages`; one JSX element renders the pill inside `<main>`. Frontend-only, no backend changes.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `frontend/src/App.tsx` | `historySizeBytes` useMemo + pill element |

---

## Task 1: Add size indicator to `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

### Background

`App.tsx` already has a `useMemo` for `availableTools` (around line 163). Add `historySizeBytes` immediately after it. The `<main>` chat area (around line 423) is a `flex-col overflow-y-auto` container — the pill element goes inside it, after the `messages.map` block and before `<div ref={bottomRef} />`, using `self-end sticky bottom-2` so it sticks to the bottom-right of the visible scroll area.

- [ ] **Step 1: Add `historySizeBytes` useMemo**

In `frontend/src/App.tsx`, locate the `availableTools` useMemo block (around line 163):

```ts
const availableTools = useMemo(() => {
  ...
}, [grantedToolGroups, rootDirectory]);
```

Immediately after the closing `}, [grantedToolGroups, rootDirectory]);` line, add:

```ts
const historySizeBytes = useMemo(() => {
  const lastCompactIdx = messages.reduce(
    (acc: number, msg, i) => (msg.role === 'compact' ? i : acc), -1
  );
  const relevant = lastCompactIdx >= 0 ? messages.slice(lastCompactIdx) : messages;
  return new TextEncoder().encode(JSON.stringify(relevant)).length;
}, [messages]);
```

- [ ] **Step 2: Add the pill element inside `<main>`**

In `frontend/src/App.tsx`, find this block (around line 494–499):

```tsx
        {loading && (
          <div className="self-start bg-white p-3 rounded-lg shadow-sm text-gray-400 animate-pulse">
            Thinking...
          </div>
        )}
        <div ref={bottomRef} />
      </main>
```

Replace it with:

```tsx
        {loading && (
          <div className="self-start bg-white p-3 rounded-lg shadow-sm text-gray-400 animate-pulse">
            Thinking...
          </div>
        )}
        {messages.length > 0 && (
          <div className={`self-end sticky bottom-2 rounded-full px-2 py-0.5 text-xs font-mono bg-gray-900/60 ${
            historySizeBytes >= 2.5 * 1_048_576
              ? 'text-red-400'
              : historySizeBytes >= 1.5 * 1_048_576
              ? 'text-amber-400'
              : 'text-gray-400'
          }`}>
            {(historySizeBytes / 1_048_576).toFixed(2)} MB
          </div>
        )}
        <div ref={bottomRef} />
      </main>
```

- [ ] **Step 3: TypeScript check**

```
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: show message history size in MB in chat area"
```

---

## Task 2: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a note to the Frontend section**

In `CLAUDE.md`, find the `Key files:` list under `## Frontend`. In the `App.tsx` entry description, add at the end:

```
Computes `historySizeBytes` (useMemo over messages since last compact) and renders a colour-coded MB pill in the bottom-right of the chat area (gray < 1.5 MB, amber 1.5–2.5 MB, red ≥ 2.5 MB).
```

- [ ] **Step 2: Commit**

```
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add CLAUDE.md
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "docs: document history size indicator in CLAUDE.md"
```
