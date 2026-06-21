# Exec Command Feature — Design Spec

**Date:** 2026-06-21
**Branch:** feature/exec-command
**Status:** Approved

## Overview

Admin users can type `!!<command>` in the chat footer to execute an arbitrary shell command with the selected root directory as the working directory. Output streams progressively into the chat thread as a preformatted code block. Requires a root directory to be selected.

---

## Backend — `ExecController`

New Spring `@RestController`.

### Endpoint

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/ai/exec` | Execute a shell command; returns `text/event-stream` |

**Parameters** (form-encoded, consistent with `/ai/chat`):

| Param | Type | Description |
|-------|------|-------------|
| `command` | String | The full command string to execute |
| `rootDirectory` | String | Must be in `RootDirectories.ALLOWED` and non-empty |

### Auth & Validation

1. **Admin-only** — non-admins receive 403 (`UserResolverFilter.ATTR_IS_ADMIN`).
2. `rootDirectory` must be non-empty, else 400.
3. `rootDirectory` must be in `RootDirectories.ALLOWED`, else 400.

No path traversal checks needed — `rootDirectory` is used as CWD, not resolved against user input.

### Execution

- Shell wrapper: `["cmd", "/c", command]` on Windows, `["sh", "-c", command]` on Unix. Detected via `System.getProperty("os.name")`.
- `ProcessBuilder.redirectErrorStream(true)` merges stdout and stderr into one stream.
- CWD set to `Path.of(rootDirectory).toFile()`.
- Output read line by line via `BufferedReader`; each line emitted as one SSE event.
- Process timeout: 10 minutes (`process.waitFor(10, TimeUnit.MINUTES)`). On timeout: destroy process, emit `error` event.
- `SseEmitter` constructed with no idle timeout (0 = infinite). `onCompletion` callback kills the process if the browser disconnects before the process finishes.

### SSE Events

| Event | Data | When |
|-------|------|------|
| `output` | One line of stdout/stderr | For each output line |
| `done` | Exit code as string (e.g. `"0"`) | Process completed normally |
| `error` | Error message string | Timeout, I/O error, or process couldn't start |

---

## Frontend API — `api.ts`

New export `execShellStream`:

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
): Promise<void>
```

Uses `fetchEventSource` with `POST /ai/exec`, `application/x-www-form-urlencoded` body. On `output`: calls `onOutput`. On `done`: calls `onDone`, aborts the controller. On `error`: calls `onError`.

---

## `!!` Interception — `useConversation.ts`

Added in `handleSend`, after the `!edit` check and before the generic `!` handler:

```
if input matches /^!!(.+)$/:
  if rootDirectory is empty → showToast('Select a root directory first'); return
  command = trimmed capture group
  add user message: { role: 'user', content: input }
  add placeholder ai message: { role: 'ai', content: '```\n```' }
  setLoading(true)
  accumulator = ''
  call execShellStream:
    onOutput(line):
      accumulator += line + '\n'
      replace last message content with '```\n' + accumulator + '```'
    onDone(exitCode):
      if exitCode !== 0: accumulator += '[exit ' + exitCode + ']\n'
      replace last message content with '```\n' + accumulator + '```'
      setLoading(false)
    onError(message):
      replace last message content with 'Error: ' + message
      setLoading(false)
  return
```

The placeholder message content is always a valid, closed markdown code block on every update, so `ReactMarkdown` renders correctly throughout streaming.

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| `!!` with no root directory | Toast: "Select a root directory first" |
| Non-admin user | 403 — frontend shows error in message |
| Invalid/empty root directory | 400 — frontend shows error in message |
| Process timeout (10 min) | `error` event; displayed as `Error: <message>` |
| Process start failure | `error` event; displayed as `Error: <message>` |
| Non-zero exit code | Exit code appended: `[exit N]` after output |
| Browser disconnect | `onCompletion` kills the process |

---

## Tests — `ExecControllerTest.java`

`@WebMvcTest` slice, same pattern as `FileControllerTest`. Validates the rejection layer only (no real processes spawned):

| Test | Expected |
|------|----------|
| Admin + valid root → POST `/ai/exec` | 200, `Content-Type: text/event-stream` |
| Non-admin | 403 |
| Empty `rootDirectory` | 400 |
| `rootDirectory` not in `ALLOWED` | 400 |
| Missing `command` param | 400 |

---

## Security

- Admin-only enforcement is server-side.
- `rootDirectory` must be in the operator-controlled allowlist — client cannot supply an arbitrary CWD.
- The command itself is unrestricted (intentional: this is an admin power-user tool).
- No child process can escape the CWD restriction inherent in `ProcessBuilder` (though child processes may themselves `cd` elsewhere — accepted for this admin tool).

---

## Out of Scope (this iteration)

- Abort/cancel a running command mid-stream
- Configurable timeout (fixed 10 minutes)
- Concurrent exec sessions
- stdin input to the running process
- Non-shell execution (bypassing the shell wrapper)
