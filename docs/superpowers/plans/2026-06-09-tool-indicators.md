# Tool Indicators Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a compact icon strip above the chat input that shows which tool groups are active for the current session, with click-to-toggle (visual only).

**Architecture:** New `ToolStrip` component receives `availableTools` + `disabledTools` as props and renders icon buttons with tooltips and a grayscale/slash overlay for disabled state. `App` derives `availableTools` reactively from `prompt` + `rootDirectory` state via `useMemo`, and manages `disabledTools` state with a `useEffect` reset on tool-set change.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4

---

## File Map

| File | Change |
|---|---|
| `frontend/src/ToolStrip.tsx` | Create — component + TOOL_META constant |
| `frontend/src/App.tsx` | Modify — add memo/state/effect, render `<ToolStrip>` |

---

### Task 1: Create ToolStrip component

**Files:**
- Create: `frontend/src/ToolStrip.tsx`

- [ ] **Step 1: Create the file with full implementation**

Create `frontend/src/ToolStrip.tsx` with this exact content:

```tsx
const TOOL_META: Record<string, { icon: string; tooltip: string }> = {
  'unix':      { icon: '📁', tooltip: 'unix: ls · cat · grep' },
  'md-writer': { icon: '✏️', tooltip: 'md-writer: write markdown files' },
  'web':       { icon: '🌐', tooltip: 'web: search · fetch' },
};

interface ToolStripProps {
  availableTools: string[];
  disabledTools: Set<string>;
  onToggle: (tool: string) => void;
}

export function ToolStrip({ availableTools, disabledTools, onToggle }: ToolStripProps) {
  if (availableTools.length === 0) return null;

  return (
    <div className="bg-white border-t border-gray-100 px-4 py-1.5 flex gap-2 items-center">
      {availableTools.map((tool) => {
        const meta = TOOL_META[tool] ?? { icon: '🔧', tooltip: tool };
        const disabled = disabledTools.has(tool);
        return (
          <button
            key={tool}
            type="button"
            title={meta.tooltip}
            onClick={() => onToggle(tool)}
            aria-label={`${meta.tooltip}${disabled ? ' (disabled)' : ''}`}
            aria-pressed={!disabled}
            className={`relative p-1.5 rounded-md text-base leading-none cursor-pointer transition-all ${
              disabled ? 'bg-gray-100 opacity-40' : 'bg-blue-100'
            }`}
            style={{ filter: disabled ? 'grayscale(1)' : undefined }}
          >
            {meta.icon}
            {disabled && (
              <span className="absolute inset-0 flex items-center justify-center pointer-events-none" aria-hidden="true">
                <span style={{ display: 'block', width: '80%', height: '1.5px', background: '#6b7280', transform: 'rotate(-20deg)' }} />
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npx tsc -b --noEmit
```

Expected: no output (zero errors). If you see errors, fix them before continuing.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/ToolStrip.tsx
git commit -m "feat: add ToolStrip component with icon-only active/disabled display"
```

---

### Task 2: Wire ToolStrip into App.tsx

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add `useMemo` to the React import and import `ToolStrip`**

Find line 1 of `frontend/src/App.tsx`:
```tsx
import { useEffect, useRef, useState } from 'react';
```
Replace with:
```tsx
import { useEffect, useMemo, useRef, useState } from 'react';
```

Add this import after the existing import block (after line 8, the `useAuth`/`UserAvatar` import):
```tsx
import { ToolStrip } from './ToolStrip';
```

- [ ] **Step 2: Add `availableTools` memo, `disabledTools` state, reset effect, and toggle callback**

In the `App` function body, find this block (around line 155–158):
```tsx
  const [isPanelOpen, setIsPanelOpen] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const { user, signIn, signOut } = useAuth();
```

Insert the following **before** that block:
```tsx
  const availableTools = useMemo(() => {
    const matched = PROMPT_BANK.find(p => p.name === prompt);
    const toolSet = new Set(matched?.tools ?? []);
    if (rootDirectory) toolSet.add('unix');
    return [...toolSet];
  }, [prompt, rootDirectory]);

  const [disabledTools, setDisabledTools] = useState<Set<string>>(new Set());

  useEffect(() => {
    setDisabledTools(new Set());
  }, [availableTools]);

  const toggleTool = (tool: string) =>
    setDisabledTools(prev => {
      const next = new Set(prev);
      next.has(tool) ? next.delete(tool) : next.add(tool);
      return next;
    });

```

- [ ] **Step 3: Render `<ToolStrip>` between the settings panel and the footer**

Find the closing tag of the settings panel (the `</div>` that ends the `{/* Settings Panel */}` block, just before `{/* Input Area */}`):

```tsx
      </div>

      {/* Input Area */}
      <footer className="bg-white border-t p-4 flex gap-2">
```

Insert `<ToolStrip>` between them:
```tsx
      </div>

      <ToolStrip
        availableTools={availableTools}
        disabledTools={disabledTools}
        onToggle={toggleTool}
      />

      {/* Input Area */}
      <footer className="bg-white border-t p-4 flex gap-2">
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd frontend && npx tsc -b --noEmit
```

Expected: no output (zero errors).

- [ ] **Step 5: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat: wire ToolStrip into App — reactive available tools, click-to-toggle"
```

---

### Task 3: Verify in browser

**Files:** none (read-only verification)

- [ ] **Step 1: Start the dev server**

```bash
cd frontend && npm run dev
```

Open `http://localhost:5177` in your browser.

- [ ] **Step 2: Verify strip is hidden on fresh load**

With no system prompt selected and no root directory set, the strip should not be visible (no gap between settings panel and input).

- [ ] **Step 3: Verify strip appears when a prompt is selected**

In the SYSTEM PROMPT dropdown, pick **"Implementation-planner"**. The strip should appear showing two icons: 📁 (unix) and ✏️ (md-writer), both with blue backgrounds. Hover each to confirm the native tooltip text appears.

- [ ] **Step 4: Verify root directory auto-adds unix**

Clear the system prompt. Select any root directory from the ROOT DIRECTORY dropdown. The strip should appear with 📁 (unix) alone.

- [ ] **Step 5: Verify click-to-disable**

With "Implementation-planner" selected, click the ✏️ icon. It should go grayscale + faded + diagonal slash. Click it again — it should return to the active blue state.

- [ ] **Step 6: Verify reset on prompt switch**

Disable ✏️ (md-writer), then switch the system prompt to "Web-dweller". The strip should now show only 🌐 (web) in the active blue state — no disabled state carried over.

- [ ] **Step 7: Commit if any visual tweaks were made**

If you needed to tweak any styles during verification:
```bash
git add frontend/src/ToolStrip.tsx
git commit -m "fix: adjust ToolStrip visual styling"
```

If no tweaks were needed, skip this step.
