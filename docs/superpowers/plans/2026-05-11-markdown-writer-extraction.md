# MarkDownWriter Extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `newMarkDownFile` from `UnixTools` into a dedicated `MarkDownWriter` class, register it as the `"md-writer"` tool group, and enable it for `Implementation-planner` and `Specification-writer` prompts only.

**Architecture:** `MarkDownWriter` is a standalone class in the tools package with the same constructor pattern as `UnixTools` (takes `String rootDirectory`). `buildToolInstances` in `AiController` gains a `"md-writer"` case gated identically to `"unix"`. The PROMPT_BANK in `App.tsx` is updated to reflect which agents get which tool groups.

**Tech Stack:** Java 21, Spring Boot 3.5, LangChain4j 0.36.2, JUnit 5, AssertJ, React 19 + TypeScript

---

### Task 1: Create `MarkDownWriter` with unit tests (TDD)

**Files:**
- Create: `src/main/java/com/example/agentsuite/tools/MarkDownWriter.java`
- Create: `src/test/java/com/example/agentsuite/tools/MarkDownWriterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/tools/MarkDownWriterTest.java`:

```java
package com.example.agentsuite.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MarkDownWriterTest {

    @TempDir
    Path tempDir;

    private MarkDownWriter writer;

    @BeforeEach
    void setUp() {
        writer = new MarkDownWriter(tempDir.toString());
    }

    @Test
    void newMarkDownFile_specType_createsFileInSpecsDirectory() {
        String result = writer.newMarkDownFile("spec", "my-feature", "# Content");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(tempDir.resolve("docs/specs").toFile().listFiles()).hasSize(1);
    }

    @Test
    void newMarkDownFile_planType_createsFileInPlansDirectory() {
        String result = writer.newMarkDownFile("plan", "my-feature", "# Plan");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(tempDir.resolve("docs/plans").toFile().listFiles()).hasSize(1);
    }

    @Test
    void newMarkDownFile_invalidDocumentType_returnsError() {
        String result = writer.newMarkDownFile("invalid", "my-feature", "content");
        assertThat(result).contains("Error: Unknown document type");
    }

    @Test
    void newMarkDownFile_fileAlreadyExists_returnsError() {
        writer.newMarkDownFile("spec", "my-feature", "first");
        String result = writer.newMarkDownFile("spec", "my-feature", "second");
        assertThat(result).contains("Error: File already exists");
    }

    @Test
    void newMarkDownFile_specialCharsInFeatureName_sanitisesToKebabCase() {
        String result = writer.newMarkDownFile("spec", "My Feature! With Spaces", "content");
        assertThat(result).startsWith("Successfully wrote");
        assertThat(result).contains("my-feature-with-spaces");
    }
}
```

- [ ] **Step 2: Run tests — confirm they fail with compilation error**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite; .\mvnw.cmd test -Dtest=MarkDownWriterTest" 2>&1 | Select-String -Pattern "Tests run|BUILD|ERROR|cannot find"
```

Expected: compilation error — `MarkDownWriter` does not exist yet.

- [ ] **Step 3: Create `MarkDownWriter.java`**

Create `src/main/java/com/example/agentsuite/tools/MarkDownWriter.java`:

```java
package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    @Tool("Create new markdown spec or plan file for a feature with the given content. Actual file name will be auto-generated and returned, based on the document type and current timestamp.")
    public String newMarkDownFile(
            @P("Document type, spec or plan") String documentType,
            @P("Feature name") String featureName,
            @P("The content to write") String content
    ) {
        Path docs = root.resolve("docs");
        Path mdFolder;
        if (documentType.equalsIgnoreCase("spec")) {
            mdFolder = docs.resolve("specs");
        } else if (documentType.equalsIgnoreCase("plan")) {
            mdFolder = docs.resolve("plans");
        } else {
            return "Error: Unknown document type: " + documentType + ". Use 'spec' or 'plan'.";
        }

        try {
            Files.createDirectories(mdFolder);
        } catch (Exception e) {
            return "Error: Could not create directory for markdown files: " + e.getMessage();
        }

        String safeFeatureName = featureName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String fileName = String.format("%s-%s.md", java.time.LocalDate.now(), safeFeatureName);
        Path target = mdFolder.resolve(fileName);
        if (!target.startsWith(root) || fileName.contains("..")) {
            return "Error: Path escapes root directory.";
        }
        if (target.toFile().exists()) {
            return "Error: File already exists: " + target;
        }

        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return "Successfully wrote " + target + " (" + content.length() + " bytes)";
        } catch (Exception e) {
            return "Error: Could not write file " + target + ", " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4: Run tests — confirm they pass**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite; .\mvnw.cmd test -Dtest=MarkDownWriterTest" 2>&1 | Select-String -Pattern "Tests run|BUILD"
```

Expected: `Tests run: 5, Failures: 0, Errors: 0` — `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/tools/MarkDownWriter.java src/test/java/com/example/agentsuite/tools/MarkDownWriterTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add MarkDownWriter tool class with unit tests"
```

---

### Task 2: Remove `newMarkDownFile` from `UnixTools`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/UnixTools.java`

- [ ] **Step 1: Remove `newMarkDownFile` and unused import from `UnixTools.java`**

Delete the entire `newMarkDownFile` method (lines 115–156 in the current file — the method with the `@Tool("Create new markdown spec or plan file...")` annotation through its closing `}`).

Also remove the now-unused import on line 9:
```java
import java.nio.charset.StandardCharsets;
```

All other imports (`Files`, `Path`, `Paths`, etc.) are still used by `ls`, `cat`, and `grep` — leave them.

- [ ] **Step 2: Run the full test suite — confirm nothing broke**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite; .\mvnw.cmd test" 2>&1 | Select-String -Pattern "Tests run:|BUILD"
```

Expected: all previously passing tests still pass, `BUILD SUCCESS`. The total count will be the same as before (the 5 new `MarkDownWriterTest` tests are already included).

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/tools/UnixTools.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "refactor: remove newMarkDownFile from UnixTools (moved to MarkDownWriter)"
```

---

### Task 3: Add `"md-writer"` to `buildToolInstances` with tests (TDD)

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Add these imports to `AiControllerTest.java` if not already present:
```java
import com.example.agentsuite.tools.MarkDownWriter;
```

Add these tests inside the `AiControllerTest` class:

```java
@Test
void buildToolInstances_mdWriterGroup_withRootDirectory_returnsMarkDownWriter() {
    Object[] result = AiController.buildToolInstances("md-writer", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
}

@Test
void buildToolInstances_mdWriterGroup_noRootDirectory_returnsEmptyArray() {
    Object[] result = AiController.buildToolInstances("md-writer", "");
    assertThat(result).isEmpty();
}

@Test
void buildToolInstances_unixAndMdWriter_withRootDirectory_returnsBothInstances() {
    Object[] result = AiController.buildToolInstances("unix,md-writer", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).hasSize(2);
    assertThat(result[0]).isInstanceOf(UnixTools.class);
    assertThat(result[1]).isInstanceOf(MarkDownWriter.class);
}

@Test
void buildToolInstances_mdWriterAndUnknown_onlyMarkDownWriterAdded() {
    Object[] result = AiController.buildToolInstances("md-writer,unknown", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(MarkDownWriter.class);
}
```

- [ ] **Step 2: Run tests — confirm they fail**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite; .\mvnw.cmd test -Dtest=AiControllerTest" 2>&1 | Select-String -Pattern "Tests run|BUILD|FAIL"
```

Expected: 4 failures — `buildToolInstances` returns empty for `"md-writer"` (case not implemented yet).

- [ ] **Step 3: Add `"md-writer"` case to `buildToolInstances` in `AiController.java`**

Add the import at the top of `AiController.java` (with the other tool imports):
```java
import com.example.agentsuite.tools.MarkDownWriter;
```

In `buildToolInstances`, extend the switch to add the new case after `"unix"`:

```java
switch (g) {
    case "unix" -> {
        if (!rootDirectory.isEmpty()) instances.add(new UnixTools(rootDirectory));
    }
    case "md-writer" -> {
        if (!rootDirectory.isEmpty()) instances.add(new MarkDownWriter(rootDirectory));
    }
}
```

- [ ] **Step 4: Run tests — confirm they pass**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite; .\mvnw.cmd test -Dtest=AiControllerTest" 2>&1 | Select-String -Pattern "Tests run|BUILD"
```

Expected: `Tests run: 26, Failures: 0, Errors: 0` — `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/AiController.java src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add md-writer tool group to buildToolInstances"
```

---

### Task 4: Update `PROMPT_BANK` in `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Update `PROMPT_BANK` entries**

In `frontend/src/App.tsx`, replace the `PROMPT_BANK` constant with:

```typescript
const PROMPT_BANK = [
  {
    name: 'Code-request classifier',
    text: 'You are a coding assistant and will use the available tools on the selected codebase to classify the coding requests you receive. Respond in json format 1) intent, which shall be either bug-fix, enhancement, new-feature, architecture-change or unknown, 2) confidence in the classification (percentages)',
    tools: ['unix'],
  },
  {
    name: 'Implementation-planner',
    text: 'Your job is to read a named specification file and create a step-by-step implementation plan. The plan should be broken down into small, actionable tasks that can be easily assigned to developers. The plan should also include any necessary technical details, such as which files or modules will need to be modified.',
    tools: ['unix', 'md-writer'],
  },
  {
    name: 'Specification-writer',
    text: 'Your job is to create a new specification file that takes a user request and defines the business requirement, the user problem and the success criteria. Specify what is in scope and out of scope. This is about the what and why, not how it will be implemented.',
    tools: ['unix', 'md-writer'],
  },
];
```

- [ ] **Step 2: Verify TypeScript compiles cleanly**

```bash
powershell -Command "Set-Location C:/Users/Lenovo/IdeaProjects/agent-suite/frontend; npx tsc --noEmit"
```

Expected: no output (zero errors).

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: enable md-writer tool for Implementation-planner and Specification-writer"
```
