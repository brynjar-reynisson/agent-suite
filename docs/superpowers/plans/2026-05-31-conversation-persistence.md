# Conversation Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist all chat messages (system prompts, user/assistant turns, model changes, tool use per iteration) to the database and replay full conversation history to the LLM on each subsequent turn.

**Architecture:** A new `ChatOrchestrationService` sits between `AiController` and `ChatService`. It owns conversation lifecycle, history loading, and message persistence. `ChatService` gains a new `chatStreamWithHistory(List<HistoryMessage>, ...)` method; `chatStream` delegates to it. `ChatEvent.ToolCall` is replaced by `ChatEvent.ToolBatch` (one per agentic iteration, includes tool results) so results are capturable without changing the SSE frontend protocol — `AiController` translates each `ToolBatch` into individual SSE `tool_call` events. The frontend generates a UUID per session via `crypto.randomUUID()` and passes it as `conversationId` on every request.

**Tech Stack:** Spring Boot 3.5, JOOQ 3.x, Jackson ObjectMapper, LangChain4j 0.36.2, React 19 + TypeScript

---

## File Map

**New:**
- `supabase/migrations/20260531000000_add_external_id_to_conversation.sql`
- `src/main/java/com/example/agentsuite/service/HistoryMessage.java`
- `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`
- `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`

**Modified:**
- `src/main/java/com/example/agentsuite/service/ChatEvent.java`
- `src/main/java/com/example/agentsuite/service/ChatService.java`
- `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`
- `src/main/java/com/example/agentsuite/service/DeepSeekService.java`
- `src/main/java/com/example/agentsuite/jooq/generated/tables/Conversation.java` *(regenerated)*
- `src/main/java/com/example/agentsuite/jooq/generated/tables/records/ConversationRecord.java` *(regenerated)*
- `src/main/java/com/example/agentsuite/jooq/generated/Keys.java` *(regenerated)*
- `src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java`
- `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`
- `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- `src/main/java/com/example/agentsuite/controller/AiController.java`
- `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`
- `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`
- `frontend/src/api.ts`
- `frontend/src/App.tsx`

---

## Message Type Reference

All `message.type` values used in this implementation:

| Value | Sent to LLM? | Notes |
|---|---|---|
| `system_prompt` | Yes | System prompt at conversation start |
| `user` | Yes | User chat message |
| `assistant` | Yes | LLM text response |
| `tool_call` | Yes | JSON: `[{"name":"...", "arguments":"..."}]` per iteration |
| `tool_result` | Yes | JSON: `[{"name":"...", "result":"..."}]` per iteration (parallel with tool_call) |
| `model_change` | No | Model name string, metadata only |

---

## Task 1: Migration and JOOQ Regeneration

**Files:**
- Create: `supabase/migrations/20260531000000_add_external_id_to_conversation.sql`
- Regenerated: `src/main/java/com/example/agentsuite/jooq/generated/tables/Conversation.java`
- Regenerated: `src/main/java/com/example/agentsuite/jooq/generated/tables/records/ConversationRecord.java`
- Regenerated: `src/main/java/com/example/agentsuite/jooq/generated/Keys.java`

- [ ] **Step 1: Write migration**

```sql
ALTER TABLE conversation
    ADD COLUMN external_id TEXT UNIQUE NOT NULL;
```

Save to `supabase/migrations/20260531000000_add_external_id_to_conversation.sql`. No DEFAULT needed — the table is empty in all environments.

- [ ] **Step 2: Apply migration to local Supabase**

```bash
npx supabase migration up
```

Expected output: `Applying migration 20260531000000_add_external_id_to_conversation...`

- [ ] **Step 3: Regenerate JOOQ classes**

```bash
./mvnw jooq-codegen:generate
```

Expected: exits 0. Verify that `Conversation.java` now has an `EXTERNAL_ID` field and `ConversationRecord.java` has `getExternalId()`/`setExternalId(String)`.

- [ ] **Step 4: Run tests**

```bash
./mvnw test
```

Expected: all tests pass. If tests fail with a column-not-found error for `external_id`, the H2 test schema needs updating — execute **Task 9** before continuing.

- [ ] **Step 5: Commit**

```bash
git add supabase/migrations/20260531000000_add_external_id_to_conversation.sql
git add src/main/java/com/example/agentsuite/jooq/generated/
git commit -m "feat: add external_id to conversation table and regenerate JOOQ"
```

---

## Task 2: HistoryMessage and ChatEvent.ToolBatch

**Files:**
- Create: `src/main/java/com/example/agentsuite/service/HistoryMessage.java`
- Modify: `src/main/java/com/example/agentsuite/service/ChatEvent.java`
- Modify (fix compile): `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 1: Create HistoryMessage**

Create `src/main/java/com/example/agentsuite/service/HistoryMessage.java`:

```java
package com.example.agentsuite.service;

public sealed interface HistoryMessage
        permits HistoryMessage.SystemPrompt, HistoryMessage.User, HistoryMessage.Assistant,
                HistoryMessage.ToolCall, HistoryMessage.ToolResult {

    record SystemPrompt(String content) implements HistoryMessage {}
    record User(String content) implements HistoryMessage {}
    record Assistant(String content) implements HistoryMessage {}
    // callsJson: JSON array [{"name":"...","arguments":"..."}]
    record ToolCall(String callsJson) implements HistoryMessage {}
    // resultsJson: JSON array [{"name":"...","result":"..."}]
    record ToolResult(String resultsJson) implements HistoryMessage {}
}
```

- [ ] **Step 2: Replace ChatEvent.ToolCall with ChatEvent.ToolBatch**

Replace the entire contents of `src/main/java/com/example/agentsuite/service/ChatEvent.java`:

```java
package com.example.agentsuite.service;

import java.util.List;

public sealed interface ChatEvent {

    record ToolBatch(List<ToolExecution> executions) implements ChatEvent {
        public record ToolExecution(String name, String arguments, String result) {}
    }

    record Content(String text) implements ChatEvent {}

    record Error(String message) implements ChatEvent {}

    record Done() implements ChatEvent {}
}
```

- [ ] **Step 3: Fix AiController compilation — update ToolCall case to ToolBatch**

In `AiController.java`, add this import after the existing imports if not already present:

```java
import java.util.Map;
```

Then find the lambda inside `service.chatStream(...)`:

```java
// OLD — remove this case:
case ChatEvent.ToolCall tc ->
        sendEvent(emitter, "tool_call", tc);
```

Replace with:

```java
case ChatEvent.ToolBatch tb -> {
    for (ChatEvent.ToolBatch.ToolExecution e : tb.executions()) {
        sendEvent(emitter, "tool_call",
                Map.of("name", e.name(), "arguments", e.arguments()));
    }
}
```

Note: Task 7 will replace this entire file with the final version; this step only needs to keep the project compilable.

- [ ] **Step 4: Verify compilation**

```bash
./mvnw compile
```

Expected: BUILD SUCCESS. (Tests that reference `ChatEvent.ToolCall` will also need fixing — do that in the next step.)

- [ ] **Step 5: Fix any test references to ChatEvent.ToolCall**

Search for `ChatEvent.ToolCall` in test files:

```bash
grep -r "ChatEvent.ToolCall" src/test/
```

If found, update to use `ChatEvent.ToolBatch`. If none found, skip.

- [ ] **Step 6: Run tests**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/HistoryMessage.java
git add src/main/java/com/example/agentsuite/service/ChatEvent.java
git add src/main/java/com/example/agentsuite/controller/AiController.java
git commit -m "feat: add HistoryMessage DTO and replace ChatEvent.ToolCall with ToolBatch"
```

---

## Task 3: ChatService Interface + AbstractLangChain4jChatService

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/ChatService.java`
- Modify: `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`

- [ ] **Step 1: Add chatStreamWithHistory to ChatService interface**

Replace the entire contents of `src/main/java/com/example/agentsuite/service/ChatService.java`:

```java
package com.example.agentsuite.service;

import java.util.List;
import java.util.function.Consumer;

public interface ChatService {
    ChatResponse chat(String systemPrompt, String userMessage, Object... tools);

    void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools);

    void chatStreamWithHistory(List<HistoryMessage> history, String userMessage,
                               Consumer<ChatEvent> emitter, Object... tools);
}
```

- [ ] **Step 2: Rewrite AbstractLangChain4jChatService**

Replace the entire contents of `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`:

```java
package com.example.agentsuite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

abstract class AbstractLangChain4jChatService implements ChatService {

    private static final int MAX_TOOL_ITERATIONS = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected final ChatLanguageModel model;

    protected AbstractLangChain4jChatService(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public ChatResponse chat(String systemPrompt, String userMessage, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        List<ChatResponse.ToolCall> allToolCalls = new ArrayList<>();
        int iterations = 0;
        while (true) {
            if (iterations >= MAX_TOOL_ITERATIONS) {
                throw new IllegalStateException("Exceeded maximum tool iterations: " + MAX_TOOL_ITERATIONS);
            }
            Response<AiMessage> response = toolSpecs.isEmpty()
                    ? model.generate(messages)
                    : model.generate(messages, toolSpecs);

            AiMessage aiMessage = response.content();
            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text() != null ? aiMessage.text() : "";
                return new ChatResponse(allToolCalls, text);
            }
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                allToolCalls.add(new ChatResponse.ToolCall(req.name(), req.arguments()));
                ToolExecutor executor = executors.get(req.name());
                if (executor == null) throw new IllegalStateException("No executor for tool: " + req.name());
                messages.add(ToolExecutionResultMessage.from(req, executor.execute(req, "default")));
            }
            iterations++;
        }
    }

    @Override
    public void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools) {
        List<HistoryMessage> history = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            history.add(new HistoryMessage.SystemPrompt(systemPrompt));
        }
        chatStreamWithHistory(history, userMessage, emitter, tools);
    }

    @Override
    public void chatStreamWithHistory(List<HistoryMessage> history, String userMessage,
                                      Consumer<ChatEvent> emitter, Object... tools) {
        List<ChatMessage> messages = buildMessageList(history);
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        int iterations = 0;
        while (true) {
            if (iterations >= MAX_TOOL_ITERATIONS) {
                throw new IllegalStateException("Exceeded maximum tool iterations: " + MAX_TOOL_ITERATIONS);
            }
            Response<AiMessage> response = toolSpecs.isEmpty()
                    ? model.generate(messages)
                    : model.generate(messages, toolSpecs);

            AiMessage aiMessage = response.content();
            if (!aiMessage.hasToolExecutionRequests()) {
                String text = aiMessage.text() != null ? aiMessage.text() : "";
                emitter.accept(new ChatEvent.Content(text));
                emitter.accept(new ChatEvent.Done());
                return;
            }
            messages.add(aiMessage);
            List<ChatEvent.ToolBatch.ToolExecution> executions = new ArrayList<>();
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                ToolExecutor executor = executors.get(req.name());
                if (executor == null) throw new IllegalStateException("No executor for tool: " + req.name());
                String result = executor.execute(req, "default");
                executions.add(new ChatEvent.ToolBatch.ToolExecution(req.name(), req.arguments(), result));
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
            emitter.accept(new ChatEvent.ToolBatch(executions));
            iterations++;
        }
    }

    private List<ChatMessage> buildMessageList(List<HistoryMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        List<ToolExecutionRequest> pendingRequests = null;
        for (HistoryMessage h : history) {
            switch (h) {
                case HistoryMessage.SystemPrompt sp -> messages.add(SystemMessage.from(sp.content()));
                case HistoryMessage.User u -> messages.add(UserMessage.from(u.content()));
                case HistoryMessage.Assistant a -> messages.add(AiMessage.from(a.content()));
                case HistoryMessage.ToolCall tc -> {
                    pendingRequests = parseToolCallRequests(tc.callsJson());
                    messages.add(AiMessage.from(pendingRequests));
                }
                case HistoryMessage.ToolResult tr -> {
                    List<String> results = parseToolResults(tr.resultsJson());
                    for (int i = 0; i < results.size(); i++) {
                        messages.add(ToolExecutionResultMessage.from(pendingRequests.get(i), results.get(i)));
                    }
                    pendingRequests = null;
                }
            }
        }
        return messages;
    }

    private List<ToolExecutionRequest> parseToolCallRequests(String callsJson) {
        try {
            JsonNode arr = MAPPER.readTree(callsJson);
            List<ToolExecutionRequest> requests = new ArrayList<>();
            for (JsonNode item : arr) {
                requests.add(ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(item.get("name").asText())
                        .arguments(item.get("arguments").asText())
                        .build());
            }
            return requests;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseToolResults(String resultsJson) {
        try {
            JsonNode arr = MAPPER.readTree(resultsJson);
            List<String> results = new ArrayList<>();
            for (JsonNode item : arr) {
                results.add(item.get("result").asText());
            }
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ToolSpecification> buildToolSpecs(Object[] tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object tool : tools) specs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        return specs;
    }

    private Map<String, ToolExecutor> buildExecutors(Object[] tools) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    Tool annotation = method.getAnnotation(Tool.class);
                    String toolName = annotation.name().isEmpty() ? method.getName() : annotation.name();
                    executors.put(toolName, new DefaultToolExecutor(tool, method));
                }
            }
        }
        return executors;
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/ChatService.java
git add src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java
git commit -m "feat: add chatStreamWithHistory to ChatService; refactor AbstractLangChain4j to emit ToolBatch"
```

---

## Task 4: DeepSeekService — chatStreamWithHistory

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/DeepSeekService.java`

- [ ] **Step 1: Add chatStreamWithHistory to DeepSeekService**

Add the following import at the top of `DeepSeekService.java` (after existing imports):

```java
import java.util.UUID;
```

Add the following two methods to `DeepSeekService`, before `private void processResponseStream(...)`:

```java
@Override
public void chatStreamWithHistory(List<HistoryMessage> history, String userMessage,
                                   Consumer<ChatEvent> emitter, Object... tools) {
    List<ObjectNode> messages = buildMessagesFromHistory(history);
    messages.add(createMessage("user", userMessage));

    ArrayNode toolDefs = tools.length > 0 ? buildToolDefinitions(tools) : null;
    JsonNode response = sendRequest(messages, toolDefs);
    processResponseStream(messages, toolDefs, tools, response, emitter);
    emitter.accept(new ChatEvent.Done());
}

private List<ObjectNode> buildMessagesFromHistory(List<HistoryMessage> history) {
    List<ObjectNode> messages = new ArrayList<>();
    List<String> pendingCallIds = null;

    for (HistoryMessage h : history) {
        switch (h) {
            case HistoryMessage.SystemPrompt sp ->
                    messages.add(createMessage("system", sp.content()));
            case HistoryMessage.User u ->
                    messages.add(createMessage("user", u.content()));
            case HistoryMessage.Assistant a ->
                    messages.add(createMessage("assistant", a.content()));
            case HistoryMessage.ToolCall tc -> {
                try {
                    JsonNode arr = mapper.readTree(tc.callsJson());
                    pendingCallIds = new ArrayList<>();
                    ObjectNode assistantMsg = mapper.createObjectNode();
                    assistantMsg.put("role", "assistant");
                    assistantMsg.putNull("content");
                    ArrayNode toolCallsArr = mapper.createArrayNode();
                    for (JsonNode item : arr) {
                        String id = UUID.randomUUID().toString();
                        pendingCallIds.add(id);
                        ObjectNode tcNode = mapper.createObjectNode();
                        tcNode.put("id", id);
                        tcNode.put("type", "function");
                        ObjectNode func = mapper.createObjectNode();
                        func.put("name", item.get("name").asText());
                        func.put("arguments", item.get("arguments").asText());
                        tcNode.set("function", func);
                        toolCallsArr.add(tcNode);
                    }
                    assistantMsg.set("tool_calls", toolCallsArr);
                    messages.add(assistantMsg);
                } catch (Exception ignored) {}
            }
            case HistoryMessage.ToolResult tr -> {
                try {
                    JsonNode arr = mapper.readTree(tr.resultsJson());
                    for (int i = 0; i < arr.size(); i++) {
                        ObjectNode toolMsg = mapper.createObjectNode();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", pendingCallIds != null ? pendingCallIds.get(i) : "");
                        toolMsg.put("content", arr.get(i).get("result").asText());
                        messages.add(toolMsg);
                    }
                    pendingCallIds = null;
                } catch (Exception ignored) {}
            }
        }
    }
    return messages;
}
```

- [ ] **Step 2: Update chatStream to delegate to chatStreamWithHistory**

Replace the existing `chatStream` method:

```java
// OLD:
@Override
public void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools) {
    List<ObjectNode> messages = new ArrayList<>();
    if (systemPrompt != null && !systemPrompt.isEmpty()) {
        messages.add(createMessage("system", systemPrompt));
    }
    messages.add(createMessage("user", userMessage));

    ArrayNode toolDefs = tools.length > 0 ? buildToolDefinitions(tools) : null;
    JsonNode response = sendRequest(messages, toolDefs);

    processResponseStream(messages, toolDefs, tools, response, emitter);
    emitter.accept(new ChatEvent.Done());
}
```

with:

```java
@Override
public void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools) {
    List<HistoryMessage> history = new ArrayList<>();
    if (systemPrompt != null && !systemPrompt.isBlank()) {
        history.add(new HistoryMessage.SystemPrompt(systemPrompt));
    }
    chatStreamWithHistory(history, userMessage, emitter, tools);
}
```

- [ ] **Step 3: Update processResponseStream to emit ToolBatch instead of individual ToolCall**

In `processResponseStream`, find this block:

```java
ArrayNode toolCalls = (ArrayNode) msg.get("tool_calls");
for (JsonNode toolCall : toolCalls) {
    String funcName = toolCall.get("function").get("name").asText();
    String funcArgs = toolCall.get("function").get("arguments").asText();
    String callId = toolCall.get("id").asText();

    emitter.accept(new ChatEvent.ToolCall(funcName, funcArgs));

    String result = executeTool(tools, funcName, funcArgs);
    ObjectNode toolMsg = mapper.createObjectNode();
    toolMsg.put("role", "tool");
    toolMsg.put("tool_call_id", callId);
    toolMsg.put("content", result);
    messages.add(toolMsg);
}
```

Replace with:

```java
ArrayNode toolCalls = (ArrayNode) msg.get("tool_calls");
List<ChatEvent.ToolBatch.ToolExecution> executions = new ArrayList<>();
for (JsonNode toolCall : toolCalls) {
    String funcName = toolCall.get("function").get("name").asText();
    String funcArgs = toolCall.get("function").get("arguments").asText();
    String callId = toolCall.get("id").asText();

    String result = executeTool(tools, funcName, funcArgs);
    executions.add(new ChatEvent.ToolBatch.ToolExecution(funcName, funcArgs, result));

    ObjectNode toolMsg = mapper.createObjectNode();
    toolMsg.put("role", "tool");
    toolMsg.put("tool_call_id", callId);
    toolMsg.put("content", result);
    messages.add(toolMsg);
}
emitter.accept(new ChatEvent.ToolBatch(executions));
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/DeepSeekService.java
git commit -m "feat: add chatStreamWithHistory to DeepSeekService and emit ToolBatch"
```

---

## Task 5: Repository and ConversationService Updates

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java`
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/RepositoryTest.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

- [ ] **Step 1: Write failing tests for new repository methods**

Add these tests to `RepositoryTest.java`, inside the class body after the existing tests:

```java
@Test
void findConversationByExternalId() {
    long id = conversationRepo.insert(guestId, "Ext Conv", "/home", "ext-uuid-123");
    assertThat(conversationRepo.findByExternalId("ext-uuid-123"))
            .isPresent()
            .hasValueSatisfying(r -> assertThat(r.getConversationName()).isEqualTo("Ext Conv"));
}

@Test
void findConversationByExternalIdMissingReturnsEmpty() {
    assertThat(conversationRepo.findByExternalId("no-such-uuid")).isEmpty();
}

@Test
void findLastModelChangeReturnsLatest() {
    long convId = conversationRepo.insert(guestId, "Model Test", null, "uuid-model-test");
    messageRepo.insert(convId, guestId, "model_change", "deepseek-v4-pro");
    messageRepo.insert(convId, guestId, "model_change", "sonnet-4.6");
    assertThat(messageRepo.findLastModelChange(convId))
            .isPresent()
            .hasValue("sonnet-4.6");
}

@Test
void findLastModelChangeEmptyWhenNone() {
    long convId = conversationRepo.insert(guestId, "No Model", null, "uuid-no-model");
    assertThat(messageRepo.findLastModelChange(convId)).isEmpty();
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw test -Dtest=RepositoryTest
```

Expected: compile error (insert signature mismatch) or test failures on the new tests.

- [ ] **Step 3: Update ConversationRepository**

Replace the entire contents of `ConversationRepository.java`:

```java
package com.example.agentsuite.jooq.repository;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.example.agentsuite.jooq.generated.Tables.CONVERSATION;

@Repository
public class ConversationRepository {

    private final DSLContext dsl;

    public ConversationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long insert(long userId, String name, String rootDirectory, String externalId) {
        return dsl.insertInto(CONVERSATION)
                .set(CONVERSATION.USER_ID, userId)
                .set(CONVERSATION.CONVERSATION_NAME, name)
                .set(CONVERSATION.ROOT_DIRECTORY, rootDirectory)
                .set(CONVERSATION.EXTERNAL_ID, externalId)
                .returning(CONVERSATION.CONVERSATION_ID)
                .fetchSingle()
                .getConversationId();
    }

    public Optional<ConversationRecord> findById(long conversationId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.CONVERSATION_ID.eq(conversationId))
                .fetchOptional();
    }

    public Optional<ConversationRecord> findByExternalId(String externalId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.EXTERNAL_ID.eq(externalId))
                .fetchOptional();
    }

    public List<ConversationRecord> findByUserId(long userId) {
        return dsl.selectFrom(CONVERSATION)
                .where(CONVERSATION.USER_ID.eq(userId))
                .orderBy(CONVERSATION.CREATE_TIME.desc())
                .fetch();
    }
}
```

- [ ] **Step 4: Update MessageRepository**

Add this method to `MessageRepository.java` after `findByConversationId`:

```java
public Optional<String> findLastModelChange(long conversationId) {
    return dsl.select(MESSAGE.MESSAGE_)
            .from(MESSAGE)
            .where(MESSAGE.CONVERSATION_ID.eq(conversationId))
            .and(MESSAGE.TYPE.eq("model_change"))
            .orderBy(MESSAGE.MESSAGE_TIME.desc(), MESSAGE.MESSAGE_ID.desc())
            .limit(1)
            .fetchOptional(MESSAGE.MESSAGE_);
}
```

- [ ] **Step 5: Update ConversationService**

Replace the entire contents of `ConversationService.java`:

```java
package com.example.agentsuite.jooq.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public long createConversation(long userId, String name, String rootDirectory, String externalId) {
        return conversationRepository.insert(userId, name, rootDirectory, externalId);
    }

    @Transactional
    public void addMessage(long conversationId, long userId, String type, String message) {
        messageRepository.insert(conversationId, userId, type, message);
    }

    @Transactional(readOnly = true)
    public List<MessageRecord> getMessages(long conversationId) {
        return messageRepository.findByConversationId(conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationRecord getConversation(long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }

    @Transactional(readOnly = true)
    public Optional<ConversationRecord> findByExternalId(String externalId) {
        return conversationRepository.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Optional<String> findLastModelChange(long conversationId) {
        return messageRepository.findLastModelChange(conversationId);
    }
}
```

- [ ] **Step 6: Update RepositoryTest — fix insert calls to include externalId**

In `RepositoryTest.java`, replace all `conversationRepo.insert(...)` calls that use the 3-arg signature. Each one needs a 4th UUID argument:

```java
// insertConversationReturnsId test:
long id = conversationRepo.insert(guestId, "Test Conv", "/home", java.util.UUID.randomUUID().toString());

// findConversationByIdRoundTrip test:
long id = conversationRepo.insert(guestId, "My Conv", "/projects", java.util.UUID.randomUUID().toString());

// findConversationsByUserId test — 3 calls:
conversationRepo.insert(guestId, "Conv A", null, java.util.UUID.randomUUID().toString());
conversationRepo.insert(guestId, "Conv B", null, java.util.UUID.randomUUID().toString());
```

Also update `insertMessagesReturnedInOrder`:
```java
long convId = conversationRepo.insert(guestId, "Order Test", null, java.util.UUID.randomUUID().toString());
```

And `messageTypesRoundTrip`:
```java
long convId = conversationRepo.insert(guestId, "Types Test", null, java.util.UUID.randomUUID().toString());
```

- [ ] **Step 7: Update ConversationServiceTest — fix createConversation calls**

In `ConversationServiceTest.java`, replace all `service.createConversation(...)` calls to include a 4th UUID argument:

```java
// In setUp():
guestConvId = service.createConversation(guestId, "Guest Chat", "/projects",
        java.util.UUID.randomUUID().toString());
// ...
someoneConvId = service.createConversation(someoneId, "Coding Chat", "/code",
        java.util.UUID.randomUUID().toString());

// In createConversationReturnsId test:
long id = service.createConversation(guestId, "New Conv", null, java.util.UUID.randomUUID().toString());
```

- [ ] **Step 8: Run tests**

```bash
./mvnw test
```

Expected: all tests pass including the 4 new repository tests.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/repository/ConversationRepository.java
git add src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git add src/test/java/com/example/agentsuite/jooq/RepositoryTest.java
git add src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java
git commit -m "feat: add external_id and model_change lookup to repositories and ConversationService"
```

---

## Task 6: ChatOrchestrationService

**Files:**
- Create: `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`
- Create: `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`:

```java
package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatOrchestrationServiceTest {

    private ModelRegistry modelRegistry;
    private ConversationService conversationService;
    private ChatService chatService;
    private ChatOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        modelRegistry = mock(ModelRegistry.class);
        conversationService = mock(ConversationService.class);
        chatService = mock(ChatService.class);
        when(modelRegistry.get(anyString())).thenReturn(chatService);
        orchestration = new ChatOrchestrationService(modelRegistry, conversationService);
    }

    @Test
    void statelessMode_whenNoConversationId_doesNotInteractWithDb() {
        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("hello"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStream(any(), any(), any(), any());

        List<ChatEvent> events = new ArrayList<>();
        orchestration.chatStream(null, "deepseek-v4-pro", "Be helpful", "Hi", "",
                events::add, new Object[0]);

        verifyNoInteractions(conversationService);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(ChatEvent.Content.class);
    }

    @Test
    void firstTurn_createsConversationAndInsertsMetadata() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 42L;

        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.empty());
        when(conversationService.createConversation(anyLong(), anyString(), anyString(), eq(externalId)))
                .thenReturn(convDbId);
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Hello!"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any(), any());

        List<ChatEvent> events = new ArrayList<>();
        orchestration.chatStream(externalId, "deepseek-v4-pro", "Be helpful", "Hello",
                "/projects", events::add, new Object[0]);

        verify(conversationService).createConversation(eq(1L), eq("Hello"), eq("/projects"), eq(externalId));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("model_change"), eq("deepseek-v4-pro"));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("system_prompt"), eq("Be helpful"));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("user"), eq("Hello"));
    }

    @Test
    void firstTurn_historyPassedToLlmContainsOnlySystemPrompt() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 7L;

        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.empty());
        when(conversationService.createConversation(anyLong(), anyString(), anyString(), eq(externalId)))
                .thenReturn(convDbId);
        // DB has system_prompt after resolveConversation; loaded before user insert
        MessageRecord sysRecord = mock(MessageRecord.class);
        when(sysRecord.getType()).thenReturn("system_prompt");
        when(sysRecord.getMessage()).thenReturn("Be helpful");
        when(conversationService.getMessages(convDbId)).thenReturn(List.of(sysRecord));

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Hi!"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any(), any());

        orchestration.chatStream(externalId, "deepseek-v4-pro", "Be helpful", "Hello",
                "/projects", e -> {}, new Object[0]);

        @SuppressWarnings("unchecked")
        var historyCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatService).chatStreamWithHistory(historyCaptor.capture(), eq("Hello"), any(), any());
        List<HistoryMessage> history = historyCaptor.getValue();
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isInstanceOf(HistoryMessage.SystemPrompt.class);
        assertThat(((HistoryMessage.SystemPrompt) history.get(0)).content()).isEqualTo("Be helpful");
    }

    @Test
    void subsequentTurn_sameModel_noModelChangeInserted() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 9L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("OK"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any(), any());

        orchestration.chatStream(externalId, "deepseek-v4-pro", "", "Follow up",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService, never()).addMessage(eq(convDbId), anyLong(), eq("model_change"), any());
    }

    @Test
    void subsequentTurn_modelChanged_insertsModelChange() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 11L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.Content("Got it"));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any(), any());

        orchestration.chatStream(externalId, "sonnet-4.6", "", "Continue",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("model_change"), eq("sonnet-4.6"));
    }

    @Test
    void toolBatch_persistedAsToolCallAndToolResult() {
        String externalId = UUID.randomUUID().toString();
        long convDbId = 13L;

        ConversationRecord conv = mock(ConversationRecord.class);
        when(conv.getConversationId()).thenReturn(convDbId);
        when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
        when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
        when(conversationService.getMessages(convDbId)).thenReturn(List.of());

        doAnswer(inv -> {
            java.util.function.Consumer<ChatEvent> emitter = inv.getArgument(2);
            emitter.accept(new ChatEvent.ToolBatch(List.of(
                    new ChatEvent.ToolBatch.ToolExecution("ls", "{\"path\":\".\"}",  "file1\nfile2")
            )));
            emitter.accept(new ChatEvent.Content("Here are the files."));
            emitter.accept(new ChatEvent.Done());
            return null;
        }).when(chatService).chatStreamWithHistory(any(), any(), any(), any());

        orchestration.chatStream(externalId, "deepseek-v4-pro", "", "List files",
                "/projects", e -> {}, new Object[0]);

        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_call"),
                argThat(json -> json.contains("\"name\":\"ls\"")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_result"),
                argThat(json -> json.contains("file1")));
        verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("assistant"),
                eq("Here are the files."));
    }
}
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
./mvnw test -Dtest=ChatOrchestrationServiceTest
```

Expected: compile error (class not found).

- [ ] **Step 3: Implement ChatOrchestrationService**

Create `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`:

```java
package com.example.agentsuite.service;

import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ChatOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrationService.class);
    private static final long GUEST_USER_ID = 1L;

    private final ModelRegistry modelRegistry;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatOrchestrationService(ModelRegistry modelRegistry,
                                     ConversationService conversationService) {
        this.modelRegistry = modelRegistry;
        this.conversationService = conversationService;
    }

    public void chatStream(String conversationId, String model, String systemPrompt,
                           String userMessage, String rootDirectory,
                           Consumer<ChatEvent> emitter, Object[] tools) {

        if (conversationId == null || conversationId.isBlank()) {
            ChatService service = modelRegistry.get(model);
            if (service == null) {
                emitter.accept(new ChatEvent.Error("Unknown model: " + model));
                emitter.accept(new ChatEvent.Done());
                return;
            }
            service.chatStream(systemPrompt, userMessage, emitter, tools);
            return;
        }

        long conversationDbId;
        try {
            conversationDbId = resolveConversation(conversationId, model, systemPrompt, userMessage, rootDirectory);
        } catch (Exception e) {
            log.error("Failed to resolve conversation {}", conversationId, e);
            emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        List<HistoryMessage> history = loadHistory(conversationDbId);

        try {
            conversationService.addMessage(conversationDbId, GUEST_USER_ID, "user", userMessage);
        } catch (Exception e) {
            log.error("Failed to save user message for conversation {}", conversationDbId, e);
            emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        ChatService service = modelRegistry.get(model);
        if (service == null) {
            emitter.accept(new ChatEvent.Error("Unknown model: " + model));
            emitter.accept(new ChatEvent.Done());
            return;
        }

        List<ChatEvent.ToolBatch> toolBatchBuffer = new ArrayList<>();
        StringBuilder contentBuffer = new StringBuilder();
        long convId = conversationDbId;

        service.chatStreamWithHistory(history, userMessage, event -> {
            switch (event) {
                case ChatEvent.ToolBatch tb -> {
                    emitter.accept(event);
                    toolBatchBuffer.add(tb);
                }
                case ChatEvent.Content c -> {
                    emitter.accept(event);
                    contentBuffer.append(c.text());
                }
                case ChatEvent.Done d -> {
                    persistTurnResult(convId, toolBatchBuffer, contentBuffer.toString());
                    emitter.accept(event);
                }
                case ChatEvent.Error e -> emitter.accept(event);
            }
        }, tools);
    }

    private long resolveConversation(String externalId, String model, String systemPrompt,
                                      String userMessage, String rootDirectory) {
        return conversationService.findByExternalId(externalId)
                .map(conv -> {
                    long convId = conv.getConversationId();
                    conversationService.findLastModelChange(convId).ifPresent(lastModel -> {
                        if (!lastModel.equals(model)) {
                            conversationService.addMessage(convId, GUEST_USER_ID, "model_change", model);
                        }
                    });
                    return convId;
                })
                .orElseGet(() -> {
                    String name = userMessage.length() > 80 ? userMessage.substring(0, 80) : userMessage;
                    long convId = conversationService.createConversation(
                            GUEST_USER_ID, name, rootDirectory, externalId);
                    conversationService.addMessage(convId, GUEST_USER_ID, "model_change", model);
                    conversationService.addMessage(convId, GUEST_USER_ID, "system_prompt",
                            systemPrompt != null ? systemPrompt : "");
                    return convId;
                });
    }

    private List<HistoryMessage> loadHistory(long conversationDbId) {
        List<HistoryMessage> history = new ArrayList<>();
        for (MessageRecord r : conversationService.getMessages(conversationDbId)) {
            HistoryMessage msg = switch (r.getType()) {
                case "system_prompt" -> new HistoryMessage.SystemPrompt(r.getMessage());
                case "user"          -> new HistoryMessage.User(r.getMessage());
                case "assistant"     -> new HistoryMessage.Assistant(r.getMessage());
                case "tool_call"     -> new HistoryMessage.ToolCall(r.getMessage());
                case "tool_result"   -> new HistoryMessage.ToolResult(r.getMessage());
                default              -> null;
            };
            if (msg != null) history.add(msg);
        }
        return history;
    }

    private void persistTurnResult(long conversationDbId,
                                    List<ChatEvent.ToolBatch> batches,
                                    String content) {
        try {
            for (ChatEvent.ToolBatch batch : batches) {
                conversationService.addMessage(conversationDbId, GUEST_USER_ID,
                        "tool_call", serializeCalls(batch.executions()));
                conversationService.addMessage(conversationDbId, GUEST_USER_ID,
                        "tool_result", serializeResults(batch.executions()));
            }
            if (!content.isBlank()) {
                conversationService.addMessage(conversationDbId, GUEST_USER_ID, "assistant", content);
            }
        } catch (Exception e) {
            log.error("Failed to persist turn result for conversation {}", conversationDbId, e);
        }
    }

    private String serializeCalls(List<ChatEvent.ToolBatch.ToolExecution> executions) {
        try {
            return objectMapper.writeValueAsString(executions.stream()
                    .map(e -> Map.of("name", e.name(), "arguments", e.arguments()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }

    private String serializeResults(List<ChatEvent.ToolBatch.ToolExecution> executions) {
        try {
            return objectMapper.writeValueAsString(executions.stream()
                    .map(e -> Map.of("name", e.name(), "result", e.result()))
                    .toList());
        } catch (Exception e) {
            return "[]";
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=ChatOrchestrationServiceTest
```

Expected: all 6 tests pass.

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java
git add src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java
git commit -m "feat: add ChatOrchestrationService with conversation lifecycle and message persistence"
```

---

## Task 7: AiController Wiring

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 1: Update AiController to use ChatOrchestrationService**

Replace the entire contents of `AiController.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatEvent;
import com.example.agentsuite.service.ChatOrchestrationService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.Git;
import com.example.agentsuite.tools.MarkDownWriter;
import com.example.agentsuite.tools.UnixTools;
import com.example.agentsuite.tools.WebTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite"
    );

    private final ChatOrchestrationService orchestrationService;
    private final ModelRegistry modelRegistry;
    private final String braveApiKey;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AiController(ChatOrchestrationService orchestrationService,
                        ModelRegistry modelRegistry,
                        @Value("${brave.api-key}") String braveApiKey) {
        this.orchestrationService = orchestrationService;
        this.modelRegistry = modelRegistry;
        this.braveApiKey = braveApiKey;
    }

    @GetMapping("/ai/tools")
    public String executeTool(@RequestParam String command,
                              @RequestParam(defaultValue = "") String rootDirectory) {
        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory)) {
            return "Error: Access to the specified root directory is not allowed.";
        }
        if (rootDirectory.isEmpty()) {
            return "Error: Select a root directory to use this command.";
        }

        List<String> tokens = parseCommand(command);
        if (tokens.isEmpty()) {
            return "Error: No command specified. Use: ls, cat, grep, or git";
        }

        String tool = tokens.getFirst();
        UnixTools unixTools = new UnixTools(rootDirectory);

        return switch (tool) {
            case "ls" -> unixTools.ls(tokens.size() > 1 ? tokens.get(1) : ".");
            case "cat" -> tokens.size() > 1 ? unixTools.cat(tokens.get(1)) : "Error: cat requires a file path";
            case "grep" -> tokens.size() > 2
                    ? unixTools.grep(tokens.get(1), tokens.get(2))
                    : "Error: grep requires search text and file filter";
            case "git" -> {
                if (tokens.size() < 2) {
                    yield "Error: git requires a subcommand: status, add, commit, pull, push, newBranch, checkoutBranch";
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
                    case "pull" -> git.pull();
                    case "newBranch" -> tokens.size() > 2
                            ? git.newBranch(tokens.get(2))
                            : "Error: newBranch requires a branch name";
                    case "checkoutBranch" -> tokens.size() > 2
                            ? git.checkoutBranch(tokens.get(2))
                            : "Error: checkoutBranch requires a branch name";
                    default -> "Error: Unknown git subcommand '" + tokens.get(1)
                            + "'. Use: status, add, commit, pull, push, newBranch, checkoutBranch";
                };
            }
            default -> "Error: Unknown command '" + tool + "'. Use: ls, cat, grep, or git";
        };
    }

    @GetMapping("/ai/config/directories")
    public Set<String> getAllowedDirectories() {
        return ALLOWED_ROOT_DIRECTORIES;
    }

    @RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
    public SseEmitter chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                           @RequestParam(defaultValue = "") String prompt,
                           @RequestParam(defaultValue = "") String rootDirectory,
                           @RequestParam(defaultValue = "deepseek-v4-pro") String model,
                           @RequestParam(defaultValue = "") String tools,
                           @RequestParam(defaultValue = "") String conversationId) {

        SseEmitter emitter = new SseEmitter(300000L);

        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory)) {
            sendEvent(emitter, "error", "Error: Access to the specified root directory is not allowed.");
            emitter.complete();
            return emitter;
        }

        log.info("Chat request - model: {}, conversationId: {}, rootDirectory: {}",
                model, conversationId.isEmpty() ? "(none)" : conversationId, rootDirectory);

        Object[] toolArray = buildToolInstances(tools, rootDirectory, braveApiKey);

        CompletableFuture.runAsync(() -> {
            try {
                orchestrationService.chatStream(
                        conversationId.isEmpty() ? null : conversationId,
                        model, prompt, message, rootDirectory,
                        event -> {
                            switch (event) {
                                case ChatEvent.ToolBatch tb -> {
                                    for (ChatEvent.ToolBatch.ToolExecution e : tb.executions()) {
                                        sendEvent(emitter, "tool_call",
                                                Map.of("name", e.name(), "arguments", e.arguments()));
                                    }
                                }
                                case ChatEvent.Content c -> sendEvent(emitter, "content", c.text());
                                case ChatEvent.Error e -> sendEvent(emitter, "error", e.message());
                                case ChatEvent.Done d -> {
                                    sendEvent(emitter, "done", "");
                                    emitter.complete();
                                }
                            }
                        },
                        toolArray);
            } catch (Exception e) {
                sendEvent(emitter, "error", e.getMessage());
                emitter.complete();
            }
        }, executor);

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    static Object[] buildToolInstances(String tools, String rootDirectory, String braveApiKey) {
        if (tools.isBlank()) return new Object[0];
        if (tools.length() > 512) {
            log.warn("Rejected tools param: length {} exceeds 512 char limit", tools.length());
            return new Object[0];
        }
        List<Object> instances = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String group : tools.split(",")) {
            String g = group.trim();
            if (!seen.add(g)) continue;
            switch (g) {
                case "unix" -> {
                    if (!rootDirectory.isEmpty()) instances.add(new UnixTools(rootDirectory));
                }
                case "md-writer" -> {
                    if (!rootDirectory.isEmpty()) instances.add(new MarkDownWriter(rootDirectory));
                }
                case "web" -> instances.add(new WebTools(braveApiKey));
            }
        }
        return instances.toArray(new Object[0]);
    }

    private List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java
git commit -m "feat: wire ChatOrchestrationService into AiController; add conversationId param"
```

---

## Task 8: Frontend — conversationId

**Files:**
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Update api.ts**

In `frontend/src/api.ts`, replace the `ChatRequest` interface and `chatStream` function:

```typescript
export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
  tools?: string;
  conversationId?: string;
}

export const chatStream = (params: ChatRequest, callbacks: StreamCallbacks): Promise<void> => {
  const urlParams = new URLSearchParams({
    message: params.message,
    prompt: params.prompt || '',
    rootDirectory: params.rootDirectory || '',
    model: params.model || 'deepseek-v4-pro',
    ...(params.tools ? { tools: params.tools } : {}),
    ...(params.conversationId ? { conversationId: params.conversationId } : {}),
  });
  const url = `${API_BASE_URL}/ai/chat?${urlParams.toString()}`;
  // ... rest of function unchanged
```

Only add the `conversationId?: string` field and the spread in `urlParams`. Leave everything else in `chatStream` identical.

- [ ] **Step 2: Update App.tsx — generate UUID and pass conversationId**

In `App.tsx`, add a `useRef` for the conversation UUID. Add this line after the existing `useState` declarations (around line 128):

```typescript
const conversationId = useRef<string>(crypto.randomUUID());
```

Then in the `chatStream(...)` call inside `handleSend` (around line 179), add `conversationId` to the params object:

```typescript
await chatStream(
  {
    message: message,
    prompt: resolvedPrompt,
    rootDirectory: rootDirectory,
    model: model,
    tools: resolvedTools,
    conversationId: conversationId.current,
  },
  { ... }
);
```

- [ ] **Step 3: Verify TypeScript compiles**

```bash
cd frontend && npm run build
```

Expected: BUILD success with no TypeScript errors.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/api.ts frontend/src/App.tsx
git commit -m "feat: generate session UUID and pass conversationId to backend"
```

---

## Task 9: H2 Schema Sync (if tests fail after Task 1)

> Run this task only if `./mvnw test` fails with a column-not-found error for `external_id` after Task 1.

**Files:**
- Locate: `src/test/resources/schema.sql` (or equivalent H2 init file)

- [ ] **Step 1: Find H2 schema file**

```bash
find src/test/resources -name "*.sql"
```

- [ ] **Step 2: Add external_id to conversation table in H2 schema**

In the conversation table CREATE statement, add:

```sql
external_id TEXT NOT NULL DEFAULT ''
```

as a column. Remove the `DEFAULT ''` if other test data explicitly provides the value.

- [ ] **Step 3: Run tests**

```bash
./mvnw test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/schema.sql
git commit -m "fix: add external_id to H2 test schema"
```

---

## Final Verification

- [ ] Start local Supabase: `npx supabase start`
- [ ] Start backend: `./mvnw spring-boot:run`
- [ ] Start frontend: `cd frontend && npm run dev`
- [ ] Open `http://localhost:5176`, send a message, verify no errors in console
- [ ] Send a second message in the same session, verify it works
- [ ] Check database: `SELECT * FROM message ORDER BY message_id;` — confirm system_prompt, user, assistant rows are present
- [ ] Change model in UI, send a message, verify a `model_change` row appears in the DB
