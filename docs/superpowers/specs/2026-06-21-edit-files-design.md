# Edit Files Feature — Design Spec

**Date:** 2026-06-21  
**Branch:** feature/edit-files  
**Status:** Approved

## Overview

Admin users can type `!edit <path>` in the chat footer to open a popup editor for any text file in the selected root directory. The editor has Save and Close buttons. No syntax highlighting initially; a plugin seam allows file-type-specific renderers to be added later.

---

## Backend — `FileController`

New Spring `@RestController` at `/ai/files`.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/ai/files?path=<relpath>&rootDirectory=<root>` | Read file, returns `text/plain; charset=UTF-8` |
| `PUT` | `/ai/files?path=<relpath>&rootDirectory=<root>` | Write file, body is `text/plain`, returns 204 |

### Auth & validation (both endpoints)

1. **Admin-only** — non-admins receive 403.
2. `rootDirectory` must be in `RootDirectories.ALLOWED`, else 400.
3. `rootDirectory` must be non-empty, else 400.
4. `path` must not be absolute (rejected if it starts with `/`, `\`, or matches `[A-Za-z]:`) and must not contain any `..` segment, else 400.
5. Resolve `rootDirectory.resolve(path)` and verify the canonical result still starts with the canonical root (guards against symlink traversal), else 403.

### Write atomicity

`PUT` writes to a sibling temp file then renames it over the target — prevents partial writes on crash.

---

## Frontend API — `api.ts`

Two new exported functions:

```ts
readFile(path: string, rootDirectory: string, token?: string | null): Promise<string>
// GET /ai/files?path=...&rootDirectory=... — throws on non-2xx

writeFile(path: string, rootDirectory: string, content: string, token?: string | null): Promise<void>
// PUT /ai/files?path=...&rootDirectory=... with text/plain body — throws on non-2xx
```

---

## `!edit` Interception — `useConversation.ts`

`handleSend` already dispatches all `!` commands. `!edit` is added as a new early-return branch, before the generic `execTool` fallthrough:

```
if input matches /^!edit\s+(.+)$/i:
  if rootDirectory is empty → show toast "Select a root directory first"
  else → set editorFile = { path: trimmed capture group, rootDirectory }
  return (no chat message added, loading never set)
```

New state returned from the hook:
- `editorFile: { path: string; rootDirectory: string } | null`
- `closeEditor: () => void` — sets `editorFile` to null

`App.tsx` changes are limited to:
1. Destructuring `editorFile` and `closeEditor` from `useConversation`.
2. Adding `{editorFile && <FileEditorModal ... />}` to the JSX.

---

## `FileEditorModal.tsx`

Portal modal (`ReactDOM.createPortal` to `document.body`), following the `ToolInfoModal` / `ImageLightbox` pattern.

### Layout

```
┌─────────────────────────────────────┐
│ src/config/RootDirectories.java  ✕  │  ← header: path + close button
├─────────────────────────────────────┤
│                                     │
│  <textarea / plugin renderer>       │  ← fills remaining height, monospace
│                                     │
├─────────────────────────────────────┤
│              [Close]  [Save]        │  ← footer, right-aligned
└─────────────────────────────────────┘
```

### Behaviour

- **On mount:** calls `readFile()`, populates textarea. Shows a loading state while fetching; shows inline error on failure.
- **Textarea:** controlled, monospace font, full modal body height, no formatting.
- **Save:** calls `writeFile()`, disables both buttons while saving, shows inline error on failure, closes modal on success.
- **Close:** closes immediately without unsaved-changes prompt (can be added later).
- **Escape key:** closes (same as Close button).
- **Backdrop click:** closes.

### Plugin seam

```ts
export interface FileEditorPlugin {
  test: (path: string) => boolean;
  render: (content: string, onChange: (value: string) => void) => ReactNode;
}
```

`FileEditorModal` accepts `plugins?: FileEditorPlugin[]`. The first plugin whose `test(path)` returns true replaces the default `<textarea>`. With no plugins provided, behaviour is a plain textarea. This prop is not passed from `App` initially (no plugins exist yet).

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| `!edit` with no root directory selected | Toast via existing `showToast` mechanism |
| File not found (404 on read) | Inline error in modal |
| Permission denied / path traversal (400/403) | Inline error in modal |
| Write failure | Inline error in modal, modal stays open |
| Network error | Inline error in modal |

---

## Security

- Path traversal prevention is enforced server-side (canonical path check after resolution).
- Admin-only enforcement is server-side; the frontend `isAdmin` flag is used only to decide whether to surface the feature (no `!edit` matching for non-admins — they see no message, or could be shown a toast).
- `rootDirectory` must be in the server-side allowlist (`RootDirectories.ALLOWED`) — client cannot supply an arbitrary path.

---

## Out of Scope (this iteration)

- Unsaved-changes confirmation on Close
- Syntax highlighting or rich editing
- File creation / deletion
- Directory browsing
- Non-text (binary) file handling
