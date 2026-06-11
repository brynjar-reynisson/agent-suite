# LangChain4j Upgrade: 0.36.2 → 1.16.2

**Date:** 2026-06-10  
**Branch:** `feature/update_langchain4j`  
**Scope:** Upgrade all LangChain4j dependencies to 1.16.2, adopt the `AiServices` pattern with convenience annotations, and migrate DeepSeek to `OpenAiChatModel`.

---

## 1. Dependency Changes

Two version properties in `pom.xml`:

| Property | Old | New |
|---|---|---|
| `langchain4j.version` | `0.36.2` | `1.16.2` |
| `langchain4j-spring-boot-starter.version` | _(new property)_ | `1.16.2-beta26` |

The starter tracks a separate beta versioning track; all other `langchain4j-*` modules use `${langchain4j.version}`. The five existing dependencies keep their artifact IDs — no renames between 0.36.2 and 1.16.2 for the modules in use.

---

## 2. Core Architecture: `AbstractLangChain4jChatService`

### What changes

The manual `while(true)` agentic loop is deleted. Each `chatStreamWithHistory()` call instead builds a fresh `AiServices` instance, delegates to it, and collects the result.

### New field type

```java
protected final ChatModel model;  // was ChatLanguageModel
```

### New inner interface

```java
interface AssistantService {
    String chat(@UserMessage String userMessage);
}
```

### `buildAiService()` helper

Called once per `chatStreamWithHistory` invocation:

```java
private AssistantService buildAiService(
        String systemPrompt,
        List<ChatMessage> historyMessages,
        Consumer<ChatEvent> emitter,
        Object[] tools) {

    ChatMemory memory = MessageWindowChatMemory.withMaxMessages(1000);
    for (ChatMessage m : historyMessages) memory.add(m);

    return AiServices.builder(AssistantService.class)
        .chatModel(model)
        .chatMemory(memory)
        .systemMessageProvider(id -> systemPrompt.isBlank() ? null : systemPrompt)
        .toolProvider(buildToolProvider(tools, emitter))
        .build();
}
```

History messages (from DB) are seeded into a fresh `MessageWindowChatMemory`. The memory is discarded after the call — persistence is handled by `ChatOrchestrationService` as before.

### `buildToolProvider()` helper

Wraps each `@Tool`-annotated method executor so it emits a `ToolBatch` event when called:

```java
private ToolProvider buildToolProvider(Object[] tools, Consumer<ChatEvent> emitter) {
    return request -> {
        ToolProviderResult.Builder result = ToolProviderResult.builder();
        for (Object tool : tools) {
            for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(tool)) {
                Method method = findMethod(tool, spec.name());
                ToolExecutor real = new DefaultToolExecutor(tool, method);
                result.add(spec, (req, memId) -> {
                    String out = real.execute(req, memId);
                    emitter.accept(new ChatEvent.ToolBatch(List.of(
                        new ChatEvent.ToolBatch.ToolExecution(req.name(), req.arguments(), out)
                    )));
                    return out;
                });
            }
        }
        return result.build();
    };
}
```

This preserves per-call `ToolBatch` SSE events without proxying or subclassing the tool instances. Each tool call emits a single-item `ToolBatch`; `AiController` already iterates batches, so the frontend sees identical individual `tool_call` SSE events.

`findMethod(tool, spec.name())` is a private helper that walks `tool.getClass().getMethods()` to find the `Method` whose name matches `spec.name()` (the same lookup `buildExecutors()` currently does).

### `chatStreamWithHistory()` body

```java
List<ChatMessage> history = buildMessageList(historyMessages);
AssistantService service = buildAiService(systemPrompt, history, emitter, tools);
try {
    String response = service.chat(userMessage);
    emitter.accept(new ChatEvent.Content(response));
    emitter.accept(new ChatEvent.Done());
} catch (Exception e) {
    emitter.accept(new ChatEvent.Error(e.getMessage()));
}
```

### What is removed

- The `MAX_TOOL_ITERATIONS` constant and loop — AiServices manages iteration internally.
- `buildToolSpecs()`, `buildExecutors()` private helpers — replaced by `buildToolProvider()`.
- Imports for `Response`, `ChatLanguageModel`, `DefaultToolExecutor` (raw), direct `generate()` calls.

### What is kept

- `buildMessageList()`, `parseToolCallRequests()`, `parseToolResults()` — unchanged; they convert `HistoryMessage` → `ChatMessage` for the ChatMemory seed.

---

## 3. DeepSeek Migration

### `DeepSeekService` → `DeepSeekChatService`

The entire hand-rolled `RestClient` + JSON agentic loop is deleted. The new class is a thin subclass of `AbstractLangChain4jChatService`:

```java
public class DeepSeekChatService extends AbstractLangChain4jChatService {

    private final String apiKey;
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;

    @Autowired
    public DeepSeekChatService(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.temperature}") double temperature,
            @Value("${langchain4j.open-ai.chat-model.max-tokens}") int maxTokens) {
        super(buildModel(apiKey, baseUrl, modelName, maxTokens));
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    // Used by withModel() — bypasses Spring injection
    private DeepSeekChatService(ChatModel model, String apiKey, String baseUrl,
                                 double temperature, int maxTokens) {
        super(model);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public DeepSeekChatService withModel(String newModelName) {
        return new DeepSeekChatService(
            buildModel(apiKey, baseUrl, newModelName, maxTokens),
            apiKey, baseUrl, temperature, maxTokens
        );
    }

    private static ChatModel buildModel(String apiKey, String baseUrl,
                                         String modelName, int maxTokens) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }
}
```

**Trade-off:** The `reasoning_content` per-turn caching in `DeepSeekService` is not carried over. DeepSeek tool calls continue to work; reasoning content is simply not injected back between turns.

`ModelRegistry` is updated to construct `DeepSeekChatService` and inject it as the `@Service` bean.

---

## 4. Provider Services

### `AnthropicChatService`, `GoogleChatService`, `MistralChatService`

Two changes each:

1. Constructor parameter type: `ChatLanguageModel` → `ChatModel`
2. Builder API: builder method names are unchanged between 0.36.2 and 1.16.2 for all three providers

The `AnthropicChatService` temperature guard for `opus-4` models is preserved as-is.

---

## 5. What Does Not Change

| Component | Status |
|---|---|
| `ChatService` interface | Unchanged |
| `ChatEvent`, `ChatResponse`, `HistoryMessage` | Unchanged |
| `UnixTools`, `WebTools`, `MarkDownWriter` | Unchanged (`@Tool`/`@P` stay in `dev.langchain4j.agent.tool`) |
| `AiController` | Unchanged |
| `ChatOrchestrationService` | Unchanged |
| `application.properties` | Unchanged |
| `LangChain4jConfig` | Unchanged (empty `@Configuration`) |

---

## 6. Key API Renames (Import Level)

| Old (0.36.2) | New (1.16.2) |
|---|---|
| `dev.langchain4j.model.chat.ChatLanguageModel` | `dev.langchain4j.model.chat.ChatModel` |
| `dev.langchain4j.model.output.Response` | _(removed; `ChatResponse` used internally by AiServices)_ |
| `dev.langchain4j.model.output.Response<AiMessage>` | _(no longer needed in our code)_ |
| `model.generate(messages)` | `AiServices` handles internally |
| `model.generate(messages, toolSpecs)` | `AiServices` + `ToolProvider` handles internally |

New imports added:

```
dev.langchain4j.service.AiServices
dev.langchain4j.service.UserMessage
dev.langchain4j.service.tool.ToolProvider
dev.langchain4j.service.tool.ToolProviderResult
dev.langchain4j.memory.chat.MessageWindowChatMemory
dev.langchain4j.model.openai.OpenAiChatModel
```
