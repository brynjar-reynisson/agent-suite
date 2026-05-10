# Git Tool Commands — Design Spec

**Date:** 2026-05-10
**Branch:** feature/prompt-bank
**Scope:** Backend only (`AiController.java`, `Git.java`)

---

## Goal

Expose the existing `Git` class methods through the `!command` mechanism so users can run `!git status`, `!git add`, `!git commit`, `!git push`, `!git newBranch`, and `!git checkoutBranch` from the chat window and see the output inline.

---

## How `!command` Works Today

The frontend strips the `!` prefix and calls `GET /ai/tools?command=<text>&rootDirectory=<dir>`. `AiController.executeTool()` parses the command into tokens and routes on the first token (`ls`, `cat`, `grep`). The result is returned as plain text and rendered in the chat window. No frontend changes are needed — the routing gap is entirely in the backend switch.

---

## Changes Required

| File | Change |
|---|---|
| `AiController.java` | Add `case "git"` branch; update unknown-command error message |
| `Git.java` | Fix `status()` bug: `runner.run()` is called twice |

No new files. No frontend changes.

---

## Routing Logic (`case "git"` in `AiController.executeTool()`)

```
tokens[0] = "git"
tokens[1] = subcommand
tokens[2..] = arguments
```

| Subcommand | Minimum tokens | Calls | Commit-message note |
|---|---|---|---|
| `status` | 2 | `git.status()` | — |
| `add` | 3 | `git.add(tokens[2])` | — |
| `commit` | 3 | `git.commit(tokens[2..].join(" "))` | All tokens from index 2 onward are joined with a space |
| `push` | 2 | `git.push()` | — |
| `newBranch` | 3 | `git.newBranch(tokens[2])` | — |
| `checkoutBranch` | 3 | `git.checkoutBranch(tokens[2])` | — |

Missing subcommand → `"Error: git requires a subcommand: status, add, commit, push, newBranch, checkoutBranch"`

Missing required argument → descriptive error per subcommand, e.g. `"Error: add requires a file path"`.

The existing empty-rootDirectory guard (`"Error: Select a root directory to use this command."`) already fires before the routing switch and covers git commands too.

The `default` branch error message is updated from `"Use: ls, cat, or grep"` to `"Use: ls, cat, grep, or git"`.

---

## Bug Fix — `Git.status()`

Current code calls `runner.run()` twice: once to check the exit code, and a second time (on a new process) to retrieve stdout. The fix: capture the `Output` from the single `run()` call and use it for both the exit-code check and the return value.

```java
// Before (buggy — runs process twice)
ProcessRunner runner = new ProcessRunner(...);
ProcessRunner.Output output = runner.run();
if (output.exitCode() != 0) return "Error: " + output.stdErr();
return runner.run().stdOut();   // ← second run

// After
ProcessRunner.Output output = new ProcessRunner(...).run();
if (output.exitCode() != 0) return "Error: " + output.stdErr();
return output.stdOut();
```

---

## Error Handling Summary

All `Git` methods already return `"Error: " + stdErr` on non-zero exit codes. The controller layer adds argument-count guards before delegating. No exceptions are expected to surface to the user — `ProcessRunner` catches them internally and returns exit code `-1` with the exception message as stderr.

---

## Out of Scope

- Exposing git commands as AI-callable `@Tool` methods (deferred).
- Any other git subcommands beyond the six listed.
- Frontend UI changes.
