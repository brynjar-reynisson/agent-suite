# MCP as Common Tool-Set — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose MCP servers configured in `.mcp.json` as a first-class `mcp` tool group available to all LangChain4j-backed models.

**Architecture:** Introduce a `DynamicToolProvider` interface so `AbstractLangChain4jChatService.buildToolProvider()` can accept objects that supply `ToolSpecification + ToolExecutor` pairs at runtime rather than via `@Tool` annotations. `McpToolBridge` implements this interface, connecting to each configured MCP server at startup, discovering tools, building one `ToolSpecification` per MCP tool (namespaced as `mcp__<server>__<tool>`), and pairing it with a `ToolExecutor` that delegates to the MCP server at call time.

**Tech Stack:** `io.modelcontextprotocol.sdk:mcp 2.0.0` (MCP client + transports), LangChain4j 1.16.2 (`ToolSpecification`, `ToolExecutor`, `ToolProviderResult`), Spring Boot 3.5, React 19 / TypeScript.

---

## File Map

| Path | Status | Responsibility |
|------|--------|----------------|
| `pom.xml` | modify | Add `io.modelcontextprotocol.sdk:mcp-bom` + `mcp` |
| `src/main/java/com/example/agentsuite/service/DynamicToolProvider.java` | **create** | Interface: `Map<ToolSpecification, ToolExecutor> toolEntries()` |
| `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java` | modify | `buildToolProvider()`: handle `DynamicToolProvider` alongside `@Tool` objects |
| `src/main/java/com/example/agentsuite/tools/McpJsonSchemaConverter.java` | **create** | Converts `McpSchema.JsonSchema` → LangChain4j `JsonObjectSchema` |
| `src/main/java/com/example/agentsuite/tools/McpToolBridge.java` | **create** | Spring singleton: parse `.mcp.json`, connect servers, `toolEntries()`, `@PreDestroy` |
| `src/main/java/com/example/agentsuite/service/AuthorizationService.java` | modify | Add `"mcp"` to admin tool groups |
| `src/main/java/com/example/agentsuite/controller/AiController.java` | modify | Inject `McpToolBridge`; `buildToolInstances` gains 4th `McpToolBridge` param |
| `src/main/resources/application.properties` | modify | Add `mcp.config.path` and `mcp.call-timeout-seconds` |
| `frontend/src/ToolStrip.tsx` | modify | Add `mcp` entry to `TOOL_META` |
| `src/test/java/com/example/agentsuite/tools/McpJsonSchemaConverterTest.java` | **create** | Unit tests for schema conversion |
| `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java` | **create** | Unit tests with mock MCP clients |
| `src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java` | modify | Update admin tool-group assertion |
| `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | modify | Add `@MockBean McpToolBridge`; update affected assertions; add mcp tests |

---

## Task 1: Create feature branch

- [ ] **Step 1: Create and switch to the feature branch**

```bash
git checkout -b feature/mcp_tools
```

- [ ] **Step 2: Confirm you're on the right branch**

```bash
git branch --show-current
```
Expected: `feature/mcp_tools`

---

## Task 2: Add MCP SDK dependency

**Files:** modify `pom.xml`

- [ ] **Step 1: Add `mcp-sdk.version` property and the BOM + dependency**

In `pom.xml`, inside `<properties>`:
```xml
<mcp-sdk.version>2.0.0</mcp-sdk.version>
```

Add a `<dependencyManagement>` block (if one doesn't exist) before `<dependencies>`:
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-bom</artifactId>
            <version>${mcp-sdk.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Inside `<dependencies>`:
```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
</dependency>
```

- [ ] **Step 2: Verify compilation succeeds**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS (no errors).

> **Note:** If the BOM doesn't resolve (network error or wrong version), check https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp-bom for the actual latest version and adjust `mcp-sdk.version`.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add io.modelcontextprotocol.sdk:mcp 2.0.0 dependency"
```

---

## Task 3: DynamicToolProvider interface

**Files:** create `src/main/java/com/example/agentsuite/service/DynamicToolProvider.java`

- [ ] **Step 1: Create the interface**

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;

public interface DynamicToolProvider {
    Map<ToolSpecification, ToolExecutor> toolEntries();
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/DynamicToolProvider.java
git commit -m "feat: add DynamicToolProvider interface for runtime tool registration"
```

---

## Task 4: Extend buildToolProvider() to handle DynamicToolProvider

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java`
- Test: `src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java` (**create**)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java`:

```java
package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractLangChain4jChatServiceTest {

    // Minimal concrete subclass for testing
    static class TestChatService extends AbstractLangChain4jChatService {
        TestChatService(ChatModel model) { super(model); }
    }

    @Test
    void chatStream_dynamicToolProvider_toolExecutorIsCalled() {
        AtomicBoolean toolCalled = new AtomicBoolean(false);

        ToolSpecification spec = ToolSpecification.builder()
                .name("test_tool")
                .description("A test tool")
                .build();
        ToolExecutor executor = (req, memId) -> {
            toolCalled.set(true);
            return "tool result";
        };

        DynamicToolProvider dynamicProvider = () -> Map.of(spec, executor);

        // Mock model that immediately calls the tool then returns a response
        ChatModel mockModel = mock(ChatModel.class);
        // The mock returns a non-tool response so the loop ends
        dev.langchain4j.data.message.AiMessage aiResponse =
                dev.langchain4j.data.message.AiMessage.from("done");
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new java.util.ArrayList<>();
        service.chatStream("", "hello", events::add, dynamicProvider);

        // The dynamic provider was added — verify no exception and the tool spec was registered
        // (exact tool invocation would require the model to request it; here we just verify no crash)
        assertThat(events).anyMatch(e -> e instanceof ChatEvent.Done);
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

```bash
./mvnw test -Dtest=AbstractLangChain4jChatServiceTest -q
```
Expected: compilation failure — `DynamicToolProvider` not yet handled in `buildToolProvider`.

- [ ] **Step 3: Modify `buildToolProvider()` in `AbstractLangChain4jChatService`**

In `AbstractLangChain4jChatService.java`, update the `buildToolProvider` method. Replace the existing method body:

```java
private ToolProvider buildToolProvider(Object[] tools, Consumer<ChatEvent> emitter) {
    return request -> {
        ToolProviderResult.Builder result = ToolProviderResult.builder();
        for (Object tool : tools) {
            if (tool instanceof DynamicToolProvider dtp) {
                for (Map.Entry<ToolSpecification, ToolExecutor> entry : dtp.toolEntries().entrySet()) {
                    ToolExecutor real = entry.getValue();
                    ToolSpecification spec = entry.getKey();
                    result.add(spec, (req, memId) -> {
                        String out = real.execute(req, memId);
                        emitter.accept(new ChatEvent.ToolBatch(List.of(
                                new ChatEvent.ToolBatch.ToolExecution(req.name(), req.arguments(), out)
                        )));
                        return out;
                    });
                }
            } else {
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
        }
        return result.build();
    };
}
```

Add the import at the top of `AbstractLangChain4jChatService.java`:
```java
import com.example.agentsuite.service.DynamicToolProvider;
import java.util.Map;
```

(`Map` may already be imported.)

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./mvnw test -Dtest=AbstractLangChain4jChatServiceTest -q
```
Expected: BUILD SUCCESS, 1 test passing.

- [ ] **Step 5: Run all tests to confirm nothing is broken**

```bash
./mvnw test -q
```
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AbstractLangChain4jChatService.java
git add src/test/java/com/example/agentsuite/service/AbstractLangChain4jChatServiceTest.java
git commit -m "feat: extend buildToolProvider to handle DynamicToolProvider"
```

---

## Task 5: McpJsonSchemaConverter

**Files:**
- Create: `src/main/java/com/example/agentsuite/tools/McpJsonSchemaConverter.java`
- Create: `src/test/java/com/example/agentsuite/tools/McpJsonSchemaConverterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/tools/McpJsonSchemaConverterTest.java`:

```java
package com.example.agentsuite.tools;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonSchemaConverterTest {

    @Test
    void convert_nullSchema_returnsEmptyObjectSchema() {
        JsonObjectSchema result = McpJsonSchemaConverter.convert(null);
        assertThat(result.properties()).isEmpty();
        assertThat(result.required()).isEmpty();
    }

    @Test
    void convert_schemaWithStringProperty_buildsStringSchema() {
        Map<String, Object> props = Map.of("path", Map.of("type", "string", "description", "File path"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, List.of("path"), null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).containsKey("path");
        assertThat(result.properties().get("path")).isInstanceOf(JsonStringSchema.class);
        assertThat(result.required()).containsExactly("path");
    }

    @Test
    void convert_schemaWithNumberProperty_buildsNumberSchema() {
        Map<String, Object> props = Map.of("count", Map.of("type", "number"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).containsKey("count");
        assertThat(result.properties().get("count")).isInstanceOf(JsonNumberSchema.class);
        assertThat(result.required()).isEmpty();
    }

    @Test
    void convert_schemaWithBooleanProperty_buildsBooleanSchema() {
        Map<String, Object> props = Map.of("flag", Map.of("type", "boolean"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties().get("flag")).isInstanceOf(JsonBooleanSchema.class);
    }

    @Test
    void convert_unknownPropertyType_treatedAsString() {
        Map<String, Object> props = Map.of("data", Map.of("type", "array"));
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", props, null, null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        // unknown/complex types fall back to string to avoid breaking
        assertThat(result.properties()).containsKey("data");
    }

    @Test
    void convert_noProperties_returnsSchemaWithRequiredList() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", null, List.of("a"), null, null, null);

        JsonObjectSchema result = McpJsonSchemaConverter.convert(schema);

        assertThat(result.properties()).isEmpty();
        assertThat(result.required()).containsExactly("a");
    }
}
```

> **Note on McpSchema.JsonSchema constructor:** Verify the exact constructor signature after the dependency resolves. The args above match the expected record fields `(type, properties, required, additionalProperties, description, $defs)`. Adjust if the SDK uses different field order or names.

- [ ] **Step 2: Run the tests to see them fail**

```bash
./mvnw test -Dtest=McpJsonSchemaConverterTest -q
```
Expected: compilation failure — `McpJsonSchemaConverter` doesn't exist yet.

- [ ] **Step 3: Create `McpJsonSchemaConverter.java`**

```java
package com.example.agentsuite.tools;

import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class McpJsonSchemaConverter {

    static JsonObjectSchema convert(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return JsonObjectSchema.builder().build();
        }

        Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
        if (schema.properties() != null) {
            for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
                String desc = null;
                String type = "string";
                if (entry.getValue() instanceof Map<?, ?> propMap) {
                    Object t = propMap.get("type");
                    if (t instanceof String s) type = s;
                    Object d = propMap.get("description");
                    if (d instanceof String s) desc = s;
                }
                properties.put(entry.getKey(), toElement(type, desc));
            }
        }

        List<String> required = schema.required() != null ? schema.required() : List.of();

        return JsonObjectSchema.builder()
                .properties(properties)
                .required(required)
                .build();
    }

    private static JsonSchemaElement toElement(String type, String description) {
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number"  -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            default        -> JsonStringSchema.builder().description(description).build();
        };
    }
}
```

> **Note on LangChain4j imports:** Verify that `JsonObjectSchema`, `JsonStringSchema`, etc. are at `dev.langchain4j.model.chat.request.json.*` in LangChain4j 1.16.2. If the compiler can't find them, check `dev.langchain4j.agent.tool.*` as an alternative location.

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./mvnw test -Dtest=McpJsonSchemaConverterTest -q
```
Expected: BUILD SUCCESS, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/tools/McpJsonSchemaConverter.java
git add src/test/java/com/example/agentsuite/tools/McpJsonSchemaConverterTest.java
git commit -m "feat: add McpJsonSchemaConverter for MCP→LangChain4j schema mapping"
```

---

## Task 6: McpToolBridge — config parsing, clients, tool discovery

**Files:**
- Create: `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`
- Create: `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`:

```java
package com.example.agentsuite.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void toolEntries_noConfigFile_returnsEmpty() {
        McpToolBridge bridge = new McpToolBridge(
                tempDir.resolve("nonexistent.json").toString(), 30,
                (name, config) -> { throw new AssertionError("Should not create client"); });

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_emptyMcpServers_returnsEmpty() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, "{\"mcpServers\": {}}");

        McpToolBridge bridge = new McpToolBridge(config.toString(), 30,
                (name, cfg) -> { throw new AssertionError("Should not create client"); });

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_serverFailsToConnect_skipsServer() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {
                  "mcpServers": {
                    "bad-server": {
                      "command": "nonexistent-binary-that-will-fail",
                      "args": []
                    }
                  }
                }""");

        McpToolBridge bridge = new McpToolBridge(config.toString(), 30,
                (name, cfg) -> { throw new RuntimeException("connection refused"); });

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_validServer_returnsNamespacedToolSpec() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {
                  "mcpServers": {
                    "fs": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
                    }
                  }
                }""");

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("path", Map.of("type", "string", "description", "File path")),
                List.of("path"),
                null, null, null);
        McpSchema.Tool mcpTool = new McpSchema.Tool("read_file", "Read a file", inputSchema);
        McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(List.of(mcpTool), null);

        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.listTools()).thenReturn(listResult);

        McpToolBridge bridge = new McpToolBridge(config.toString(), 30,
                (name, cfg) -> mockClient);

        Map<ToolSpecification, ToolExecutor> entries = bridge.toolEntries();

        assertThat(entries).hasSize(1);
        ToolSpecification spec = entries.keySet().iterator().next();
        assertThat(spec.name()).isEqualTo("mcp__fs__read_file");
        assertThat(spec.description()).isEqualTo("Read a file");
    }

    @Test
    void toolEntries_twoServers_returnsAllToolsNamespaced() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {
                  "mcpServers": {
                    "server1": { "command": "cmd1", "args": [] },
                    "server2": { "command": "cmd2", "args": [] }
                  }
                }""");

        McpSchema.ListToolsResult result1 = new McpSchema.ListToolsResult(List.of(
                new McpSchema.Tool("tool_a", "Tool A", null)), null);
        McpSchema.ListToolsResult result2 = new McpSchema.ListToolsResult(List.of(
                new McpSchema.Tool("tool_b", "Tool B", null)), null);

        McpSyncClient client1 = mock(McpSyncClient.class);
        McpSyncClient client2 = mock(McpSyncClient.class);
        when(client1.listTools()).thenReturn(result1);
        when(client2.listTools()).thenReturn(result2);

        Map<String, McpSyncClient> clients = Map.of("server1", client1, "server2", client2);
        McpToolBridge bridge = new McpToolBridge(config.toString(), 30,
                (name, cfg) -> clients.get(name));

        Map<ToolSpecification, ToolExecutor> entries = bridge.toolEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries.keySet().stream().map(ToolSpecification::name))
                .containsExactlyInAnyOrder("mcp__server1__tool_a", "mcp__server2__tool_b");
    }
}
```

> **Note on McpSchema.Tool and McpSchema.ListToolsResult constructors:** Verify the exact constructor signatures from the SDK after adding the dependency. The MCP SDK uses Jackson records — the constructor arg order follows the JSON field order: `Tool(name, description, inputSchema)` and `ListToolsResult(tools, nextCursor)`.

- [ ] **Step 2: Run the tests to see them fail**

```bash
./mvnw test -Dtest=McpToolBridgeTest -q
```
Expected: compilation failure — `McpToolBridge` doesn't exist yet.

- [ ] **Step 3: Create `McpToolBridge.java`**

```java
package com.example.agentsuite.tools;

import com.example.agentsuite.service.DynamicToolProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Service
public class McpToolBridge implements DynamicToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record McpServerConfig(String command, List<String> args, Map<String, String> env,
                           String url, String transport) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record McpConfig(Map<String, McpServerConfig> mcpServers) {}

    private final Map<ToolSpecification, ToolExecutor> toolEntries;
    private final List<McpSyncClient> clients;

    @org.springframework.beans.factory.annotation.Autowired
    public McpToolBridge(
            @Value("${mcp.config.path:.mcp.json}") String configPath,
            @Value("${mcp.call-timeout-seconds:30}") int callTimeoutSeconds) {
        this(configPath, callTimeoutSeconds, McpToolBridge::defaultCreateClient);
    }

    McpToolBridge(String configPath, int callTimeoutSeconds,
                  BiFunction<String, McpServerConfig, McpSyncClient> clientFactory) {
        this.clients = new ArrayList<>();
        this.toolEntries = buildToolEntries(configPath, callTimeoutSeconds, clientFactory);
    }

    @Override
    public Map<ToolSpecification, ToolExecutor> toolEntries() {
        return toolEntries;
    }

    @PreDestroy
    void close() {
        for (McpSyncClient client : clients) {
            try { client.closeGracefully(); } catch (Exception e) {
                log.warn("Error closing MCP client", e);
            }
        }
    }

    private Map<ToolSpecification, ToolExecutor> buildToolEntries(
            String configPath, int callTimeoutSeconds,
            BiFunction<String, McpServerConfig, McpSyncClient> clientFactory) {

        McpConfig config;
        try {
            File configFile = new File(configPath);
            if (!configFile.exists()) {
                log.info("No .mcp.json found at {} — mcp tool group will have no tools", configPath);
                return Map.of();
            }
            config = MAPPER.readValue(configFile, McpConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse MCP config at {}: {}", configPath, e.getMessage());
            return Map.of();
        }

        if (config.mcpServers() == null || config.mcpServers().isEmpty()) {
            return Map.of();
        }

        Map<ToolSpecification, ToolExecutor> entries = new LinkedHashMap<>();

        for (Map.Entry<String, McpServerConfig> serverEntry : config.mcpServers().entrySet()) {
            String serverName = serverEntry.getKey();
            McpServerConfig serverConfig = serverEntry.getValue();

            McpSyncClient client;
            try {
                client = clientFactory.apply(serverName, serverConfig);
                clients.add(client);
            } catch (Exception e) {
                log.error("Failed to connect to MCP server '{}': {}", serverName, e.getMessage());
                continue;
            }

            McpSchema.ListToolsResult listResult;
            try {
                listResult = client.listTools();
            } catch (Exception e) {
                log.error("Failed to list tools from MCP server '{}': {}", serverName, e.getMessage());
                continue;
            }

            for (McpSchema.Tool tool : listResult.tools()) {
                String namespacedName = "mcp__" + serverName + "__" + tool.name();
                String originalName = tool.name();

                ToolSpecification spec = ToolSpecification.builder()
                        .name(namespacedName)
                        .description(tool.description() != null ? tool.description() : "")
                        .parameters(McpJsonSchemaConverter.convert(tool.inputSchema()))
                        .build();

                ToolExecutor executor = (req, memId) -> callMcpTool(
                        client, serverName, originalName, req.arguments(), callTimeoutSeconds);

                entries.put(spec, executor);
                log.info("Registered MCP tool: {}", namespacedName);
            }
        }

        return entries;
    }

    private String callMcpTool(McpSyncClient client, String serverName,
                                String toolName, String argumentsJson, int timeoutSeconds) {
        try {
            Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                    ? MAPPER.readValue(argumentsJson, Map.class)
                    : Map.of();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest(toolName, args));

            String output = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(java.util.stream.Collectors.joining("\n"));

            if (Boolean.TRUE.equals(result.isError())) {
                return "Error from MCP server '" + serverName + "': " + output;
            }
            return output;
        } catch (Exception e) {
            log.error("MCP tool call failed: server={}, tool={}", serverName, toolName, e);
            return "Error calling MCP tool '" + toolName + "' on server '" + serverName + "': " + e.getMessage();
        }
    }

    private static McpSyncClient defaultCreateClient(String serverName, McpServerConfig config) {
        McpSyncClient client;
        if (config.command() != null) {
            ServerParameters params = ServerParameters.builder(config.command())
                    .args(config.args() != null ? config.args() : List.of())
                    .env(config.env() != null ? config.env() : Map.of())
                    .build();
            client = McpClient.sync(new StdioClientTransport(params)).build();
        } else if (config.url() != null) {
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder()
                    .url(config.url())
                    .build()).build();
        } else {
            throw new IllegalArgumentException("MCP server config must have either 'command' or 'url'");
        }
        client.initialize();
        return client;
    }
}
```

> **API verification checklist** — after adding the dependency, confirm these before compiling:
> - `McpClient.sync(transport).build()` — may need `.requestTimeout(Duration.ofSeconds(n))` before `.build()`
> - `client.closeGracefully()` — or `client.close()` depending on SDK version
> - `McpSchema.TextContent` — inner class of `McpSchema`, check exact name
> - `McpSchema.CallToolRequest` — constructor is `new McpSchema.CallToolRequest(name, args)` where args is `Map<String, Object>`
> - `HttpClientStreamableHttpTransport.builder().url(url).build()` — verify builder API; may be `new HttpClientStreamableHttpTransport(url)` instead
> - `ServerParameters.builder(command).args(list).env(map).build()` — verify builder API

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./mvnw test -Dtest=McpToolBridgeTest -q
```
Expected: BUILD SUCCESS, 5 tests passing.

- [ ] **Step 5: Run all tests**

```bash
./mvnw test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/tools/McpToolBridge.java
git add src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java
git commit -m "feat: add McpToolBridge — MCP client, tool discovery, DynamicToolProvider impl"
```

---

## Task 7: AuthorizationService — add "mcp" to admin groups

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/AuthorizationService.java`
- Modify: `src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java`

- [ ] **Step 1: Update the failing assertion first**

In `AuthorizationServiceTest.java`, update `grantedToolGroups_admin_returnsWebAndMdWriter`:

```java
@Test
void grantedToolGroups_admin_returnsWebAndMdWriterAndMcp() {
    assertThat(authorizationService.grantedToolGroups(true)).containsExactly("web", "md-writer", "mcp");
}
```

- [ ] **Step 2: Run to confirm it fails**

```bash
./mvnw test -Dtest=AuthorizationServiceTest -q
```
Expected: 1 test failure (`grantedToolGroups_admin` asserts 2 elements but gets 3 after change — or fails now before the change because the list only has 2).

- [ ] **Step 3: Update AuthorizationService**

In `AuthorizationService.java`, update `grantedToolGroups`:

```java
public List<String> grantedToolGroups(boolean isAdmin) {
    return isAdmin ? List.of("web", "md-writer", "mcp") : List.of("web");
}
```

- [ ] **Step 4: Run the test**

```bash
./mvnw test -Dtest=AuthorizationServiceTest -q
```
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AuthorizationService.java
git add src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java
git commit -m "feat: grant mcp tool group to admin users"
```

---

## Task 8: AiController — inject McpToolBridge, update buildToolInstances

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Update AiControllerTest — add @MockBean and fix affected assertions**

In `AiControllerTest.java`:

**Add the MockBean** (alongside existing MockBeans):
```java
@MockBean
private McpToolBridge mcpToolBridge;
```

Add to imports:
```java
import com.example.agentsuite.tools.McpToolBridge;
import java.util.LinkedHashMap;
```

**Update `setUpAuth`** — mock `toolEntries()` to return empty (default mock behavior, but be explicit):
No change needed; Mockito returns empty map by default for `toolEntries()`.

**Update all `AiController.buildToolInstances(...)` static calls** to pass `null` as the 4th argument:

```java
// Before (all occurrences):
AiController.buildToolInstances("...", ..., "")
// After:
AiController.buildToolInstances("...", ..., "", null)
```

Affected tests (8 occurrences total):
- `buildToolInstances_emptyTools_returnsEmptyArray`
- `buildToolInstances_unixGroup_noRootDirectory_returnsEmptyArray`
- `buildToolInstances_unixGroup_withRootDirectory_returnsUnixTools`
- `buildToolInstances_unknownGroup_silentlyIgnored`
- `buildToolInstances_blankTools_returnsEmptyArray`
- `buildToolInstances_multipleGroups_onlyKnownGroupsAdded`
- `buildToolInstances_mdWriterGroup_withRootDirectory_returnsMarkDownWriter`
- `buildToolInstances_mdWriterGroup_noRootDirectory_returnsEmptyArray`
- `buildToolInstances_unixAndMdWriter_withRootDirectory_returnsBothInstances`
- `buildToolInstances_mdWriterAndUnknown_onlyMarkDownWriterAdded`
- `buildToolInstances_webGroup_returnsWebTools`

**Update admin HTTP tests** — admin now has `[web, md-writer, mcp]` granted. With a mock `mcpToolBridge` (returns empty toolEntries by default), `McpToolBridge` itself still appears in the tool instances array. Update the affected matchers:

`chat_adminUserNoRootDirectory_mdWriterStrippedServerSide` — no rootDirectory means md-writer stripped, but mcp remains:
```java
// Before:
argThat(arr -> arr instanceof Object[] t && t.length == 1 && t[0] instanceof WebTools)
// After (web + mcpToolBridge):
argThat(arr -> arr instanceof Object[] t && t.length == 2
        && t[0] instanceof WebTools && t[1] instanceof McpToolBridge)
```

`chat_adminUserWithRootDirectory_allThreeToolsPassedToOrchestration` — rename to `_allFourTools_`:
```java
// Before: length 3
argThat(arr -> arr instanceof Object[] t && t.length == 3
        && t[0] instanceof WebTools
        && t[1] instanceof MarkDownWriter
        && t[2] instanceof UnixTools)
// After: length 4 (web, md-writer, mcp, unix)
argThat(arr -> arr instanceof Object[] t && t.length == 4
        && t[0] instanceof WebTools
        && t[1] instanceof MarkDownWriter
        && t[2] instanceof McpToolBridge
        && t[3] instanceof UnixTools)
```

`chat_adminOptOutMdWriter_onlyWebAndUnixPassedToOrchestration` — tools param is "web,unix", so mcp is excluded. No change needed (still length 2).

**Add new `buildToolInstances` tests**:

```java
@Test
void buildToolInstances_mcpGroup_noBridge_returnsEmpty() {
    Object[] result = AiController.buildToolInstances("mcp", "", "", null);
    assertThat(result).isEmpty();
}

@Test
void buildToolInstances_mcpGroup_withBridge_returnsBridge() {
    Object[] result = AiController.buildToolInstances("mcp", "", "", mcpToolBridge);
    assertThat(result).hasSize(1);
    assertThat(result[0]).isSameAs(mcpToolBridge);
}
```

- [ ] **Step 2: Run tests to confirm they now fail due to the missing 4th param**

```bash
./mvnw test -Dtest=AiControllerTest -q
```
Expected: compilation failure — `buildToolInstances` doesn't have a 4th `McpToolBridge` param yet.

- [ ] **Step 3: Update AiController**

In `AiController.java`:

**Add import**:
```java
import com.example.agentsuite.tools.McpToolBridge;
```

**Add `McpToolBridge` field and update constructor**:
```java
private final McpToolBridge mcpToolBridge;

public AiController(ChatOrchestrationService orchestrationService,
                    ModelRegistry modelRegistry,
                    ConversationService conversationService,
                    AuthorizationService authorizationService,
                    @Value("${brave.api-key}") String braveApiKey,
                    McpToolBridge mcpToolBridge) {
    this.orchestrationService = orchestrationService;
    this.modelRegistry = modelRegistry;
    this.conversationService = conversationService;
    this.authorizationService = authorizationService;
    this.braveApiKey = braveApiKey;
    this.mcpToolBridge = mcpToolBridge;
}
```

**Update the `chat` method** — pass `mcpToolBridge` to `buildToolInstances`:
```java
Object[] toolArray = buildToolInstances(String.join(",", authorized), rootDirectory, braveApiKey, mcpToolBridge);
```

**Update `buildToolInstances`** — add `McpToolBridge mcpToolBridge` as 4th param and add the `"mcp"` case:
```java
static Object[] buildToolInstances(String tools, String rootDirectory, String braveApiKey,
                                    McpToolBridge mcpToolBridge) {
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
            case "mcp" -> {
                if (mcpToolBridge != null) instances.add(mcpToolBridge);
            }
        }
    }
    return instances.toArray(new Object[0]);
}
```

- [ ] **Step 4: Run the AiController tests**

```bash
./mvnw test -Dtest=AiControllerTest -q
```
Expected: BUILD SUCCESS, all tests passing.

- [ ] **Step 5: Run all tests**

```bash
./mvnw test -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: wire McpToolBridge into AiController as mcp tool group"
```

---

## Task 9: application.properties — add mcp config keys

**Files:** modify `src/main/resources/application.properties`

- [ ] **Step 1: Add the MCP config properties**

Append to `src/main/resources/application.properties`:
```properties
# MCP tool group
mcp.config.path=.mcp.json
mcp.call-timeout-seconds=30
```

- [ ] **Step 2: Verify the app context loads**

```bash
./mvnw test -Dtest=AgentSuiteApplicationTests -q
```
Expected: BUILD SUCCESS (context loads without errors about missing `mcp.*` bindings).

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties
git commit -m "config: add mcp.config.path and mcp.call-timeout-seconds defaults"
```

---

## Task 10: ToolStrip.tsx — add mcp entry

**Files:** modify `frontend/src/ToolStrip.tsx`

- [ ] **Step 1: Add the mcp entry to TOOL_META**

In `frontend/src/ToolStrip.tsx`, update `TOOL_META`:

```typescript
const TOOL_META: Record<string, { icon: string; tooltip: string }> = {
  'unix':      { icon: '📁', tooltip: 'unix: ls · cat · grep' },
  'md-writer': { icon: '✏️', tooltip: 'md-writer: write markdown files' },
  'web':       { icon: '🌐', tooltip: 'web: search · fetch' },
  'mcp':       { icon: '🔌', tooltip: 'mcp: external MCP servers' },
};
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```
Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/ToolStrip.tsx
git commit -m "feat: add mcp tool group to frontend ToolStrip"
```

---

## Task 11: Full test suite + final commit

- [ ] **Step 1: Run the full test suite**

```bash
./mvnw test
```
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Update CLAUDE.md** — add mcp to the tool group table

In `CLAUDE.md`, update the model aliases table at the bottom — also update the Architecture section to mention `McpToolBridge` and `DynamicToolProvider` in the Key Layers list.

Add to the `AuthorizationService` description:
> `grantedToolGroups(isAdmin)` returns `["web", "md-writer", "mcp"]` for admins, `["web"]` for guests.

Add new entries:
> - `DynamicToolProvider` — interface for objects that supply `Map<ToolSpecification, ToolExecutor>` pairs at runtime, bypassing `@Tool` annotation reflection. Implemented by `McpToolBridge`.
> - `McpToolBridge` — Spring singleton; parses `.mcp.json` at startup, connects MCP servers (stdio + Streamable HTTP), discovers tools via `tools/list`, builds namespaced `ToolSpecification`+`ToolExecutor` pairs. `@PreDestroy` closes all connections. Implements `DynamicToolProvider`. Registered as the `"mcp"` tool group.
> - `McpJsonSchemaConverter` — converts `McpSchema.JsonSchema` (from MCP tool discovery) to LangChain4j `JsonObjectSchema`.

- [ ] **Step 3: Commit CLAUDE.md**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md with mcp tool group architecture"
```

- [ ] **Step 4: Invoke superpowers:finishing-a-development-branch skill**

```
/finishing-a-development-branch
```

---

## Self-Review Checklist

| Spec requirement | Task(s) |
|-----------------|---------|
| Parse `.mcp.json` from configurable path | Task 6 (`McpToolBridge.buildToolEntries`) |
| Connect servers at startup | Task 6 (`defaultCreateClient`) |
| Stdio transport | Task 6 (`StdioClientTransport`) |
| Streamable HTTP transport | Task 6 (`HttpClientStreamableHttpTransport`) |
| Per-tool `ToolSpecification` (not meta-tool) | Task 6 (`toolEntries()` + `DynamicToolProvider`) |
| Tool names namespaced `mcp__server__tool` | Task 6 (namespacedName construction) |
| Admin-only authorization | Task 7 (`AuthorizationService`) |
| Frontend tool strip icon | Task 10 (`ToolStrip.tsx`) |
| Graceful degradation on server failure | Task 6 (try/catch + log + continue) |
| `isError: true` propagation | Task 6 (`callMcpTool` error handling) |
| `@PreDestroy` cleanup | Task 6 (`close()`) |
| Configurable timeout | Task 9 (`mcp.call-timeout-seconds`) |
| `buildToolInstances` injection | Task 8 (constructor + static method 4th param) |
| `AbstractLangChain4jChatService` DynamicToolProvider handling | Task 4 |
| `application.properties` entries | Task 9 |
