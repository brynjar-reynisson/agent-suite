# Git Agent Tool Design

**Date:** 2026-06-23  
**Status:** Approved

## Overview

Add `GitTools.java` — a LangChain4j `@Tool`-annotated class that exposes `gitAdd` and `gitCommit` to the AI agent. It delegates to the existing `Git.java` utility (which already powers `!git` user commands). Tools are bundled into the existing `"unix"` tool group. A system prompt directive is injected automatically when git tools are active, instructing the agent to always stage and commit files it creates or modifies.

## Scope

- `gitAdd(relativePath)` and `gitCommit(message)` only.
- Push, pull, branch operations remain user-only via `!git` commands.
- Admin + non-empty `rootDirectory` required (inherited from the `"unix"` group).

## Components

### `GitTools.java` (new)

Located in `com.example.agentsuite.tools`. Holds a `Git` instance constructed from `rootDirectory`.

```java
@Tool("Add a file to the git staging area. Always call this after creating or modifying a file.")
public String gitAdd(@P("Relative path to the file") String relativePath) { ... }

@Tool("Commit all staged changes with a message. Always call after gitAdd.")
public String gitCommit(@P("Commit message") String message) { ... }
```

Both methods delegate directly to `Git.add()` and `Git.commit()`. No new process-running logic.

### `AiController` — tool group wiring

In the `"unix"` case of `buildTools()`, add `new GitTools(rootDirectory)` alongside `new UnixTools(rootDirectory)`. Both share the existing `!rootDirectory.isEmpty()` guard.

```java
case "unix" -> {
    if (!rootDirectory.isEmpty()) {
        instances.add(new UnixTools(rootDirectory));
        instances.add(new GitTools(rootDirectory));
    }
}
```

### `AiController` — system prompt directive

After the tools list is assembled, check if any element is a `GitTools` instance. If so, and if the system prompt does not already contain the directive (checked via `String.contains()`), append:

> "After creating or modifying any file, always call `gitAdd` with the file path and then `gitCommit` with a descriptive commit message."

The `contains()` guard prevents duplication when the system prompt is pre-populated or carried over from a prior call. Extract the directive as a named constant (`GIT_COMMIT_DIRECTIVE`) for the guard and the append to stay in sync.

## Error Handling

`Git.add()` and `Git.commit()` already return `"Error: ..."` strings on non-zero exit codes. `GitTools` passes these through — no additional wrapping needed. The agent sees the error text and can decide to retry or report.

## Testing

- **`GitToolsTest.java`** — integration tests for `gitAdd` and `gitCommit` using `@TempDir` + a real `git` subprocess (same pattern as `GitTest.java`). Covers success and error paths for both methods. Requires `git` on PATH.
- **`AiControllerTest.java`** — verify that when admin + rootDirectory are set, the tool list contains a `GitTools` instance and the system prompt directive is injected. Verify directive is not duplicated when already present.

## Out of Scope

- Frontend changes — no new tool group name means no UI toggles needed.
- Push/pull/branch as agent tools — user retains these via `!git` commands.
- Rollback or diff tools.
