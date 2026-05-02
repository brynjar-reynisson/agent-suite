# Multi-Provider Chat Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google Gemini and Anthropic Claude alongside the existing DeepSeek integration, routed by a `model` request parameter on `/ai/chat`.

**Architecture:** A `ChatService` interface unifies all three providers. `DeepSeekService` keeps its hand-rolled implementation (LangChain4j reasoning was broken for it). `GoogleChatService` and `AnthropicChatService` use LangChain4j's `ChatLanguageModel` abstractions with their own agentic tool loops. `ModelRegistry` maps user-facing aliases (e.g. `sonnet-4.6`) to pre-configured service instances. `AiController` routes by a new `model` param.

**Tech Stack:** Spring Boot 3.5, LangChain4j 0.36.2 (`langchain4j-anthropic`, `langchain4j-google-ai-gemini`), JUnit 5, Mockito, Spring MVC Test.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Add two LangChain4j provider dependencies |
| `src/main/resources/application.properties` | Modify | Add Google and Anthropic API key properties |
| `src/test/java/com/example/agentsuite/AgentSuiteApplicationTests.java` | Modify | Add test property sources for new keys |
| `src/main/java/com/example/agentsuite/service/ChatService.java` | Create | Interface: `chat(systemPrompt, userMessage, tools...)` |
| `src/main/java/com/example/agentsuite/service/DeepSeekService.java` | Modify | Add `implements ChatService` |
| `src/main/java/com/example/agentsuite/service/AnthropicChatService.java` | Create | LangChain4j Anthropic implementation |
| `src/test/java/com/example/agentsuite/service/AnthropicChatServiceTest.java` | Create | Unit tests for Anthropic service |
| `src/main/java/com/example/agentsuite/service/GoogleChatService.java` | Create | LangChain4j Google Gemini implementation |
| `src/test/java/com/example/agentsuite/service/GoogleChatServiceTest.java` | Create | Unit tests for Google service |
| `src/main/java/com/example/agentsuite/service/ModelRegistry.java` | Create | Maps model aliases to ChatService instances |
| `src/test/java/com/example/agentsuite/service/ModelRegistryTest.java` | Create | Unit tests for registry routing |
| `src/main/java/com/example/agentsuite/controller/AiController.java` | Modify | Use ModelRegistry, add `model` param |
| `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | Create | MVC tests for controller routing |
| `CLAUDE.md` | Modify | Document new `model` param and supported aliases |

---

### Task 1: Add Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the two new LangChain4j dependencies**

In `pom.xml`, inside `<dependencies>`, after the existing `langchain4j-open-ai` dependency add:

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
    <version>${langchain4j.version}</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-google-ai-gemini</artifactId>
    <version>${langchain4j.version}</version>
</dependency>
```

- [ ] **Step 2: Verify dependencies resolve**

Run: `./mvnw dependency:resolve -q`
Expected: BUILD SUCCESS with no errors.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "feat: add langchain4j-anthropic and langchain4j-google-ai-gemini dependencies"
```

---

### Task 2: Add API key properties and fix context load test

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/example/agentsuite/AgentSuiteApplicationTests.java`

- [ ] **Step 1: Add API key properties with empty defaults**

Append to `src/main/resources/application.properties`:

```properties
google.api-key=${GOOGLE_API_KEY:}
anthropic.api-key=${ANTHROPIC_API_KEY:}
```

The `:` suffix means the property defaults to empty string when the env var is absent, preventing context startup failure in CI/test environments where keys are not set.

- [ ] **Step 2: Verify context load test still passes**

Run: `./mvnw test -Dtest=AgentSuiteApplicationTests -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "feat: add Google and Anthropic API key properties"
```

---

### Task 3: Create ChatService interface and make DeepSeekService implement it

**Files:**
- Create: `src/main/java/com/example/agentsuite/service/ChatService.java`
- Modify: `src/main/java/com/example/agentsuite/service/DeepSeekService.java`

- [ ] **Step 1: Create the ChatService interface**

```java
package com.example.agentsuite.service;

public interface ChatService {
    String chat(String systemPrompt, String userMessage, Object... tools);
}
```

- [ ] **Step 2: Make DeepSeekService implement ChatService**

In `DeepSeekService.java`, change the class declaration from:

```java
public class DeepSeekService {
```

to:

```java
public class DeepSeekService implements ChatService {
```

No other changes to `DeepSeekService`.

- [ ] **Step 3: Verify compilation and existing test**

Run: `./mvnw test -Dtest=AgentSuiteApplicationTests -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/ChatService.java \
        src/main/java/com/example/agentsuite/service/DeepSeekService.java
git commit -m "feat: extract ChatService interface, DeepSeekService implements it"
```

---

### Task 4: Create AnthropicChatService (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/service/AnthropicChatServiceTest.java`
- Create: `src/main/java/com/example/agentsuite/service/AnthropicChatService.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnthropicChatServiceTest {

    @Mock
    private ChatLanguageModel mockModel;

    private AnthropicChatService service;

    @BeforeEach
    void setUp() {
        service = new AnthropicChatService(mockModel);
    }

    @Test
    void chat_returnsTextResponse() {
        when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Hello!")));

        String result = service.chat("", "Hi");

        assertThat(result).isEqualTo("Hello!");
    }

    @Test
    void chat_withSystemPrompt_includesSystemMessageFirst() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("You are helpful", "Hi");

        List<ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_emptySystemPrompt_excludesSystemMessage() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("", "Hi");

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_withToolCall_executesToolAndLoops() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("greet")
                .arguments("{\"name\":\"World\"}")
                .build();

        when(mockModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(req))))
                .thenReturn(Response.from(AiMessage.from("I said: Hello, World!")));

        String result = service.chat("", "Greet the world", new EchoTool());

        assertThat(result).isEqualTo("I said: Hello, World!");
        verify(mockModel, times(2)).generate(anyList(), anyList());
    }

    static class EchoTool {
        @Tool("Greet someone by name")
        public String greet(@P("the name to greet") String name) {
            return "Hello, " + name + "!";
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./mvnw test -Dtest=AnthropicChatServiceTest -q`
Expected: COMPILE ERROR — `AnthropicChatService` does not exist yet.

- [ ] **Step 3: Implement AnthropicChatService**

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnthropicChatService implements ChatService {

    private final ChatLanguageModel model;

    public AnthropicChatService(String apiKey, String modelName) {
        this(AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(8192)
                .build());
    }

    AnthropicChatService(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        return loop(messages, toolSpecs, executors);
    }

    private String loop(List<ChatMessage> messages,
                        List<ToolSpecification> toolSpecs,
                        Map<String, ToolExecutor> executors) {
        Response<AiMessage> response = toolSpecs.isEmpty()
                ? model.generate(messages)
                : model.generate(messages, toolSpecs);

        AiMessage aiMessage = response.content();
        if (aiMessage.hasToolExecutionRequests()) {
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String result = executors.get(req.name()).execute(req, "default");
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
            return loop(messages, toolSpecs, executors);
        }
        return aiMessage.text() != null ? aiMessage.text() : "";
    }

    private List<ToolSpecification> buildToolSpecs(Object[] tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object tool : tools) {
            specs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        }
        return specs;
    }

    private Map<String, ToolExecutor> buildExecutors(Object[] tools) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    executors.put(method.getName(), new DefaultToolExecutor(tool, method));
                }
            }
        }
        return executors;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./mvnw test -Dtest=AnthropicChatServiceTest -q`
Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AnthropicChatService.java \
        src/test/java/com/example/agentsuite/service/AnthropicChatServiceTest.java
git commit -m "feat: add AnthropicChatService with LangChain4j agentic tool loop"
```

---

### Task 5: Create GoogleChatService (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/service/GoogleChatServiceTest.java`
- Create: `src/main/java/com/example/agentsuite/service/GoogleChatService.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleChatServiceTest {

    @Mock
    private ChatLanguageModel mockModel;

    private GoogleChatService service;

    @BeforeEach
    void setUp() {
        service = new GoogleChatService(mockModel);
    }

    @Test
    void chat_returnsTextResponse() {
        when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Gemini here!")));

        String result = service.chat("", "Hi");

        assertThat(result).isEqualTo("Gemini here!");
    }

    @Test
    void chat_withSystemPrompt_includesSystemMessageFirst() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("Be concise", "Hi");

        List<ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_emptySystemPrompt_excludesSystemMessage() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("", "Hi");

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_withToolCall_executesToolAndLoops() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("greet")
                .arguments("{\"name\":\"World\"}")
                .build();

        when(mockModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(req))))
                .thenReturn(Response.from(AiMessage.from("I said: Hello, World!")));

        String result = service.chat("", "Greet the world", new EchoTool());

        assertThat(result).isEqualTo("I said: Hello, World!");
        verify(mockModel, times(2)).generate(anyList(), anyList());
    }

    static class EchoTool {
        @Tool("Greet someone by name")
        public String greet(@P("the name to greet") String name) {
            return "Hello, " + name + "!";
        }
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./mvnw test -Dtest=GoogleChatServiceTest -q`
Expected: COMPILE ERROR — `GoogleChatService` does not exist yet.

- [ ] **Step 3: Implement GoogleChatService**

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GoogleChatService implements ChatService {

    private final ChatLanguageModel model;

    public GoogleChatService(String apiKey, String modelName) {
        this(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxOutputTokens(8192)
                .build());
    }

    GoogleChatService(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Object... tools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));

        List<ToolSpecification> toolSpecs = buildToolSpecs(tools);
        Map<String, ToolExecutor> executors = buildExecutors(tools);

        return loop(messages, toolSpecs, executors);
    }

    private String loop(List<ChatMessage> messages,
                        List<ToolSpecification> toolSpecs,
                        Map<String, ToolExecutor> executors) {
        Response<AiMessage> response = toolSpecs.isEmpty()
                ? model.generate(messages)
                : model.generate(messages, toolSpecs);

        AiMessage aiMessage = response.content();
        if (aiMessage.hasToolExecutionRequests()) {
            messages.add(aiMessage);
            for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                String result = executors.get(req.name()).execute(req, "default");
                messages.add(ToolExecutionResultMessage.from(req, result));
            }
            return loop(messages, toolSpecs, executors);
        }
        return aiMessage.text() != null ? aiMessage.text() : "";
    }

    private List<ToolSpecification> buildToolSpecs(Object[] tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Object tool : tools) {
            specs.addAll(ToolSpecifications.toolSpecificationsFrom(tool));
        }
        return specs;
    }

    private Map<String, ToolExecutor> buildExecutors(Object[] tools) {
        Map<String, ToolExecutor> executors = new HashMap<>();
        for (Object tool : tools) {
            for (Method method : tool.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    executors.put(method.getName(), new DefaultToolExecutor(tool, method));
                }
            }
        }
        return executors;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./mvnw test -Dtest=GoogleChatServiceTest -q`
Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/GoogleChatService.java \
        src/test/java/com/example/agentsuite/service/GoogleChatServiceTest.java
git commit -m "feat: add GoogleChatService with LangChain4j agentic tool loop"
```

---

### Task 6: Create ModelRegistry (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/service/ModelRegistryTest.java`
- Create: `src/main/java/com/example/agentsuite/service/ModelRegistry.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.example.agentsuite.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelRegistryTest {

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry(
                mock(DeepSeekService.class),
                "test-anthropic-key",
                "test-google-key"
        );
    }

    @Test
    void get_deepseekAlias_returnsDeepSeekService() {
        assertThat(registry.get("deepseek-v4-pro")).isInstanceOf(DeepSeekService.class);
    }

    @Test
    void get_anthropicAliases_returnAnthropicService() {
        assertThat(registry.get("sonnet-4.6")).isInstanceOf(AnthropicChatService.class);
        assertThat(registry.get("opus-4.7")).isInstanceOf(AnthropicChatService.class);
        assertThat(registry.get("haiku-4.5")).isInstanceOf(AnthropicChatService.class);
    }

    @Test
    void get_googleAliases_returnGoogleService() {
        assertThat(registry.get("gemini-2.5-pro")).isInstanceOf(GoogleChatService.class);
        assertThat(registry.get("gemini-2.5-flash")).isInstanceOf(GoogleChatService.class);
    }

    @Test
    void get_unknownAlias_returnsNull() {
        assertThat(registry.get("gpt-4o")).isNull();
        assertThat(registry.get("")).isNull();
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./mvnw test -Dtest=ModelRegistryTest -q`
Expected: COMPILE ERROR — `ModelRegistry` does not exist yet.

- [ ] **Step 3: Implement ModelRegistry**

```java
package com.example.agentsuite.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ModelRegistry {

    private final Map<String, ChatService> registry;

    public ModelRegistry(DeepSeekService deepSeekService,
                         @Value("${anthropic.api-key}") String anthropicApiKey,
                         @Value("${google.api-key}") String googleApiKey) {
        registry = new HashMap<>();
        registry.put("deepseek-v4-pro", deepSeekService);
        registry.put("sonnet-4.6", new AnthropicChatService(anthropicApiKey, "claude-sonnet-4-6"));
        registry.put("opus-4.7", new AnthropicChatService(anthropicApiKey, "claude-opus-4-7"));
        registry.put("haiku-4.5", new AnthropicChatService(anthropicApiKey, "claude-haiku-4-5-20251001"));
        registry.put("gemini-2.5-pro", new GoogleChatService(googleApiKey, "gemini-2.5-pro"));
        registry.put("gemini-2.5-flash", new GoogleChatService(googleApiKey, "gemini-2.5-flash"));
    }

    public ChatService get(String model) {
        return registry.get(model);
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `./mvnw test -Dtest=ModelRegistryTest -q`
Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/ModelRegistry.java \
        src/test/java/com/example/agentsuite/service/ModelRegistryTest.java
git commit -m "feat: add ModelRegistry mapping model aliases to ChatService instances"
```

---

### Task 7: Update AiController (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatService;
import com.example.agentsuite.service.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModelRegistry modelRegistry;

    @Test
    void chat_defaultModel_usesDeepSeekAndDefaultMessage() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);
        when(mockService.chat("", "Hello, how are you?")).thenReturn("Hi there!");

        mockMvc.perform(get("/ai/chat"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hi there!"));
    }

    @Test
    void chat_specifiedModel_routesToCorrectService() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("sonnet-4.6")).thenReturn(mockService);
        when(mockService.chat("", "Hello")).thenReturn("Claude here");

        mockMvc.perform(get("/ai/chat")
                        .param("model", "sonnet-4.6")
                        .param("message", "Hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Claude here"));
    }

    @Test
    void chat_unknownModel_returnsError() throws Exception {
        when(modelRegistry.get("gpt-4o")).thenReturn(null);

        mockMvc.perform(get("/ai/chat").param("model", "gpt-4o"))
                .andExpect(status().isOk())
                .andExpect(content().string("Error: Unknown model: gpt-4o"));
    }

    @Test
    void chat_disallowedDirectory_returnsError() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);

        mockMvc.perform(get("/ai/chat").param("rootDirectory", "/etc/passwd"))
                .andExpect(status().isOk())
                .andExpect(content().string("Error: Access to the specified root directory is not allowed."));
    }

    @Test
    void chat_withSystemPrompt_passesPromptToService() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);
        when(mockService.chat("Be concise", "Hello")).thenReturn("OK");

        mockMvc.perform(get("/ai/chat")
                        .param("message", "Hello")
                        .param("prompt", "Be concise"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

Run: `./mvnw test -Dtest=AiControllerTest -q`
Expected: Test compilation fails or tests fail because controller still uses `DeepSeekService`.

- [ ] **Step 3: Rewrite AiController**

Replace the entire contents of `src/main/java/com/example/agentsuite/controller/AiController.java`:

```java
package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.UnixTools;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
public class AiController {

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite"
    );

    private final ModelRegistry modelRegistry;

    public AiController(ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry;
    }

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
}
```

- [ ] **Step 4: Run all tests**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java \
        src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: route /ai/chat by model param via ModelRegistry"
```

---

### Task 8: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the API section to document the model param and supported aliases**

In `CLAUDE.md`, replace the entire `## API` section with the following (verbatim):

    ## API

    ```
    GET/POST /ai/chat
      ?message=<user message>          (default: "Hello, how are you?")
      ?prompt=<system prompt>          (default: empty)
      ?rootDirectory=<path>            (default: empty; must be in allowlist)
      ?model=<model alias>             (default: "deepseek-v4-pro")
    ```

    Supported model aliases:

    | Alias | Provider | Requires env var |
    |---|---|---|
    | `deepseek-v4-pro` | DeepSeek (hand-rolled) | `DEEPSEEK_API_KEY` |
    | `sonnet-4.6` | Anthropic Claude Sonnet 4.6 | `ANTHROPIC_API_KEY` |
    | `opus-4.7` | Anthropic Claude Opus 4.7 | `ANTHROPIC_API_KEY` |
    | `haiku-4.5` | Anthropic Claude Haiku 4.5 | `ANTHROPIC_API_KEY` |
    | `gemini-2.5-pro` | Google Gemini 2.5 Pro | `GOOGLE_API_KEY` |
    | `gemini-2.5-flash` | Google Gemini 2.5 Flash | `GOOGLE_API_KEY` |

    Returns plain text AI response.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document model param and supported provider aliases in CLAUDE.md"
```
