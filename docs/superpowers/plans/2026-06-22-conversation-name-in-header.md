# Conversation Name in Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the active conversation's display name (`customName ?? name`) to the right of "AgentSuite Chat" in the page header; nothing shown when no conversation is active.

**Architecture:** State lives in `useConversation.ts` (which already owns conversation lifecycle). `App.tsx` reads `activeConvDisplayName` from the hook and renders it in the header. `ConversationPanel.tsx` gains an optional `onRename` callback so the header stays in sync when the user renames the currently-loaded conversation from the panel.

**Tech Stack:** React 19, TypeScript, Tailwind CSS 4, Vite 8.

## Global Constraints

- No new files — changes confined to `frontend/src/useConversation.ts`, `frontend/src/App.tsx`, `frontend/src/ConversationPanel.tsx`
- Display name resolution: `customName ?? name` (both fields exist on `ConversationSummary`)
- Header shows nothing when no conversation is loaded (`activeConvDisplayName === null`)
- `onRename` is called **after** the API call succeeds (not optimistically), with `trimmed || conv.name`

---

### Task 1: Display active conversation name in header

**Files:**
- Modify: `frontend/src/useConversation.ts`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/ConversationPanel.tsx`

**Interfaces:**
- `useConversation` return gains:
  - `activeConvDisplayName: string | null`
  - `updateActiveConvDisplayName: (externalId: string, displayName: string) => void`
- `ConversationPanel` Props gains:
  - `onRename?: (externalId: string, displayName: string) => void`

---

- [ ] **Step 1: Add `activeConvDisplayName` state to `useConversation.ts`**

After the `editorFile` state line (currently line 27):
```ts
const [activeConvDisplayName, setActiveConvDisplayName] = useState<string | null>(null);
```

Replace `resetConversation` (currently lines 38–43) to clear the name:
```ts
const resetConversation = useCallback(() => {
  conversationId.current = crypto.randomUUID();
  lastSentModel.current = null;
  lastSentPrompt.current = null;
  setMessages([]);
  setActiveConvDisplayName(null);
}, []);
```

Replace `loadConversation` (currently lines 49–57) to set the name from the summary parameter:
```ts
const loadConversation = async (conv: ConversationSummary): Promise<ConversationDetail> => {
  const token = await getAccessToken();
  const detail = await getConversationDetail(conv.externalId, token);
  conversationId.current = detail.externalId;
  lastSentModel.current = detail.initialModel;
  lastSentPrompt.current = detail.systemPrompt;
  setMessages(detail.messages);
  setActiveConvDisplayName(conv.customName ?? conv.name);
  return detail;
};
```

Add `updateActiveConvDisplayName` directly after `loadConversation`. It checks the ref (not state) so the dep array is empty:
```ts
const updateActiveConvDisplayName = useCallback((externalId: string, displayName: string) => {
  if (externalId === conversationId.current) {
    setActiveConvDisplayName(displayName);
  }
}, []);
```

Replace the return statement (currently line 269):
```ts
return { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor, activeConvDisplayName, updateActiveConvDisplayName };
```

- [ ] **Step 2: Add `onRename` prop to `ConversationPanel.tsx`**

Replace the Props interface (lines 5–9):
```ts
interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (conv: ConversationSummary) => Promise<void>;
  onRename?: (externalId: string, displayName: string) => void;
}
```

Replace the function signature (line 15):
```ts
export function ConversationPanel({ isOpen, onClose, onSelect, onRename }: Props) {
```

Replace `saveEdit` (lines 64–86) to call `onRename` after a successful API call:
```ts
const saveEdit = async (conv: ConversationSummary) => {
  if (editHandledRef.current) return;
  editHandledRef.current = true;
  const trimmed = editValue.trim();
  const prevCustomName = conv.customName;
  setConversations(prev =>
    prev.map(c =>
      c.externalId === conv.externalId ? { ...c, customName: trimmed || null } : c,
    ),
  );
  setEditingId(null);
  try {
    const token = await getAccessToken();
    await renameConversation(conv.externalId, trimmed, token);
    onRename?.(conv.externalId, trimmed || conv.name);
  } catch {
    setConversations(prev =>
      prev.map(c =>
        c.externalId === conv.externalId ? { ...c, customName: prevCustomName } : c,
      ),
    );
    setSelectError('Failed to rename conversation');
  }
};
```

- [ ] **Step 3: Update `App.tsx`**

Replace the `useConversation` destructure (line 68):
```ts
const { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor, activeConvDisplayName, updateActiveConvDisplayName } =
  useConversation({ model, prompt, rootDirectory, availableTools, disabledTools, isAdmin });
```

Replace the entire `<header>` element (lines 95–127) — only the `<h1>` is wrapped in a new flex div; everything else is unchanged:
```tsx
<header className="bg-white shadow-sm p-4 flex justify-between items-center">
  <div className="flex items-baseline gap-3 min-w-0">
    <h1 className="text-xl font-bold text-gray-800 flex-shrink-0">AgentSuite Chat</h1>
    {activeConvDisplayName && (
      <span className="text-sm text-gray-400 truncate">{activeConvDisplayName}</span>
    )}
  </div>
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
    <UserAvatar user={user} signIn={signIn} signOut={signOut} />
  </div>
</header>
```

Replace the `ConversationPanel` usage (around line 203):
```tsx
<ConversationPanel
  isOpen={isPanelOpen}
  onClose={() => setIsPanelOpen(false)}
  onSelect={handleLoadConversation}
  onRename={updateActiveConvDisplayName}
/>
```

- [ ] **Step 4: Build and verify in browser**

From the project root run:
```
build.cmd
```
Expected: `BUILD SUCCESS` with no TypeScript errors.

Open `http://localhost:5177` and verify:
1. Fresh load → header shows only "AgentSuite Chat", nothing to its right
2. Open ☰ panel, select a conversation → name appears to the right of "AgentSuite Chat" in gray
3. Click + (new conversation) → name disappears from header
4. Load a conversation, open panel, rename it → after saving, header shows updated name
5. Load a conversation with a very long name → name truncates with ellipsis rather than overflowing

- [ ] **Step 5: Commit**

```
git add frontend/src/useConversation.ts frontend/src/App.tsx frontend/src/ConversationPanel.tsx
git commit -m "feat: show active conversation name in page header"
```
