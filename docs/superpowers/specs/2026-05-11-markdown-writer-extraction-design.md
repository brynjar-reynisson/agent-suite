# MarkDownWriter Extraction — Design Spec

**Date:** 2026-05-11
**Branch:** feature/tool-selection-per-agent

## Problem

`UnixTools` currently hosts `newMarkDownFile`, a method with a distinct responsibility (writing spec/plan markdown files) that doesn't belong alongside filesystem inspection tools (`ls`, `cat`, `grep`). It should be its own tool group so agents can be given write access independently of read access.

## Goal

Extract `newMarkDownFile` from `UnixTools` into a dedicated `MarkDownWriter` class, expose it as the `"md-writer"` tool group in `buildToolInstances`, and enable it for `Implementation-planner` and `Specification-writer` but not `Code-request classifier`.

## Scope

**In scope:**
- Create `MarkDownWriter.java` in `com.example.agentsuite.tools`
- Move `newMarkDownFile` verbatim from `UnixTools` to `MarkDownWriter`
- Remove `newMarkDownFile` from `UnixTools`
- Add `case "md-writer"` to `buildToolInstances` in `AiController`, gated by non-empty `rootDirectory`
- Update `PROMPT_BANK` in `App.tsx`
- Unit tests for `newMarkDownFile` behaviour in a new `MarkDownWriterTest`
- Unit tests for `buildToolInstances` covering the new `"md-writer"` group

**Out of scope:**
- Changing `newMarkDownFile` logic or signature
- Any interface or base-class abstraction between `UnixTools` and `MarkDownWriter`
- Frontend UI changes

## Design

### `MarkDownWriter.java`

New class in `com.example.agentsuite.tools`. Mirrors `UnixTools` structurally:

```java
public class MarkDownWriter {
    private static final Logger log = LoggerFactory.getLogger(MarkDownWriter.class);
    public final Path root;

    public MarkDownWriter(String rootDirectory) {
        root = Paths.get(rootDirectory);
        if (!root.toFile().exists() || !root.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                "Root directory does not exist or is not a directory: " + rootDirectory);
        }
    }

    @Tool("Create new markdown spec or plan file for a feature with the given content. ...")
    public String newMarkDownFile(...) { /* moved verbatim */ }
}
```

The `@Tool` annotation, method signature, and body are moved unchanged from `UnixTools`.

### `UnixTools.java`

Remove `newMarkDownFile` and its private helper usage (the method is self-contained). Imports `StandardCharsets` and `java.time.LocalDate` become unused and are removed.

### `AiController.java` — `buildToolInstances`

```java
case "md-writer" -> {
    if (!rootDirectory.isEmpty()) instances.add(new MarkDownWriter(rootDirectory));
}
```

Add `import com.example.agentsuite.tools.MarkDownWriter;`.

### `App.tsx` — `PROMPT_BANK`

| Prompt | tools |
|--------|-------|
| Code-request classifier | `['unix']` |
| Implementation-planner | `['unix', 'md-writer']` |
| Specification-writer | `['unix', 'md-writer']` |

## Tool Group Identifiers

| Identifier | Class | Requires rootDirectory |
|------------|-------|------------------------|
| `unix` | `UnixTools` | yes |
| `md-writer` | `MarkDownWriter` | yes |

## Testing

### `MarkDownWriterTest` (new file)

Tests for `newMarkDownFile` covering:
- Happy path: spec document created at correct path with correct content
- Happy path: plan document created at correct path
- Invalid document type returns error string
- File already exists returns error string
- Path traversal in feature name is sanitised (special chars replaced with `-`)

### `AiControllerTest` additions

New `buildToolInstances` tests:
- `"md-writer"` with valid root directory → returns `MarkDownWriter` instance
- `"md-writer"` with empty root directory → returns empty array
- `"unix,md-writer"` with valid root directory → returns two instances
- `"md-writer,unknown"` → only `MarkDownWriter` added (unknown silently ignored)

### Regression

Existing `UnixToolsTest` (if any) and `AiControllerTest` tests must remain green. `newMarkDownFile` is no longer on `UnixTools` — any test calling it there would need to move (there are none currently).
