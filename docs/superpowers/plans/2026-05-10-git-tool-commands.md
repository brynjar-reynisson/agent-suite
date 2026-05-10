# Git Tool Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the existing `Git` class into the `!command` system so users can run `!git status`, `!git add`, `!git commit`, `!git push`, `!git newBranch`, and `!git checkoutBranch` from the chat window and see the output inline.

**Architecture:** Two backend-only changes. (1) Fix a double-process-execution bug in `Git.status()` and add `GitTest` covering all six methods against a temporary git repo. (2) Add `case "git"` to the switch in `AiController.executeTool()` with per-subcommand argument validation, plus controller-level tests for routing and error cases.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, MockMvc

---

## File Map

| File | Action | What changes |
|---|---|---|
| `src/main/java/com/example/agentsuite/tools/Git.java` | Modify | Fix `status()` double-run bug |
| `src/main/java/com/example/agentsuite/controller/AiController.java` | Modify | Add `case "git"` branch; update unknown-command error message |
| `src/test/java/com/example/agentsuite/tools/GitTest.java` | Create | Unit tests for all six `Git` methods using a temp git repo |
| `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | Modify | Add git routing and error-case tests |

---

### Task 1: Add `GitTest` and fix `Git.status()` double-run bug

**Files:**
- Create: `src/test/java/com/example/agentsuite/tools/GitTest.java`
- Modify: `src/main/java/com/example/agentsuite/tools/Git.java`

> **Note on RED phase:** The `status()` double-run is a code quality bug (the process runs twice, but both runs return the same result). Behavioral tests pass before and after the fix. Write the tests, confirm GREEN, then apply the fix as a REFACTOR step and re-confirm GREEN.

- [ ] **Step 1: Create `GitTest` with setup/teardown scaffold**

Create `src/test/java/com/example/agentsuite/tools/GitTest.java`:

```java
package com.example.agentsuite.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

class GitTest {

    private Path tempDir;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("git-test");
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "init"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.email", "test@test.com"}).run();
        new ProcessRunner(new String[]{"git", "-C", tempDir.toString(), "config", "user.name", "Test"}).run();
        git = new Git(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }
}
```

- [ ] **Step 2: Add all six tests**

Add these test methods inside `GitTest`:

```java
    @Test
    void status_returnsOutput() {
        String result = git.status();
        assertThat(result).contains("branch");
    }

    @Test
    void add_stagedFile_returnsAddedMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        String result = git.add("hello.txt");
        assertThat(result).isEqualTo("Added hello.txt");
    }

    @Test
    void commit_withMessage_returnsCommitOutput() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        String result = git.commit("initial commit");
        assertThat(result).contains("initial commit");
    }

    @Test
    void newBranch_returnsConfirmationMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        git.commit("initial commit");
        String result = git.newBranch("my-feature");
        assertThat(result).isEqualTo("Created and switched to branch my-feature");
    }

    @Test
    void checkoutBranch_returnsConfirmationMessage() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        git.add("hello.txt");
        git.commit("initial commit");
        git.newBranch("branch-a");
        git.newBranch("branch-b");
        String result = git.checkoutBranch("branch-a");
        assertThat(result).isEqualTo("Switched to branch branch-a");
    }

    @Test
    void push_noRemote_returnsError() {
        String result = git.push();
        assertThat(result).startsWith("Error:");
    }
```

- [ ] **Step 3: Run tests to confirm GREEN**

```
.\mvnw test -Dtest=GitTest
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 4: Fix `Git.status()` double-run bug (REFACTOR)**

In `src/main/java/com/example/agentsuite/tools/Git.java`, replace:

```java
    public String status() {
        ProcessRunner runner = new ProcessRunner(new String[]{"git", "-C", root.toString(), "status"});
        ProcessRunner.Output output = runner.run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return runner.run().stdOut();
    }
```

With:

```java
    public String status() {
        ProcessRunner.Output output = new ProcessRunner(new String[]{"git", "-C", root.toString(), "status"}).run();
        if (output.exitCode() != 0) {
            return "Error: " + output.stdErr();
        }
        return output.stdOut();
    }
```

- [ ] **Step 5: Re-run `GitTest` to confirm still GREEN**

```
.\mvnw test -Dtest=GitTest
```
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```
git add src/test/java/com/example/agentsuite/tools/GitTest.java
git add src/main/java/com/example/agentsuite/tools/Git.java
git commit -m "test: add GitTest; fix Git.status() double-run bug"
```

---

### Task 2: Add `case "git"` routing in `AiController`

**Files:**
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 1: Add five failing tests to `AiControllerTest`**

In `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`, add these five test methods:

```java
    @Test
    void tools_gitStatus_doesNotReturnUnknownCommand() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git status")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.not(
                        org.hamcrest.CoreMatchers.containsString("Unknown command"))));
    }

    @Test
    void tools_gitNoSubcommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("git requires a subcommand")));
    }

    @Test
    void tools_gitUnknownSubcommand_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git rebase")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("Unknown git subcommand")));
    }

    @Test
    void tools_gitAddMissingArg_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git add")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("add requires a file path")));
    }

    @Test
    void tools_gitCommitMissingMessage_returnsError() throws Exception {
        mockMvc.perform(get("/ai/tools")
                        .param("command", "git commit")
                        .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.CoreMatchers.containsString("commit requires a message")));
    }
```

- [ ] **Step 2: Run new tests to confirm RED**

```
.\mvnw test -Dtest=AiControllerTest
```
Expected: 5 new tests FAIL (response contains "Unknown command 'git'"), existing 6 tests still pass.

- [ ] **Step 3: Add `case "git"` to `AiController.executeTool()`**

In `src/main/java/com/example/agentsuite/controller/AiController.java`:

Add `import com.example.agentsuite.tools.Git;` alongside the existing `UnixTools` import.

Then replace the entire switch expression in `executeTool()`:

```java
        return switch (tool) {
            case "ls" -> unixTools.ls(tokens.size() > 1 ? tokens.get(1) : ".");
            case "cat" -> tokens.size() > 1 ? unixTools.cat(tokens.get(1)) : "Error: cat requires a file path";
            case "grep" -> tokens.size() > 2
                    ? unixTools.grep(tokens.get(1), tokens.get(2))
                    : "Error: grep requires search text and file filter";
            default -> "Error: Unknown command '" + tool + "'. Use: ls, cat, or grep";
        };
```

With:

```java
        return switch (tool) {
            case "ls" -> unixTools.ls(tokens.size() > 1 ? tokens.get(1) : ".");
            case "cat" -> tokens.size() > 1 ? unixTools.cat(tokens.get(1)) : "Error: cat requires a file path";
            case "grep" -> tokens.size() > 2
                    ? unixTools.grep(tokens.get(1), tokens.get(2))
                    : "Error: grep requires search text and file filter";
            case "git" -> {
                if (tokens.size() < 2) {
                    yield "Error: git requires a subcommand: status, add, commit, push, newBranch, checkoutBranch";
                }
                Git git = new Git(rootDirectory);
                yield switch (tokens.get(1)) {
                    case "status" -> git.status();
                    case "add" -> tokens.size() > 2
                            ? git.add(tokens.get(2))
                            : "Error: add requires a file path";
                    case "commit" -> tokens.size() > 2
                            ? git.commit(String.join(" ", tokens.subList(2, tokens.size())))
                            : "Error: commit requires a message";
                    case "push" -> git.push();
                    case "newBranch" -> tokens.size() > 2
                            ? git.newBranch(tokens.get(2))
                            : "Error: newBranch requires a branch name";
                    case "checkoutBranch" -> tokens.size() > 2
                            ? git.checkoutBranch(tokens.get(2))
                            : "Error: checkoutBranch requires a branch name";
                    default -> "Error: Unknown git subcommand '" + tokens.get(1)
                            + "'. Use: status, add, commit, push, newBranch, checkoutBranch";
                };
            }
            default -> "Error: Unknown command '" + tool + "'. Use: ls, cat, grep, or git";
        };
```

- [ ] **Step 4: Run `AiControllerTest` to confirm GREEN**

```
.\mvnw test -Dtest=AiControllerTest
```
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Run full test suite**

```
.\mvnw test
```
Expected: `Tests run: 44, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: add git commands to tool executor"
```
