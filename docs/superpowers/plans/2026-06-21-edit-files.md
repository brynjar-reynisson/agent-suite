# Edit Files Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `!edit <path>` command that opens a modal text editor for files in the selected root directory, admin-only, with a plugin seam for future file-type renderers.

**Architecture:** `!edit` is intercepted in `useConversation.ts` before the generic `!` dispatch, setting `editorFile` state that `App.tsx` uses to render `FileEditorModal`. The modal reads/writes via new `GET /ai/files` and `PUT /ai/files` endpoints in a new `FileController`, both admin-only and path-confined to `RootDirectories.ALLOWED`.

**Tech Stack:** Spring Boot 3.5 / Java 21 (backend); React 19 + Tailwind CSS 4 (frontend); JUnit 5 + MockMvc + `@WebMvcTest` (tests).

## Global Constraints

- All new backend endpoints must be in `@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})`.
- Admin check via `request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN)` — no changes to auth infrastructure.
- Path validation: reject absolute paths and `..` segments; canonical root-confinement check with `toRealPath()`.
- No new npm dependencies. No new Maven dependencies.
- Frontend: controlled textarea, `ReactDOM.createPortal`, Tailwind utility classes only.
- Commit with `git -C <path>` (never `cd`).

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `src/main/java/com/example/agentsuite/config/RootDirectories.java` | Change `Set.of(...)` to `new LinkedHashSet<>(Set.of(...))` to allow test injection |
| Create | `src/main/java/com/example/agentsuite/controller/FileController.java` | `GET /ai/files` (read) and `PUT /ai/files` (write), admin-only, path-confined |
| Create | `src/test/java/com/example/agentsuite/controller/FileControllerTest.java` | `@WebMvcTest` tests for FileController |
| Modify | `frontend/src/api.ts` | Add `readFile()` and `writeFile()` |
| Modify | `frontend/src/useConversation.ts` | Add `editorFile` state, `closeEditor`, intercept `!edit` |
| Create | `frontend/src/FileEditorModal.tsx` | Editor modal, `FileEditorPlugin` interface |
| Modify | `frontend/src/App.tsx` | Destructure new hook values, render `FileEditorModal` |

---

### Task 1: Feature branch + Backend FileController

**Files:**
- Create branch: `feature/edit-files`
- Modify: `src/main/java/com/example/agentsuite/config/RootDirectories.java`
- Create: `src/main/java/com/example/agentsuite/controller/FileController.java`
- Create: `src/test/java/com/example/agentsuite/controller/FileControllerTest.java`

**Interfaces:**
- Produces: `GET /ai/files?path=<relpath>&rootDirectory=<root>` → `200 text/plain` or `400`/`403`/`404`
- Produces: `PUT /ai/files?path=<relpath>&rootDirectory=<root>` with `text/plain` body → `204` or `400`/`403`/`500`

- [ ] **Step 1: Create feature branch**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" checkout -b feature/edit-files
```

- [ ] **Step 2: Make `RootDirectories.ALLOWED` mutable so tests can inject a temp dir**

Edit `src/main/java/com/example/agentsuite/config/RootDirectories.java`. Change:
```java
public static final Set<String> ALLOWED = Set.of(
```
to:
```java
public static final Set<String> ALLOWED = new java.util.LinkedHashSet<>(Set.of(
```
and close with `));` instead of `);`. The full `ALLOWED` declaration becomes:
```java
public static final Set<String> ALLOWED = new java.util.LinkedHashSet<>(Set.of(
        "",
        "C:/Users/Lenovo/misc_projects/dragon",
        "C:/Users/Lenovo/misc_projects/gexplorer",
        "C:/Users/Lenovo/IdeaProjects/agent-suite",
        "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian",
        "C:/REAPER/Projects",
        "C:/Users/Lenovo/IdeaProjects/digital-me"
));
```

- [ ] **Step 3: Write failing tests**

Create `src/test/java/com/example/agentsuite/controller/FileControllerTest.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.jooq.service.SuiteUserService;
import com.example.agentsuite.service.AuthorizationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
class FileControllerTest {

    @TempDir
    static Path tempDir;
    static String root;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SuiteUserService suiteUserService;

    @MockBean
    AuthorizationService authorizationService;

    @BeforeAll
    static void addTempToAllowed() {
        root = tempDir.toString().replace("\\", "/");
        RootDirectories.ALLOWED.add(root);
    }

    @AfterAll
    static void removeTempFromAllowed() {
        RootDirectories.ALLOWED.remove(root);
    }

    @BeforeEach
    void setUpAuth() {
        lenient().when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        lenient().when(authorizationService.isAdmin(42L)).thenReturn(true);
    }

    private static final String ADMIN_BEARER = "Bearer " + makeAdminJwt();

    private static String makeAdminJwt() {
        return Jwts.builder()
                .setSubject("admin-sub")
                .claim("email", "admin@test.com")
                .setIssuer("http://127.0.0.1:54321/auth/v1")
                .setAudience("authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(
                        Keys.hmacShaKeyFor(
                                "test-secret-padded-to-at-least-32-characters"
                                        .getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void readFile_adminCanReadExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello world");

        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "hello.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void readFile_nonAdminGetsForbidden() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .param("path", "hello.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isForbidden());
    }

    @Test
    void readFile_fileNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "nonexistent.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isNotFound());
    }

    @Test
    void readFile_dotDotTraversalRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "../outside.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_unixAbsolutePathRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "/etc/passwd")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_windowsAbsolutePathRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "C:\\Windows\\System32\\evil.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_invalidRootDirectoryRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "file.txt")
                        .param("rootDirectory", "/not/allowed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeFile_adminCanWriteFile() throws Exception {
        Path file = tempDir.resolve("output.txt");

        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "output.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("new content"))
                .andExpect(status().isNoContent());

        assertThat(Files.readString(file)).isEqualTo("new content");
    }

    @Test
    void writeFile_nonAdminGetsForbidden() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .param("path", "output.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeFile_dotDotTraversalRejected() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "../outside.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeFile_windowsAbsolutePathRejected() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "C:\\Windows\\System32\\evil.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 4: Run tests — expect compilation failure (FileController does not exist yet)**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -pl . -Dtest=FileControllerTest -q 2>&1 | tail -20
```

Expected: compilation error — `cannot find symbol: class FileController`.

- [ ] **Step 5: Implement FileController**

Create `src/main/java/com/example/agentsuite/controller/FileController.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.filter.UserResolverFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class FileController {

    @GetMapping(value = "/ai/files", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> readFile(
            @RequestParam String path,
            @RequestParam String rootDirectory,
            HttpServletRequest request) {
        validateAccess(path, rootDirectory, request);
        Path resolved = resolveSafe(path, rootDirectory);
        try {
            return ResponseEntity.ok(Files.readString(resolved, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping(value = "/ai/files", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> writeFile(
            @RequestParam String path,
            @RequestParam String rootDirectory,
            @RequestBody String content,
            HttpServletRequest request) {
        validateAccess(path, rootDirectory, request);
        Path resolved = resolveSafe(path, rootDirectory);
        Path temp;
        try {
            temp = Files.createTempFile(resolved.getParent(), ".tmp-edit-", null);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot create temp file");
        }
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, resolved, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, resolved, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Write failed");
        }
        return ResponseEntity.noContent().build();
    }

    private void validateAccess(String path, String rootDirectory, HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!RootDirectories.ALLOWED.contains(rootDirectory) || rootDirectory.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid root directory");
        if (isAbsolutePath(path) || containsTraversal(path))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
    }

    private boolean isAbsolutePath(String path) {
        return path.startsWith("/") || path.startsWith("\\")
                || (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':');
    }

    private boolean containsTraversal(String path) {
        for (String segment : path.replace('\\', '/').split("/", -1)) {
            if ("..".equals(segment)) return true;
        }
        return false;
    }

    private Path resolveSafe(String relPath, String rootDirectory) {
        try {
            Path root = Path.of(rootDirectory).toRealPath();
            Path resolved = root.resolve(relPath).normalize();
            if (!resolved.startsWith(root))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Path escapes root");
            return resolved;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot resolve root directory");
        }
    }
}
```

- [ ] **Step 6: Run tests — expect all pass**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -pl . -Dtest=FileControllerTest -q 2>&1 | tail -10
```

Expected output contains: `BUILD SUCCESS`

- [ ] **Step 7: Run full test suite to check for regressions**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/config/RootDirectories.java src/main/java/com/example/agentsuite/controller/FileController.java src/test/java/com/example/agentsuite/controller/FileControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add FileController for admin file read/write"
```

---

### Task 2: Frontend API helpers

**Files:**
- Modify: `frontend/src/api.ts`

**Interfaces:**
- Consumes: `GET /ai/files` and `PUT /ai/files` from Task 1
- Produces:
  - `readFile(path: string, rootDirectory: string, token?: string | null): Promise<string>`
  - `writeFile(path: string, rootDirectory: string, content: string, token?: string | null): Promise<void>`

- [ ] **Step 1: Add `readFile` and `writeFile` to `api.ts`**

Append to the end of `frontend/src/api.ts`:

```typescript
export const readFile = async (
  path: string,
  rootDirectory: string,
  token?: string | null,
): Promise<string> => {
  const response = await fetch(
    `${API_BASE_URL}/ai/files?${new URLSearchParams({ path, rootDirectory })}`,
    { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!response.ok) throw new Error(`Failed to read file (${response.status})`);
  return response.text();
};

export const writeFile = async (
  path: string,
  rootDirectory: string,
  content: string,
  token?: string | null,
): Promise<void> => {
  const response = await fetch(
    `${API_BASE_URL}/ai/files?${new URLSearchParams({ path, rootDirectory })}`,
    {
      method: 'PUT',
      headers: {
        'Content-Type': 'text/plain',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: content,
    },
  );
  if (!response.ok) throw new Error(`Failed to write file (${response.status})`);
};
```

- [ ] **Step 2: Verify TypeScript compiles cleanly**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite/frontend" && npx tsc --noEmit 2>&1
```

Expected: no output (no errors).

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/api.ts
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add readFile and writeFile API helpers"
```

---

### Task 3: `!edit` interception in `useConversation.ts`

**Files:**
- Modify: `frontend/src/useConversation.ts`

**Interfaces:**
- Consumes: nothing new from prior tasks (uses existing `rootDirectory` prop, existing `showToast`)
- Produces (added to hook return):
  - `editorFile: { path: string; rootDirectory: string } | null`
  - `closeEditor: () => void`

- [ ] **Step 1: Add `editorFile` state and `closeEditor` to `useConversation.ts`**

In `useConversation.ts`, add after the existing `useState` declarations (around line 24, after `toastTimerRef`):

```typescript
const [editorFile, setEditorFile] = useState<{ path: string; rootDirectory: string } | null>(null);
const closeEditor = useCallback(() => setEditorFile(null), []);
```

- [ ] **Step 2: Intercept `!edit` at the top of `handleSend`, before the user message is added**

In `handleSend`, the very first thing after the early-return guard (`if (!input.trim() || loading) return;`) is building the user message. Insert the `!edit` intercept before `const userMessage`:

```typescript
const handleSend = async (input: string) => {
  if (!input.trim() || loading) return;

  // intercept !edit before adding to conversation history
  const editMatch = input.match(/^!edit\s+(.+)$/i);
  if (editMatch) {
    if (!rootDirectory) {
      showToast('Select a root directory first');
    } else {
      setEditorFile({ path: editMatch[1].trim(), rootDirectory });
    }
    return;
  }

  const userMessage: Message = { role: 'user', content: input };
  // ... rest of existing code unchanged
```

- [ ] **Step 3: Add `editorFile` and `closeEditor` to the hook's return value**

Change the `return` at the bottom of `useConversation`:

```typescript
return { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor };
```

- [ ] **Step 4: Verify TypeScript compiles cleanly**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite/frontend" && npx tsc --noEmit 2>&1
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/useConversation.ts
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: intercept !edit command in useConversation"
```

---

### Task 4: `FileEditorModal` component

**Files:**
- Create: `frontend/src/FileEditorModal.tsx`

**Interfaces:**
- Consumes: `readFile`, `writeFile` from Task 2; `getAccessToken` from `./auth`
- Produces:
  - `export interface FileEditorPlugin { test: (path: string) => boolean; render: (content: string, onChange: (value: string) => void) => React.ReactNode; }`
  - `export function FileEditorModal(props: { path: string; rootDirectory: string; onClose: () => void; plugins?: FileEditorPlugin[] }): JSX.Element`

- [ ] **Step 1: Create `FileEditorModal.tsx`**

Create `frontend/src/FileEditorModal.tsx`:

```typescript
import { useEffect, useState, useCallback } from 'react';
import ReactDOM from 'react-dom';
import { readFile, writeFile } from './api';
import { getAccessToken } from './auth';

export interface FileEditorPlugin {
  test: (path: string) => boolean;
  render: (content: string, onChange: (value: string) => void) => React.ReactNode;
}

interface Props {
  path: string;
  rootDirectory: string;
  onClose: () => void;
  plugins?: FileEditorPlugin[];
}

export function FileEditorModal({ path, rootDirectory, onClose, plugins }: Props) {
  const [content, setContent] = useState('');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onClose]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    getAccessToken()
      .then(token => readFile(path, rootDirectory, token))
      .then(text => { if (!cancelled) { setContent(text); setLoading(false); } })
      .catch((err: Error) => { if (!cancelled) { setLoadError(err.message); setLoading(false); } });
    return () => { cancelled = true; };
  }, [path, rootDirectory]);

  const handleSave = useCallback(async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const token = await getAccessToken();
      await writeFile(path, rootDirectory, content, token);
      onClose();
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }, [path, rootDirectory, content, onClose]);

  const activePlugin = plugins?.find(p => p.test(path));

  return ReactDOM.createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/45"
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div className="bg-white rounded-xl shadow-2xl flex flex-col w-[800px] max-w-[95vw] h-[80vh]">
        <div className="px-5 py-3 border-b border-gray-100 flex justify-between items-center flex-shrink-0">
          <span className="text-sm font-mono text-gray-700 truncate">{path}</span>
          <button
            onClick={onClose}
            aria-label="Close"
            className="text-gray-400 hover:text-gray-600 text-lg leading-none px-1.5 py-0.5 rounded ml-3 flex-shrink-0"
          >
            ×
          </button>
        </div>

        <div className="flex-1 overflow-hidden flex flex-col min-h-0">
          {loading && (
            <div className="flex-1 flex items-center justify-center text-gray-400 text-sm">
              Loading…
            </div>
          )}
          {loadError && (
            <div className="flex-1 flex items-center justify-center text-red-500 text-sm px-6">
              {loadError}
            </div>
          )}
          {!loading && !loadError && (
            activePlugin
              ? (
                <div className="flex-1 overflow-auto">
                  {activePlugin.render(content, setContent)}
                </div>
              )
              : (
                <textarea
                  value={content}
                  onChange={e => setContent(e.target.value)}
                  className="flex-1 resize-none font-mono text-sm p-4 outline-none border-0"
                  spellCheck={false}
                />
              )
          )}
        </div>

        <div className="px-5 py-3 border-t border-gray-100 flex justify-end items-center gap-2 flex-shrink-0">
          {saveError && (
            <span className="text-red-500 text-sm mr-auto">{saveError}</span>
          )}
          <button
            onClick={onClose}
            disabled={saving}
            className="px-4 py-1.5 rounded text-sm text-gray-600 hover:bg-gray-100 disabled:opacity-50"
          >
            Close
          </button>
          <button
            onClick={handleSave}
            disabled={saving || loading || !!loadError}
            className="px-4 py-1.5 rounded text-sm bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
```

- [ ] **Step 2: Verify TypeScript compiles cleanly**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite/frontend" && npx tsc --noEmit 2>&1
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/FileEditorModal.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add FileEditorModal with plugin seam"
```

---

### Task 5: Wire up `App.tsx` and end-to-end manual test

**Files:**
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `editorFile`, `closeEditor` from `useConversation` (Task 3); `FileEditorModal` from Task 4

- [ ] **Step 1: Add import for `FileEditorModal` in `App.tsx`**

In `frontend/src/App.tsx`, add to the import block at the top:

```typescript
import { FileEditorModal } from './FileEditorModal';
```

- [ ] **Step 2: Destructure `editorFile` and `closeEditor` from `useConversation`**

The existing destructure line (around line 67) is:
```typescript
const { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation } =
  useConversation({ model, prompt, rootDirectory, availableTools, disabledTools });
```

Change it to:
```typescript
const { messages, loading, errorToast, historySizeBytes, handleSend, resetConversation, loadConversation, editorFile, closeEditor } =
  useConversation({ model, prompt, rootDirectory, availableTools, disabledTools });
```

- [ ] **Step 3: Render `FileEditorModal` in the JSX**

In the JSX return (inside the `<div className="flex flex-col h-screen ...">` block), add after the `{lightboxSrc && <ImageLightbox .../>}` block:

```tsx
{editorFile && (
  <FileEditorModal
    path={editorFile.path}
    rootDirectory={editorFile.rootDirectory}
    onClose={closeEditor}
  />
)}
```

- [ ] **Step 4: Verify TypeScript compiles cleanly**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite/frontend" && npx tsc --noEmit 2>&1
```

Expected: no output.

- [ ] **Step 5: Build and restart**

Run the build steps from memory (PowerShell, not bash):
```
npx --yes kill-port 8090 5177
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" clean package -DskipTests
powershell.exe -File "C:\Users\Lenovo\start-agent-suite-dev.ps1"
```

- [ ] **Step 6: Manual end-to-end test**

1. Open `http://localhost:5177` and sign in as admin.
2. Select a root directory (e.g. `C:/Users/Lenovo/IdeaProjects/agent-suite`).
3. Type `!edit CLAUDE.md` and press Enter.
4. Verify: modal opens with the file path in the header and the file contents in the textarea.
5. Make a small edit (e.g. add a space at end of a line).
6. Click **Save** — modal should close without error.
7. Type `!edit CLAUDE.md` again — verify the edit persisted.
8. Open the modal again, click **Close** — modal closes, no change.
9. Type `!edit nonexistent.txt` — modal should open and show an inline error (not a blank editor).
10. With no root directory selected, type `!edit foo.txt` — verify the toast "Select a root directory first" appears.
11. Sign in as a non-admin guest and try `!edit CLAUDE.md` via the browser console (`fetch('/ai/files?path=CLAUDE.md&rootDirectory=...', {method:'GET'})`) — expect 403.

- [ ] **Step 7: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: wire FileEditorModal into App"
```

---

## Spec Coverage Check

| Spec requirement | Task |
|---|---|
| `!edit <path>` intercept, before conversation history | Task 3 |
| Admin-only backend endpoints | Task 1 (403 for non-admin) |
| Path relative to rootDirectory | Task 1 (`resolveSafe`) |
| Reject absolute paths | Task 1 (`isAbsolutePath`) |
| Reject `..` traversal | Task 1 (`containsTraversal`) |
| Canonical root-confinement check | Task 1 (`toRealPath` + `startsWith`) |
| `rootDirectory` must be in `ALLOWED` | Task 1 (`validateAccess`) |
| Atomic write | Task 1 (temp file + rename) |
| `readFile` / `writeFile` in `api.ts` | Task 2 |
| Modal portal, header with path, close button | Task 4 |
| Textarea, monospace, no formatting | Task 4 |
| Save button — writes then closes | Task 4 |
| Close button — closes immediately | Task 4 |
| Escape key closes | Task 4 |
| Backdrop click closes | Task 4 |
| Inline error on read failure | Task 4 |
| Inline error on write failure | Task 4 |
| `FileEditorPlugin` interface exported | Task 4 |
| First matching plugin replaces textarea | Task 4 |
| Toast when no rootDirectory | Task 3 |
| Minimal `App.tsx` changes | Task 5 (3 additions only) |
