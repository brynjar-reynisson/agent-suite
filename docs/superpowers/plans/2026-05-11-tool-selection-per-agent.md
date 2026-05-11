# Tool Selection Per Agent — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow each PROMPT_BANK entry to declare which tool groups it needs, forwarding them to the backend via a `tools` request param that controls which tool instances are passed to the AI model.

**Architecture:** The frontend resolves the selected prompt's `tools: string[]` field into a comma-separated string and sends it as `?tools=unix` (or empty). The backend adds a `@RequestParam String tools`, extracts a static `buildToolInstances(tools, rootDirectory)` method with a `switch` per known group, and calls it to build the tool array. `UnixTools` is only added when both `"unix"` is in the list and `rootDirectory` is non-empty.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, AssertJ, React 19, TypeScript

---

### Task 1: Backend — extract `buildToolInstances` and add `tools` param

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Add failing tests for `buildToolInstances`**

Add these tests inside `AiControllerTest` (the class already uses `@WebMvcTest`; these tests call the static method directly):

```java
// At the top of the file, add imports:
import com.example.agentsuite.tools.UnixTools;
import static org.assertj.core.api.Assertions.assertThat;
```

Add inside the `AiControllerTest` class body:

```java
@Test
void buildToolInstances_emptyTools_returnsEmptyArray() {
    Object[] result = AiController.buildToolInstances("", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).isEmpty();
}

@Test
void buildToolInstances_unixGroup_noRootDirectory_returnsEmptyArray() {
    Object[] result = AiController.buildToolInstances("unix", "");
    assertThat(result).isEmpty();
}

@Test
void buildToolInstances_unixGroup_withRootDirectory_returnsUnixTools() {
    Object[] result = AiController.buildToolInstances("unix", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(UnixTools.class);
}

@Test
void buildToolInstances_unknownGroup_silentlyIgnored() {
    Object[] result = AiController.buildToolInstances("unknown", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).isEmpty();
}

@Test
void buildToolInstances_multipleGroups_onlyKnownGroupsAdded() {
    Object[] result = AiController.buildToolInstances("unix,unknown", "C:/Users/Lenovo/IdeaProjects/agent-suite");
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(UnixTools.class);
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -Dtest=AiControllerTest -pl . 2>&1 | tail -20
```

Expected: compilation error — `buildToolInstances` does not exist yet.

- [ ] **Step 3: Add `buildToolInstances` static method to `AiController`**

In `AiController.java`, add this static method (place it before the `parseCommand` method at the bottom):

```java
static Object[] buildToolInstances(String tools, String rootDirectory) {
    if (tools.isBlank()) return new Object[0];
    List<Object> instances = new ArrayList<>();
    for (String group : tools.split(",")) {
        switch (group.trim()) {
            case "unix" -> {
                if (!rootDirectory.isEmpty()) instances.add(new UnixTools(rootDirectory));
            }
        }
    }
    return instances.toArray();
}
```

- [ ] **Step 4: Add `tools` request param and wire it up in `chat()`**

In `AiController.java`, update the `chat()` method signature (add `tools` param after `model`):

```java
@RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
public SseEmitter chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                       @RequestParam(defaultValue = "") String prompt,
                       @RequestParam(defaultValue = "") String rootDirectory,
                       @RequestParam(defaultValue = "deepseek-v4-pro") String model,
                       @RequestParam(defaultValue = "") String tools) {
```

Replace the existing one-liner tool array construction inside `chat()`:

```java
// Remove this line:
Object[] tools = rootDirectory.isEmpty() ? new Object[0] : new Object[]{new UnixTools(rootDirectory)};

// Replace with:
Object[] toolArray = buildToolInstances(tools, rootDirectory);
```

Update the `chatStream` call to use `toolArray`:

```java
service.chatStream(prompt, message, event -> { ... }, toolArray);
```

Update the log statement to include `tools`:

```java
log.info("Chat request - model: {}, prompt: {}, message: {}, rootDirectory: {}, tools: {}",
        model, prompt, message, rootDirectory, tools);
```

- [ ] **Step 5: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=AiControllerTest -pl . 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all tests pass including the 5 new ones.

- [ ] **Step 6: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/AiController.java src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add tools param to AiController chat endpoint"
```

---

### Task 2: Frontend — add `tools` to `ChatRequest` in `api.ts`

**Files:**
- Modify: `frontend/src/api.ts`

- [ ] **Step 1: Add `tools` to the `ChatRequest` interface**

In `frontend/src/api.ts`, update the `ChatRequest` interface:

```typescript
export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
  tools?: string;
}
```

- [ ] **Step 2: Include `tools` in the URL params**

In the `chatStream` function, update `urlParams` construction:

```typescript
const urlParams = new URLSearchParams({
  message: params.message,
  prompt: params.prompt || '',
  rootDirectory: params.rootDirectory || '',
  model: params.model || 'deepseek-v4-pro',
  tools: params.tools || '',
});
```

- [ ] **Step 3: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/api.ts
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add tools param to chatStream API"
```

---

### Task 3: Frontend — add `tools` to `PROMPT_BANK` and wire in `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add `tools: string[]` to each `PROMPT_BANK` entry**

In `frontend/src/App.tsx`, replace the `PROMPT_BANK` constant:

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
    tools: ['unix'],
  },
  {
    name: 'Specification-writer',
    text: 'Your job is to create a new specification file that takes a user request and defines the business requirement, the user problem and the success criteria. Specify what is in scope and out of scope. This is about the what and why, not how it will be implemented.',
    tools: ['unix'],
  },
];
```

- [ ] **Step 2: Update `PromptComboboxProps` to include `tools`**

In `frontend/src/App.tsx`, update the `PromptComboboxProps` interface:

```typescript
interface PromptComboboxProps {
  value: string;
  onChange: (v: string) => void;
  prompts?: { name: string; text: string; tools: string[] }[];
}
```

- [ ] **Step 3: Resolve tools in `handleSend` and pass to `chatStream`**

In `frontend/src/App.tsx`, inside `handleSend`, replace:

```typescript
const resolvedPrompt = PROMPT_BANK.find(p => p.name === prompt)?.text ?? prompt;
```

with:

```typescript
const matched = PROMPT_BANK.find(p => p.name === prompt);
const resolvedPrompt = matched?.text ?? prompt;
const resolvedTools = (matched?.tools ?? []).join(',');
```

Then update the `chatStream` call to include `tools`:

```typescript
await chatStream(
  {
    message: message,
    prompt: resolvedPrompt,
    rootDirectory: rootDirectory,
    model: model,
    tools: resolvedTools,
  },
  {
    onToolCall: (tc) => {
      setMessages((prev) => {
        const msgs = [...prev];
        const last = msgs[msgs.length - 1];
        if (last && last.role === 'ai') {
          const updated = { ...last, toolCalls: [...(last.toolCalls || []), tc] };
          msgs[msgs.length - 1] = updated;
        } else {
          msgs.push({ role: 'ai', content: '', toolCalls: [tc] });
        }
        return msgs;
      });
    },
    onContent: (text) => {
      setMessages((prev) => {
        const msgs = [...prev];
        const last = msgs[msgs.length - 1];
        if (last && last.role === 'ai') {
          msgs[msgs.length - 1] = { ...last, content: text };
        } else {
          msgs.push({ role: 'ai', content: text });
        }
        return msgs;
      });
    },
  }
);
```

- [ ] **Step 4: Verify TypeScript compiles cleanly**

```bash
cd frontend && npx tsc --noEmit 2>&1
```

Expected: no errors.

- [ ] **Step 5: Manual smoke test**

Start both servers:
```bash
# Terminal 1
./mvnw spring-boot:run

# Terminal 2
cd frontend && npm run dev
```

Open `http://localhost:5176`. Select `Code-request classifier` from the prompt dropdown and a root directory. Send a message that requires file access (e.g., "list the source files"). Confirm the response includes tool call activity (e.g., `ls` calls appear). Then select a blank model/no prompt (free-text) and confirm no tool calls appear in the response.

- [ ] **Step 6: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add tools field to PROMPT_BANK and wire tool selection to chatStream"
```
