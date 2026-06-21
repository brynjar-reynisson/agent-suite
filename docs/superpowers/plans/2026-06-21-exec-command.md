# Exec Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let admin users run arbitrary shell commands via `!!<command>` in the chat footer, with output streaming progressively into the chat thread.

**Architecture:** New `POST /ai/exec` SSE endpoint in Spring Boot validates admin + rootDirectory, then runs the command via `ProcessBuilder` (shell-wrapped for cross-platform), emitting one `output` SSE event per line. Frontend `execShellStream` mirrors `chatStream`; the `!!` intercept in `useConversation.ts` streams output into a running code-block message that is always a valid closed markdown fence on every update.

**Tech Stack:** Spring Boot 3.5 / Java 21 (`SseEmitter`, virtual threads), `@microsoft/fetch-event-source` (already in use), React 19.

## Global Constraints

- Java 21. Spring Boot 3.5. `SseEmitter(0L)` for no idle timeout.
- Shell wrapper: `["cmd", "/c", command]` on Windows (`os.name` contains `"win"`), `["sh", "-c", command]` on Unix.
- Process timeout: 10 minutes (`process.waitFor(10, TimeUnit.MINUTES)`).
- SSE event names: `output` (one line of stdout/stderr), `done` (exit code as string), `error` (message string).
- Admin check: `Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN))`.
- `rootDirectory` must be non-empty and in `RootDirectories.ALLOWED`; empty → 400, not-in-list → 400, non-admin → 403.
- `@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})` on the controller.
- Test JWT secret: `"test-secret-padded-to-at-least-32-characters"`, issuer: `"http://127.0.0.1:54321/auth/v1"`.
- Git: use `git -C "C:/Users/Lenovo/IdeaProjects/agent-suite"` for all git commands. Feature branch: `feature/exec-command`.
- `git add` all new source files before committing.

---

### Task 1: Backend `ExecController`

**Files:**
- Create: `src/main/java/com/example/agentsuite/controller/ExecController.java`
- Create: `src/test/java/com/example/agentsuite/controller/ExecControllerTest.java`

**Interfaces:**
- Produces: `POST /ai/exec` accepting form params `command` (String) and `rootDirectory` (String), returns `text/event-stream`. Events: `output`/line, `done`/exitCode, `error`/message.

- [ ] **Step 1: Create the feature branch**

```powershell
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" checkout -b feature/exec-command
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/example/agentsuite/controller/ExecControllerTest.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.service.AuthorizationService;
import com.example.agentsuite.service.SuiteUserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExecController.class)
class ExecControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SuiteUserService suiteUserService;
    @MockBean AuthorizationService authorizationService;

    @TempDir static Path tempDir;

    private static final String SECRET = "test-secret-padded-to-at-least-32-characters";
    private static final String ISSUER  = "http://127.0.0.1:54321/auth/v1";

    @BeforeAll
    static void setUp() {
        RootDirectories.ALLOWED.add(tempDir.toString());
    }

    @AfterAll
    static void tearDown() {
        RootDirectories.ALLOWED.remove(tempDir.toString());
    }

    private String makeToken(boolean isAdmin) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes());
        return Jwts.builder()
                .issuer(ISSUER)
                .subject("user-123")
                .claim("is_admin", isAdmin)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();
    }

    @Test
    void admin_validRoot_startsAsyncStream() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeToken(true)))
                .andExpect(request().asyncStarted());
    }

    @Test
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeToken(false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyRootDirectory_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", "")
                .header("Authorization", "Bearer " + makeToken(true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRootDirectory_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", "/not/in/allowed")
                .header("Authorization", "Bearer " + makeToken(true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingCommand_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeToken(true)))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: Run tests — expect compilation failure (class not found)**

```powershell
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" test -Dtest=ExecControllerTest
```

Expected: build error — `ExecController` does not exist yet.

- [ ] **Step 4: Create `ExecController.java`**

Create `src/main/java/com/example/agentsuite/controller/ExecController.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.filter.UserResolverFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class ExecController {

    @PostMapping(value = "/ai/exec", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter exec(
            @RequestParam String command,
            @RequestParam String rootDirectory,
            HttpServletRequest request) {

        validateAccess(rootDirectory, request);

        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Process> processRef = new AtomicReference<>();

        Runnable killProcess = () -> {
            Process p = processRef.get();
            if (p != null) p.destroyForcibly();
        };
        emitter.onCompletion(killProcess);
        emitter.onTimeout(killProcess);

        Thread.ofVirtual().start(() -> {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                List<String> cmd = isWindows
                        ? List.of("cmd", "/c", command)
                        : List.of("sh", "-c", command);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(rootDirectory));
                pb.redirectErrorStream(true);

                Process process = pb.start();
                processRef.set(process);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.send(SseEmitter.event().name("output").data(line));
                    }
                }

                boolean finished = process.waitFor(10, TimeUnit.MINUTES);
                if (!finished) {
                    process.destroyForcibly();
                    emitter.send(SseEmitter.event().name("error").data("Command timed out after 10 minutes"));
                } else {
                    emitter.send(SseEmitter.event().name("done").data(String.valueOf(process.exitValue())));
                }
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(e.getMessage() != null ? e.getMessage() : "Unknown error"));
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        });

        return emitter;
    }

    private void validateAccess(String rootDirectory, HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        if (!isAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (rootDirectory == null || rootDirectory.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Root directory is required");
        if (!RootDirectories.ALLOWED.contains(rootDirectory))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid root directory");
    }
}
```

- [ ] **Step 5: Run tests — expect 5/5 pass**

```powershell
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" test -Dtest=ExecControllerTest
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Run full test suite — confirm no regressions**

```powershell
& "C:\Users\Lenovo\IdeaProjects\agent-suite\mvnw.cmd" -f "C:\Users\Lenovo\IdeaProjects\agent-suite\pom.xml" test -q 2>&1 | Select-String "Tests run|FAILED|ERROR" | Select-Object -Last 5
```

Expected: same 5 pre-existing failures in `ImageContentHandlerTest` / `McpToolBridgeTest`, no new failures.

- [ ] **Step 7: Commit**

```powershell
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/ExecController.java src/test/java/com/example/agentsuite/controller/ExecControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add ExecController for admin shell command execution"
```

---

### Task 2: Frontend API — `execShellStream`

**Files:**
- Modify: `frontend/src/api.ts` — append `ExecCallbacks` interface and `execShellStream` function

**Interfaces:**
- Consumes: `fetchEventSource` (already imported at top of `api.ts`), `API_BASE_URL` constant (line 3 of `api.ts`)
- Produces:
  ```typescript
  export interface ExecCallbacks {
    onOutput: (line: string) => void;
    onDone: (exitCode: number) => void;
    onError?: (message: string) => void;
  }
  export const execShellStream: (
    command: string,
    rootDirectory: string,
    callbacks: ExecCallbacks,
    token?: string | null,
  ) => Promise<void>
  ```

- [ ] **Step 1: Append to `frontend/src/api.ts`**

Add the following at the end of the file:

```typescript
export interface ExecCallbacks {
  onOutput: (line: string) => void;
  onDone: (exitCode: number) => void;
  onError?: (message: string) => void;
}

export const execShellStream = async (
  command: string,
  rootDirectory: string,
  callbacks: ExecCallbacks,
  token?: string | null,
): Promise<void> => {
  const controller = new AbortController();
  await fetchEventSource(`${API_BASE_URL}/ai/exec`, {
    method: 'POST',
    signal: controller.signal,
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: new URLSearchParams({ command, rootDirectory }),
    onmessage(ev) {
      if (ev.event === 'output') callbacks.onOutput(ev.data);
      if (ev.event === 'done') {
        callbacks.onDone(parseInt(ev.data, 10));
        controller.abort();
      }
      if (ev.event === 'error') callbacks.onError?.(ev.data);
    },
    onclose() {
      // normal close after 'done' — nothing to do
    },
    onerror(err) {
      throw err;
    },
  });
};
```

- [ ] **Step 2: Verify TypeScript compiles**

```powershell
cd "C:\Users\Lenovo\IdeaProjects\agent-suite\frontend"; npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```powershell
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/api.ts
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add execShellStream API helper"
```

---

### Task 3: Frontend interception — `useConversation.ts` + docs

**Files:**
- Modify: `frontend/src/useConversation.ts` — add `!!` intercept
- Modify: `docs/dev/api.md` — document `/ai/exec`

**Interfaces:**
- Consumes (from Task 2):
  ```typescript
  import { execShellStream, type ExecCallbacks } from './api';
  ```

- [ ] **Step 1: Add `execShellStream` to the import in `useConversation.ts`**

Find the existing import at the top of `frontend/src/useConversation.ts`:

```typescript
import {
  chatStream, compactConversation, compactMergeConversation, execTool,
  getConversationDetail, type ConversationDetail, type ConversationSummary, type Message,
} from './api';
```

Replace it with:

```typescript
import {
  chatStream, compactConversation, compactMergeConversation, execTool, execShellStream,
  getConversationDetail, type ConversationDetail, type ConversationSummary, type Message,
} from './api';
```

- [ ] **Step 2: Add the `!!` intercept block in `handleSend`**

In `handleSend`, find the end of the `!edit` intercept block (the closing brace of `if (editMatch) { ... }`). Insert the following immediately after it, before the `const userMessage` line:

```typescript
    // intercept !! for direct shell execution
    const execMatch = input.match(/^!!(.+)$/);
    if (execMatch) {
      if (!rootDirectory) {
        showToast('Select a root directory first');
        return;
      }
      const command = execMatch[1].trim();
      setMessages(prev => [
        ...prev,
        { role: 'user', content: input },
        { role: 'ai', content: '```\n```' },
      ]);
      setLoading(true);
      let accumulated = '';
      try {
        const token = await getAccessToken();
        await execShellStream(command, rootDirectory, {
          onOutput: (line) => {
            accumulated += line + '\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
          },
          onDone: (exitCode) => {
            if (exitCode !== 0) accumulated += '[exit ' + exitCode + ']\n';
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: '```\n' + accumulated + '```' };
              return msgs;
            });
            setLoading(false);
          },
          onError: (message) => {
            setMessages(prev => {
              const msgs = [...prev];
              msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + message };
              return msgs;
            });
            setLoading(false);
          },
        }, token);
      } catch (error: unknown) {
        const msg = error instanceof Error ? error.message : 'Exec failed';
        setMessages(prev => {
          const msgs = [...prev];
          msgs[msgs.length - 1] = { role: 'ai', content: 'Error: ' + msg };
          return msgs;
        });
        setLoading(false);
      }
      return;
    }
```

The resulting order inside `handleSend` must be:
1. `if (!input.trim() || loading) return;`
2. `!edit` intercept → `return`
3. `!!` intercept (new) → `return`
4. `const userMessage = ...`
5. meta messages, `setMessages`, `setLoading(true)`
6. generic `!` handler (execTool)
7. `/compact`, `/compact-merge`
8. `chatStream`

- [ ] **Step 3: Verify TypeScript compiles**

```powershell
cd "C:\Users\Lenovo\IdeaProjects\agent-suite\frontend"; npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Update `docs/dev/api.md`**

Add the following entry to the endpoint listing in `docs/dev/api.md`, after the `/ai/config/mcp-tools` entry:

```
POST /ai/exec
  ?command=<shell command>        (required; passed to cmd /c on Windows, sh -c on Unix)
  ?rootDirectory=<path>           (required; must be non-empty and in allowlist; used as CWD)
  Admin-only. Executes a shell command with rootDirectory as CWD. Returns text/event-stream.
  Events: output/<line>, done/<exit code>, error/<message>. Non-admins get 403.
  Empty or invalid rootDirectory gets 400. Process timeout: 10 minutes.
```

- [ ] **Step 5: Commit**

```powershell
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/useConversation.ts docs/dev/api.md
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: intercept !! command for streaming shell execution"
```
