# Message History Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix four bugs that caused silent duplicate user messages, lost turn state, permanent MCP hangs, and ever-growing system prompts.

**Architecture:** Backend changes to `ChatOrchestrationService` (requestId dedupe, Error-branch persistence, rootDirectory injection), `AiController` (accept requestId, remove effectivePrompt concat), and `McpToolBridge` (AtomicReference per server, reconnect-on-failure). Frontend changes to `api.ts` (onclose handler, error event) and `App.tsx` (requestId generation, error toast).

**Tech Stack:** Spring Boot 3.5 / Java 21, React 19 / TypeScript / Tailwind CSS 4, `@microsoft/fetch-event-source`, Mockito / AssertJ.

---

## File Map

| File | Change |
|------|--------|
| `src/main/java/.../service/ChatOrchestrationService.java` | New `requestId` param; in-memory dedupe; `Error` branch persists partial progress; `loadHistory` takes `rootDirectory` and injects working-directory line fresh |
| `src/main/java/.../controller/AiController.java` | New `requestId` request param; remove `effectivePrompt` concatenation; pass raw `prompt` + `requestId` to orchestration |
| `src/main/java/.../tools/McpToolBridge.java` | Replace `List<McpSyncClient>` with `List<ManagedClient>`; `ManagedClient` wraps `AtomicReference<McpSyncClient>` + `ReentrantLock`; `callMcpTool` reconnects after failure |
| `src/test/.../service/ChatOrchestrationServiceTest.java` | Update all `chatStream(...)` call sites to add `null` requestId arg; update one assertion; add four new tests |
| `src/test/.../tools/McpToolBridgeTest.java` | Add two reconnect tests |
| `frontend/src/api.ts` | Add `requestId` to `ChatRequest`; add `onclose` (throws); handle `'error'` SSE event via optional `onError` callback |
| `frontend/src/App.tsx` | Generate per-send `requestId`; add `errorToast` state + `toastTimerRef`; wire `onError` callback; render dismissing banner |

---

### Task 1: ChatOrchestrationService — update tests first

**Files:**
- Modify: `src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java`

- [ ] **Step 1: Update all existing `chatStream(...)` call sites to add `null` as the new 7th arg (requestId, inserted after `rootDirectory`, before `emitter`)**

  Seven call sites to update — in each, insert `null` between the rootDirectory string and the `e -> {}` / `events::add` lambda:

  Line 47:
  ```java
  orchestration.chatStream(null, 1L, "deepseek-v4-pro", "Be helpful", "Hi", "", null,
          events::add, new Object[0]);
  ```
  Line 73:
  ```java
  orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "Hello",
          "/projects", null, events::add, new Object[0]);
  ```
  Line 103:
  ```java
  orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "Hello",
          "/projects", null, e -> {}, new Object[0]);
  ```
  Line 135:
  ```java
  orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Follow up",
          "/projects", null, e -> {}, new Object[0]);
  ```
  Line 162:
  ```java
  orchestration.chatStream(externalId, 1L, "sonnet-4.6", "", "Continue",
          "/projects", null, e -> {}, new Object[0]);
  ```
  Line 193 (inside `captureHistory` helper):
  ```java
  orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "sys", "q", "", null, e -> {}, new Object[0]);
  ```
  Line 352:
  ```java
  orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "List files",
          "/projects", null, e -> {}, new Object[0]);
  ```

- [ ] **Step 2: Update the one assertion that changes due to rootDirectory injection**

  In `firstTurn_historyPassedToLlmContainsOnlySystemPrompt` (around line 112), change:
  ```java
  assertThat(((HistoryMessage.SystemPrompt) history.get(0)).content()).isEqualTo("Be helpful");
  ```
  to:
  ```java
  assertThat(((HistoryMessage.SystemPrompt) history.get(0)).content())
          .isEqualTo("Be helpful\nWorking directory: /projects");
  ```

- [ ] **Step 3: Add four new tests at the end of the test class**

  ```java
  @Test
  void errorEventPersistsPartialTurnProgress() {
      String externalId = UUID.randomUUID().toString();
      long convDbId = 15L;
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(convDbId);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
      when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
      when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
      when(conversationService.getMessages(convDbId)).thenReturn(List.of());

      doAnswer(inv -> {
          Consumer<ChatEvent> emitter = inv.getArgument(2);
          emitter.accept(new ChatEvent.ToolBatch(List.of(
                  new ChatEvent.ToolBatch.ToolExecution("ls", "{}", "file1"))));
          emitter.accept(new ChatEvent.Error("Tool timed out"));
          return null;
      }).when(chatService).chatStreamWithHistory(any(), any(), any());

      orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "List files",
              "", null, e -> {}, new Object[0]);

      verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_call"),
              argThat(json -> json.contains("\"name\":\"ls\"")));
      verify(conversationService).addMessage(eq(convDbId), eq(1L), eq("tool_result"),
              argThat(json -> json.contains("file1")));
      verify(conversationService, never()).addMessage(eq(convDbId), eq(1L), eq("assistant"), any());
  }

  @Test
  void duplicateRequestId_skipsUserMessageInsert() {
      String externalId = UUID.randomUUID().toString();
      long convDbId = 16L;
      String requestId = UUID.randomUUID().toString();
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(convDbId);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
      when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
      when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of(""));
      when(conversationService.getMessages(convDbId)).thenReturn(List.of());

      doAnswer(inv -> {
          Consumer<ChatEvent> emitter = inv.getArgument(2);
          emitter.accept(new ChatEvent.Content("Hi!"));
          emitter.accept(new ChatEvent.Done());
          return null;
      }).when(chatService).chatStreamWithHistory(any(), any(), any());

      orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Hello",
              "", requestId, e -> {}, new Object[0]);
      orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "", "Hello",
              "", requestId, e -> {}, new Object[0]);

      verify(conversationService, times(1)).addMessage(eq(convDbId), eq(1L), eq("user"), eq("Hello"));
  }

  @Test
  void loadHistory_rootDirectory_appendedFreshToSystemPromptForLlm() {
      String externalId = UUID.randomUUID().toString();
      long convDbId = 17L;
      ConversationRecord conv = mock(ConversationRecord.class);
      when(conv.getConversationId()).thenReturn(convDbId);
      when(conv.getUserId()).thenReturn(1L);
      when(conversationService.findByExternalId(externalId)).thenReturn(Optional.of(conv));
      when(conversationService.findLastModelChange(convDbId)).thenReturn(Optional.of("deepseek-v4-pro"));
      when(conversationService.findLastSystemPrompt(convDbId)).thenReturn(Optional.of("Be helpful"));

      MessageRecord sysRecord = mock(MessageRecord.class);
      when(sysRecord.getType()).thenReturn("system_prompt");
      when(sysRecord.getMessage()).thenReturn("Be helpful");
      when(conversationService.getMessages(convDbId)).thenReturn(List.of(sysRecord));

      doAnswer(inv -> {
          Consumer<ChatEvent> emitter = inv.getArgument(2);
          emitter.accept(new ChatEvent.Done());
          return null;
      }).when(chatService).chatStreamWithHistory(any(), any(), any());

      orchestration.chatStream(externalId, 1L, "deepseek-v4-pro", "Be helpful", "q",
              "/projects", null, e -> {}, new Object[0]);

      @SuppressWarnings("unchecked")
      var captor = org.mockito.ArgumentCaptor.forClass(List.class);
      verify(chatService).chatStreamWithHistory(captor.capture(), eq("q"), any());
      HistoryMessage.SystemPrompt sp = (HistoryMessage.SystemPrompt) captor.getValue().get(0);
      assertThat(sp.content()).isEqualTo("Be helpful\nWorking directory: /projects");
  }

  @Test
  void applyWorkingDirectory_variousCombinations() {
      assertThat(ChatOrchestrationService.applyWorkingDirectory("", "")).isEqualTo("");
      assertThat(ChatOrchestrationService.applyWorkingDirectory("Be helpful", ""))
              .isEqualTo("Be helpful");
      assertThat(ChatOrchestrationService.applyWorkingDirectory("", "/p"))
              .isEqualTo("Working directory: /p");
      assertThat(ChatOrchestrationService.applyWorkingDirectory("Be helpful", "/p"))
              .isEqualTo("Be helpful\nWorking directory: /p");
  }
  ```

- [ ] **Step 4: Run tests — expect compile failure (chatStream signature mismatch)**

  ```
  ./mvnw test -Dtest=ChatOrchestrationServiceTest
  ```
  Expected: compile error — `chatStream` not found with new arg count.

---

### Task 2: ChatOrchestrationService — implement

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java`

- [ ] **Step 1: Add import for ConcurrentHashMap**

  After `import java.util.List;`, add:
  ```java
  import java.util.concurrent.ConcurrentHashMap;
  ```

- [ ] **Step 2: Add the dedupe map field**

  After the existing `OBJECT_MAPPER` field, add:
  ```java
  private final Map<Long, String> lastRequestIdByConversation = new ConcurrentHashMap<>();
  ```

- [ ] **Step 3: Add two static helper methods** (place after `buildTranscript`)

  ```java
  static String applyWorkingDirectory(String prompt, String rootDirectory) {
      String p = prompt != null ? prompt : "";
      if (rootDirectory == null || rootDirectory.isEmpty()) return p;
      return (p.isEmpty() ? "" : p + "\n") + "Working directory: " + rootDirectory;
  }

  private boolean isDuplicateRequest(long conversationDbId, String requestId) {
      if (requestId == null || requestId.isBlank()) return false;
      String previous = lastRequestIdByConversation.put(conversationDbId, requestId);
      return requestId.equals(previous);
  }
  ```

- [ ] **Step 4: Replace the `chatStream` method signature and body**

  Replace the entire `public void chatStream(...)` method with:

  ```java
  public void chatStream(String conversationId, long userId, String model, String systemPrompt,
                         String userMessage, String rootDirectory, String requestId,
                         Consumer<ChatEvent> emitter, Object[] tools) {

      if (conversationId == null || conversationId.isBlank()) {
          ChatService service = modelRegistry.get(model);
          if (service == null) {
              emitter.accept(new ChatEvent.Error("Unknown model: " + model));
              emitter.accept(new ChatEvent.Done());
              return;
          }
          service.chatStream(applyWorkingDirectory(systemPrompt, rootDirectory), userMessage, emitter, tools);
          return;
      }

      long conversationDbId;
      try {
          conversationDbId = resolveConversation(conversationId, userId, model, systemPrompt, userMessage, rootDirectory);
      } catch (Exception e) {
          log.error("Failed to resolve conversation {}", conversationId, e);
          emitter.accept(new ChatEvent.Error("Database error: " + e.getMessage()));
          emitter.accept(new ChatEvent.Done());
          return;
      }

      if (isDuplicateRequest(conversationDbId, requestId)) {
          emitter.accept(new ChatEvent.Done());
          return;
      }

      List<HistoryMessage> history = loadHistory(conversationDbId, rootDirectory);

      try {
          conversationService.addMessage(conversationDbId, userId, "user", userMessage);
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
                  persistTurnResult(convId, userId, toolBatchBuffer, contentBuffer.toString());
                  emitter.accept(event);
              }
              case ChatEvent.Error e -> {
                  persistTurnResult(convId, userId, toolBatchBuffer, contentBuffer.toString());
                  emitter.accept(event);
              }
          }
      }, tools);
  }
  ```

- [ ] **Step 5: Update `loadHistory` to accept and apply `rootDirectory`**

  Change the signature from `private List<HistoryMessage> loadHistory(long conversationDbId)` to `private List<HistoryMessage> loadHistory(long conversationDbId, String rootDirectory)`.

  Replace the block that adds the system prompt to history (currently `if (lastSystemPrompt != null && !lastSystemPrompt.isEmpty()) { ... }`) with:

  ```java
  String combinedSystemPrompt = applyWorkingDirectory(
          lastSystemPrompt != null ? lastSystemPrompt : "", rootDirectory);
  if (!combinedSystemPrompt.isEmpty()) {
      history.add(new HistoryMessage.SystemPrompt(combinedSystemPrompt));
  }
  ```

---

### Task 3: AiController — accept requestId, remove effectivePrompt concat

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 1: Add `requestId` to the `chat()` method parameters**

  In the `@RequestMapping` `chat(...)` method, add after `String conversationId`:
  ```java
  @RequestParam(defaultValue = "") String requestId,
  ```

- [ ] **Step 2: Remove the `effectivePrompt` local variable entirely**

  Delete these lines:
  ```java
  String effectivePrompt = rootDirectory.isEmpty() ? prompt
          : (prompt.isEmpty() ? "" : prompt + "\n") + "Working directory: " + rootDirectory;
  ```

- [ ] **Step 3: Update the `orchestrationService.chatStream(...)` call**

  Change `model, effectivePrompt, message, rootDirectory,` to `model, prompt, message, rootDirectory, requestId.isBlank() ? null : requestId,`:

  ```java
  orchestrationService.chatStream(
          conversationId.isEmpty() ? null : conversationId,
          userId,
          model, prompt, message, rootDirectory, requestId.isBlank() ? null : requestId,
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
  ```

- [ ] **Step 4: Run all backend tests**

  ```
  ./mvnw test
  ```
  Expected: all pass. The compile error from Task 1 Step 4 is now resolved.

- [ ] **Step 5: Commit**

  ```
  git add src/main/java/com/example/agentsuite/service/ChatOrchestrationService.java
  git add src/main/java/com/example/agentsuite/controller/AiController.java
  git add src/test/java/com/example/agentsuite/service/ChatOrchestrationServiceTest.java
  git commit -m "feat: requestId dedupe, Error-branch persistence, rootDirectory injection in history"
  ```

---

### Task 4: McpToolBridge — reconnect-on-failure (write tests first)

**Files:**
- Modify: `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`

- [ ] **Step 1: Add two reconnect tests at the end of the test class**

  ```java
  @Test
  void callMcpTool_afterFailure_nextCallUsesReconnectedClient() throws IOException {
      Path config = tempDir.resolve(".mcp.json");
      Files.writeString(config, """
              {"mcpServers": {"srv": {"command": "x", "args": []}}}""");

      Map<String, Object> emptySchema = Map.of("type", "object");
      McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(
              List.of(McpSchema.Tool.builder("do_thing", emptySchema).description("do").build()), null);

      McpSyncClient firstClient = mock(McpSyncClient.class);
      McpSyncClient secondClient = mock(McpSyncClient.class);
      when(firstClient.listTools()).thenReturn(listResult);
      when(firstClient.callTool(any())).thenThrow(new RuntimeException("connection reset"));

      McpSchema.TextContent textContent = mock(McpSchema.TextContent.class);
      when(textContent.text()).thenReturn("ok");
      when(secondClient.callTool(any())).thenReturn(
              new McpSchema.CallToolResult(List.of(textContent), false, null, null));

      AtomicInteger createCount = new AtomicInteger();
      McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
              (name, cfg) -> createCount.getAndIncrement() == 0 ? firstClient : secondClient, null);

      ToolExecutor executor = bridge.toolEntries().values().iterator().next();
      dev.langchain4j.agent.tool.ToolExecutionRequest req =
              dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                      .name("mcp__srv__do_thing").arguments("{}").build();

      assertThat(executor.execute(req, null)).contains("connection reset");
      assertThat(executor.execute(req, null)).isEqualTo("ok");
      assertThat(createCount.get()).isEqualTo(2);
  }

  @Test
  void callMcpTool_reconnectFactoryThrows_returnsErrorGracefully() throws IOException {
      Path config = tempDir.resolve(".mcp.json");
      Files.writeString(config, """
              {"mcpServers": {"srv": {"command": "x", "args": []}}}""");

      Map<String, Object> emptySchema = Map.of("type", "object");
      McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(
              List.of(McpSchema.Tool.builder("do_thing", emptySchema).description("do").build()), null);

      McpSyncClient deadClient = mock(McpSyncClient.class);
      when(deadClient.listTools()).thenReturn(listResult);
      when(deadClient.callTool(any())).thenThrow(new RuntimeException("timeout"));

      AtomicInteger createCount = new AtomicInteger();
      McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
              (name, cfg) -> {
                  if (createCount.getAndIncrement() == 0) return deadClient;
                  throw new RuntimeException("cannot reconnect");
              }, null);

      ToolExecutor executor = bridge.toolEntries().values().iterator().next();
      dev.langchain4j.agent.tool.ToolExecutionRequest req =
              dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                      .name("mcp__srv__do_thing").arguments("{}").build();

      assertThat(executor.execute(req, null)).contains("timeout");
      assertThat(executor.execute(req, null)).contains("timeout");
  }
  ```

- [ ] **Step 2: Run — expect failures (reconnect not yet implemented)**

  ```
  ./mvnw test -Dtest=McpToolBridgeTest
  ```
  Expected: two new tests fail; existing tests pass.

---

### Task 5: McpToolBridge — implement reconnect

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`

- [ ] **Step 1: Add imports**

  After the existing imports, add:
  ```java
  import java.util.concurrent.atomic.AtomicReference;
  import java.util.concurrent.locks.ReentrantLock;
  ```

- [ ] **Step 2: Add the `ManagedClient` record inside the class**

  Add this private record after the existing `McpConfig` record:
  ```java
  private record ManagedClient(
          AtomicReference<McpSyncClient> ref,
          ReentrantLock lock,
          String serverName,
          McpServerConfig config,
          BiFunction<String, McpServerConfig, McpSyncClient> factory) {

      McpSyncClient get() { return ref.get(); }
  }
  ```

- [ ] **Step 3: Change the `clients` field type from `List<McpSyncClient>` to `List<ManagedClient>`**

  ```java
  private final List<ManagedClient> clients;
  ```

- [ ] **Step 4: Update `loadEntries` to create `ManagedClient` instances**

  Find the block where a new client is connected and stored:
  ```java
  McpSyncClient client;
  try {
      client = clientFactory.apply(serverName, serverConfig);
      clients.add(client);
  } catch (Exception e) { ... }
  ```

  Replace with:
  ```java
  McpSyncClient client;
  try {
      client = clientFactory.apply(serverName, serverConfig);
  } catch (Exception e) {
      Throwable root = e;
      while (root.getCause() != null) root = root.getCause();
      log.error("Failed to connect to MCP server '{}': {} (root: {})", serverName, e.getMessage(), root.getMessage(), e);
      continue;
  }
  ManagedClient managed = new ManagedClient(
          new AtomicReference<>(client), new ReentrantLock(),
          serverName, serverConfig, clientFactory);
  clients.add(managed);
  ```

  Then update the executor lambda (in the same loop, after `listTools`):
  ```java
  ToolExecutor executor = (req, memId) -> callMcpTool(
          managed, originalName, req.arguments(), callTimeoutSeconds);
  ```

- [ ] **Step 5: Add `tryReconnect` helper method**

  ```java
  private void tryReconnect(ManagedClient managed, McpSyncClient failedClient) {
      if (!managed.lock().tryLock()) return;
      try {
          if (managed.ref().get() != failedClient) return;
          try {
              McpSyncClient fresh = managed.factory().apply(managed.serverName(), managed.config());
              managed.ref().set(fresh);
              try { failedClient.closeGracefully(); } catch (Exception ignored) {}
              log.info("Reconnected MCP server '{}'", managed.serverName());
          } catch (Exception e) {
              log.error("Failed to reconnect MCP server '{}': {}", managed.serverName(), e.getMessage());
          }
      } finally {
          managed.lock().unlock();
      }
  }
  ```

- [ ] **Step 6: Update `callMcpTool` signature and body**

  Change signature from `callMcpTool(McpSyncClient client, String serverName, String toolName, ...)` to `callMcpTool(ManagedClient managed, String toolName, ...)`:

  ```java
  @SuppressWarnings("unchecked")
  private String callMcpTool(ManagedClient managed, String toolName,
                              String argumentsJson, int timeoutSeconds) {
      McpSyncClient client = managed.get();
      try {
          Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                  ? MAPPER.readValue(argumentsJson, Map.class)
                  : Map.of();

          McpSchema.CallToolResult result = client.callTool(
                  new McpSchema.CallToolRequest(toolName, args));

          List<String> parts = new ArrayList<>();
          result.content().stream()
                  .filter(c -> c instanceof McpSchema.TextContent)
                  .map(c -> ((McpSchema.TextContent) c).text())
                  .forEach(parts::add);

          if (imageContentHandler != null) {
              result.content().stream()
                      .filter(c -> c instanceof McpSchema.ImageContent)
                      .map(c -> imageContentHandler.handle((McpSchema.ImageContent) c))
                      .forEach(parts::add);
          }

          String output = String.join("\n", parts);
          if (Boolean.TRUE.equals(result.isError())) {
              return "Error from MCP server '" + managed.serverName() + "': " + output;
          }
          return output;
      } catch (Exception e) {
          log.error("MCP tool call failed: server={}, tool={}", managed.serverName(), toolName, e);
          tryReconnect(managed, client);
          return "Error calling MCP tool '" + toolName + "' on server '"
                  + managed.serverName() + "': " + e.getMessage();
      }
  }
  ```

- [ ] **Step 7: Update `@PreDestroy close()` to iterate `ManagedClient`**

  ```java
  @PreDestroy
  void close() {
      for (ManagedClient managed : clients) {
          try { managed.get().closeGracefully(); } catch (Exception e) {
              log.warn("Error closing MCP client", e);
          }
      }
  }
  ```

- [ ] **Step 8: Run all tests**

  ```
  ./mvnw test
  ```
  Expected: all pass.

- [ ] **Step 9: Commit**

  ```
  git add src/main/java/com/example/agentsuite/tools/McpToolBridge.java
  git add src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java
  git commit -m "feat: MCP reconnect-on-failure via per-server AtomicReference + ReentrantLock"
  ```

---

### Task 6: Frontend — api.ts

**Files:**
- Modify: `frontend/src/api.ts`

- [ ] **Step 1: Add `requestId` to `ChatRequest` and `onError` to `StreamCallbacks`**

  ```typescript
  export interface ChatRequest {
    message: string;
    prompt?: string;
    rootDirectory?: string;
    model?: string;
    tools?: string;
    conversationId?: string;
    requestId?: string;
  }

  export interface StreamCallbacks {
    onToolCall: (tc: ToolCall) => void;
    onContent: (text: string) => void;
    onError?: (message: string) => void;
  }
  ```

- [ ] **Step 2: Update `chatStream` — add `requestId` to body, add `onclose` handler, handle `'error'` event**

  Replace the `fetchEventSource` call body:
  ```typescript
  body: new URLSearchParams({
    message: params.message,
    prompt: params.prompt ?? '',
    rootDirectory: params.rootDirectory ?? '',
    model: params.model ?? 'deepseek-v4-pro',
    ...(params.tools ? { tools: params.tools } : {}),
    ...(params.conversationId ? { conversationId: params.conversationId } : {}),
    ...(params.requestId ? { requestId: params.requestId } : {}),
  }),
  onmessage(ev) {
    if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
    if (ev.event === 'content') callbacks.onContent(ev.data);
    if (ev.event === 'error') callbacks.onError?.(ev.data);
    if (ev.event === 'done') controller.abort();
  },
  onclose() {
    throw new Error('Stream closed by server');
  },
  onerror(err) {
    throw err;
  },
  ```

- [ ] **Step 3: Commit**

  ```
  git add frontend/src/api.ts
  git commit -m "feat: add requestId to ChatRequest, onclose to stop auto-retry, wire error event"
  ```

---

### Task 7: Frontend — App.tsx error toast + requestId

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add toast state and timer ref** (alongside the existing `useRef` declarations near line 160)

  ```typescript
  const [errorToast, setErrorToast] = useState<string | null>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  ```

- [ ] **Step 2: Add `onError` handler and `requestId` inside `handleSend`**

  Inside `handleSend`, after `const enabledTools = ...` (around line 333), add:
  ```typescript
  const requestId = crypto.randomUUID();
  const onError = (message: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current);
    setErrorToast(message || 'An error occurred');
    toastTimerRef.current = setTimeout(() => setErrorToast(null), 5000);
  };
  ```

  Then update the `chatStream(...)` call to include `requestId` in params and `onError` in callbacks:
  ```typescript
  await chatStream(
    {
      message: message,
      prompt: resolvedPrompt,
      rootDirectory: rootDirectory,
      model: model,
      tools: enabledTools,
      conversationId: conversationId.current,
      requestId: requestId,
    },
    { onToolCall, onContent, onError },
    token
  );
  ```

- [ ] **Step 3: Render the toast** — add just before the final closing `</div>` of the root component (or at the end of the JSX return, alongside the existing `ImageLightbox` portal):

  ```tsx
  {errorToast && (
    <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 px-4 py-2 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm shadow-lg whitespace-pre-wrap max-w-md text-center">
      {errorToast}
    </div>
  )}
  ```

- [ ] **Step 4: Commit**

  ```
  git add frontend/src/App.tsx
  git commit -m "feat: add per-send requestId and error toast banner"
  ```

---

### Task 8: Final verification

- [ ] **Step 1: Full build and test**

  ```
  ./mvnw test
  ```
  Expected: all pass.

- [ ] **Step 2: Build frontend**

  ```
  cd frontend && npm run build
  ```
  Expected: no TypeScript errors, build succeeds.

- [ ] **Step 3: Manual test — error toast**

  Start dev servers. In the chat UI, send a message with an MCP tool active. Stop the backend mid-request (or temporarily point a tool at a non-existent server). Confirm a red banner appears in the browser and auto-dismisses after ~5 seconds. Confirm no duplicate messages appear when reloading the conversation.

- [ ] **Step 4: Manual test — system prompt fix**

  Open a conversation that previously had a rootDirectory set. Reload the page. Send a follow-up message. Reload again. Confirm the system prompt textarea never shows "Working directory: ..." and that the DB row length doesn't grow across reloads (verify via a direct DB query or the conversation detail endpoint).
