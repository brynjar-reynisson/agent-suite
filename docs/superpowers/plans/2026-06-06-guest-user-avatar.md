# Guest User Avatar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a purely visual guest user avatar indicator to the top-right of the chat header.

**Architecture:** A small `UserAvatar` function component defined at the top of `App.tsx` and rendered as the last element in the header's right button group. No state, no event handlers, no backend changes.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4 (inline styles used for the avatar to keep it self-contained)

---

### Task 1: Add UserAvatar component and render it in the header

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add the `UserAvatar` component** — define it just before the `App` function in `frontend/src/App.tsx`:

```tsx
function UserAvatar() {
  return (
    <div className="relative" style={{ width: 32, height: 32 }} title="Guest">
      <div
        className="w-full h-full rounded-full flex items-center justify-center font-bold text-sm text-gray-500"
        style={{ background: '#e5e7eb', border: '2px solid #d1d5db', cursor: 'default' }}
      >
        G
      </div>
      <div
        className="absolute rounded-full"
        style={{
          width: 10,
          height: 10,
          background: '#f59e0b',
          border: '2px solid #ffffff',
          bottom: 0,
          right: 0,
        }}
      />
    </div>
  );
}
```

- [ ] **Step 2: Render `<UserAvatar />` after the `☰` button in the header**

In `App.tsx`, find the header's right button group (the `<div className="flex gap-2 items-center">` block). Append `<UserAvatar />` after the history button:

```tsx
<div className="flex gap-2 items-center">
  <select
    value={model}
    onChange={(e) => setModel(e.target.value)}
    className="border rounded px-2 py-1 text-sm bg-gray-50"
  >
    {MODELS.map((m) => (
      <option key={m} value={m}>{m}</option>
    ))}
  </select>
  <button
    onClick={startNewConversation}
    disabled={loading}
    title="New conversation"
    className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 font-bold text-lg leading-none"
    aria-label="New conversation"
  >
    +
  </button>
  <button
    onClick={() => setIsPanelOpen(true)}
    disabled={loading}
    title="Past conversations"
    className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 text-base leading-none"
    aria-label="Past conversations"
  >
    ☰
  </button>
  <UserAvatar />
</div>
```

- [ ] **Step 3: Verify visually** — run the frontend dev server and confirm the avatar appears:

```bash
cd frontend && npm run dev
```

Open http://localhost:5176. The header should show the gray "G" circle with an amber dot at bottom-right, after the ☰ button. Hover over it — a native "Guest" tooltip should appear. Clicking it does nothing.

- [ ] **Step 4: Commit**

```bash
git -C . add frontend/src/App.tsx
git -C . commit -m "feat: add guest user avatar indicator to header"
```
