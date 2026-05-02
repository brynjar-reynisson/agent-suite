# Multi-Provider Chat Design

**Date:** 2026-05-02  
**Status:** Approved

## Overview

Add Google Gemini and Anthropic Claude support alongside the existing DeepSeek integration. The `DeepSeekService` hand-rolled implementation is preserved as-is (LangChain4j's reasoning support was broken for DeepSeek). The two new providers use LangChain4j abstractions. A `model` request parameter routes each request to the correct provider.

## Architecture

Five components:

| Component | Role |
|---|---|
| `ChatService` | Interface: `chat(String systemPrompt, String userMessage, Object... tools)` |
| `DeepSeekService` | Existing hand-rolled implementation — adds `implements ChatService`, no logic changes |
| `GoogleChatService` | New — LangChain4j `GoogleAiGeminiChatModel`, constructed with a specific model name |
| `AnthropicChatService` | New — LangChain4j `AnthropicChatModel`, constructed with a specific model name |
| `ModelRegistry` | Spring `@Component` — maps user-facing model alias strings to pre-configured `ChatService` instances |

`AiController` replaces its `DeepSeekService` dependency with `ModelRegistry` and gains a `model` request param (default: `deepseek-v4-pro`).

## Model Registry

Aliases registered at startup:

| Alias | Provider | API model identifier |
|---|---|---|
| `deepseek-v4-pro` | DeepSeek (existing) | `deepseek-v4-pro` |
| `sonnet-4.6` | Anthropic | `claude-sonnet-4-6` |
| `opus-4.7` | Anthropic | `claude-opus-4-7` |
| `haiku-4.5` | Anthropic | `claude-haiku-4-5-20251001` |
| `gemini-2.5-pro` | Google | `gemini-2.5-pro` |
| `gemini-2.5-flash` | Google | `gemini-2.5-flash` |

Unknown alias → controller returns `"Error: Unknown model: <name>"`.

`AnthropicChatService` and `GoogleChatService` each accept the model name as a constructor arg, so one class serves all aliases for that provider.

## Google and Anthropic Service Implementation

Both services follow the same pattern:

1. **Construction** — build a LangChain4j `ChatLanguageModel` via the provider's builder. API keys read from `GOOGLE_API_KEY` / `ANTHROPIC_API_KEY` env vars via `@Value`. Temperature `0.1`, max tokens `8192`.

2. **Agentic tool loop:**
   - Convert `Object... tools` → `List<ToolSpecification>` via `ToolSpecifications.toolSpecificationsFrom(toolInstance)`
   - Build `List<ChatMessage>` from system prompt + user message
   - Call `model.generate(messages, toolSpecs)` → `Response<AiMessage>`
   - If `AiMessage.hasToolExecutionRequests()`: execute each via LangChain4j's `DefaultToolExecutor`, append `ToolExecutionResultMessage`s, loop
   - Otherwise return text content

3. **No reasoning cache** — DeepSeek-specific; not needed here.

Tool execution uses LangChain4j's `DefaultToolExecutor`. In LangChain4j 0.36.2, `DefaultToolExecutor` is constructed per tool method (`new DefaultToolExecutor(toolInstance, method)`), so the service builds a `Map<String, ToolExecutor>` (tool name → executor) by iterating `@Tool`-annotated methods on each tool object. This map is built fresh per `chat()` call since tools are passed as arguments.

## Controller Changes

New `model` param added to `/ai/chat` (default: `deepseek-v4-pro`):

```java
@RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
public String chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                   @RequestParam(defaultValue = "") String prompt,
                   @RequestParam(defaultValue = "") String rootDirectory,
                   @RequestParam(defaultValue = "deepseek-v4-pro") String model) {

    ChatService service = modelRegistry.get(model);
    if (service == null) return "Error: Unknown model: " + model;

    if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory))
        return "Error: Access to the specified root directory is not allowed.";

    if (!rootDirectory.isEmpty())
        return service.chat(prompt, message, new UnixTools(rootDirectory));

    return service.chat(prompt, message);
}
```

## Configuration

**`pom.xml`** — two new dependencies at `${langchain4j.version}` (0.36.2):
- `dev.langchain4j:langchain4j-google-ai-gemini`
- `dev.langchain4j:langchain4j-anthropic`

**`application.properties`** additions:
```properties
google.api-key=${GOOGLE_API_KEY}
anthropic.api-key=${ANTHROPIC_API_KEY}
```

## File Locations

New files:
- `src/main/java/com/example/agentsuite/service/ChatService.java`
- `src/main/java/com/example/agentsuite/service/GoogleChatService.java`
- `src/main/java/com/example/agentsuite/service/AnthropicChatService.java`
- `src/main/java/com/example/agentsuite/service/ModelRegistry.java`

Modified files:
- `src/main/java/com/example/agentsuite/service/DeepSeekService.java` — add `implements ChatService`
- `src/main/java/com/example/agentsuite/controller/AiController.java` — swap `DeepSeekService` for `ModelRegistry`, add `model` param
- `pom.xml` — add two dependencies
- `src/main/resources/application.properties` — add two API key properties
