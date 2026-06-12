# Per-Root-Directory MCP Servers (Obsidian First) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let agent-suite load MCP servers from `.agent-suite-mcp.json` files inside allowed root directories (Obsidian vault first), exposing those tools only when that root is selected, layered on top of the existing global `.mcp.json`.

**Architecture:** `McpToolBridge` gains a second config layer: at startup it scans every non-empty allowed root directory for `.agent-suite-mcp.json`, connects those servers (reusing the existing client factory), and stores entries per root. A new `scopedProvider(rootDirectory)` returns a `DynamicToolProvider` merging global + per-root tools (per-root wins on name collision), which `AiController.buildToolInstances` passes to the chat services instead of the raw bridge. The root-directory allowlist moves from `AiController` to a new `RootDirectories` class so both classes share it.

**Tech Stack:** Spring Boot 3.5, Java 21, MCP SDK 2.0.0, LangChain4j 1.16.2, JUnit 5 + AssertJ + Mockito. Obsidian server: npm package `obsidian-mcp` (StevenStavrakis, filesystem-based, `npx -y obsidian-mcp <vault-path>`).

**Spec:** `docs/superpowers/specs/2026-06-12-per-root-mcp-servers-design.md`

**Branch:** `feature/per-root-mcp-servers` (created from `main` before Task 1; the plan file is committed on this branch).

**Conventions for executors:**
- Run tests with: `.\mvnw.cmd test -Dtest=<ClassName>` from `C:\Users\Lenovo\IdeaProjects\agent-suite` in PowerShell (NOT bash, NOT `cmd /c` — mvnw.cmd resolution fails there).
- Always `git -C C:/Users/Lenovo/IdeaProjects/agent-suite add <files>` for new files before committing.
- All file paths below are relative to `C:\Users\Lenovo\IdeaProjects\agent-suite` unless absolute.

---

### Task 1: `RootDirectories` config class (relocate the allowlist)

The root-directory allowlist is currently a private constant in `AiController`. `McpToolBridge` will need it too, so it moves to a shared class.

**Files:**
- Create: `src/main/java/com/example/agentsuite/config/RootDirectories.java`
- Create: `src/test/java/com/example/agentsuite/config/RootDirectoriesTest.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java` (constant at lines 43-49; usages at lines 77, 133, 185)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/example/agentsuite/config/RootDirectoriesTest.java`:

```java
package com.example.agentsuite.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RootDirectoriesTest {

    @Test
    void allowed_containsEmptyStringForNoRootSelected() {
        assertThat(RootDirectories.ALLOWED).contains("");
    }

    @Test
    void nonEmpty_excludesEmptyString_keepsAllRealDirectories() {
        assertThat(RootDirectories.nonEmpty())
                .doesNotContain("")
                .hasSize(RootDirectories.ALLOWED.size() - 1)
                .allSatisfy(dir -> assertThat(RootDirectories.ALLOWED).contains(dir));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw.cmd test -Dtest=RootDirectoriesTest`
Expected: COMPILATION ERROR — `RootDirectories` does not exist.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/example/agentsuite/config/RootDirectories.java`:

```java
package com.example.agentsuite.config;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * The allowlist of root directories the AI may operate in. The empty string
 * means "no root selected" and is valid for requests but meaningless for
 * filesystem scanning — use {@link #nonEmpty()} for the latter.
 */
public final class RootDirectories {

    public static final Set<String> ALLOWED = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite",
            "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"
    );

    private RootDirectories() {}

    public static Set<String> nonEmpty() {
        return ALLOWED.stream()
                .filter(dir -> !dir.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw.cmd test -Dtest=RootDirectoriesTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Switch `AiController` to use it**

In `src/main/java/com/example/agentsuite/controller/AiController.java`:

1. Add import: `import com.example.agentsuite.config.RootDirectories;`
2. Delete the whole `ALLOWED_ROOT_DIRECTORIES` constant (lines 43-49):

```java
    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite",
            "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"
    );
```

3. Replace all three usages of `ALLOWED_ROOT_DIRECTORIES` with `RootDirectories.ALLOWED`:
   - in `executeTool`: `if (!RootDirectories.ALLOWED.contains(rootDirectory)) {`
   - in `getAllowedDirectories`: `return RootDirectories.ALLOWED;`
   - in `chat`: `if (!RootDirectories.ALLOWED.contains(rootDirectory)) {`

- [ ] **Step 6: Run the controller tests to verify no regression**

Run: `.\mvnw.cmd test -Dtest=AiControllerTest`
Expected: PASS (all existing tests, no changes needed — pure relocation).

- [ ] **Step 7: Commit**

```powershell
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add src/main/java/com/example/agentsuite/config/RootDirectories.java src/test/java/com/example/agentsuite/config/RootDirectoriesTest.java src/main/java/com/example/agentsuite/controller/AiController.java
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "refactor: move root-directory allowlist to shared RootDirectories class"
```

---

### Task 2: `McpToolBridge` — per-root config loading, `${root}` expansion, `scopedProvider`

The bridge learns to scan allowed roots for `.agent-suite-mcp.json`, connect those servers, and hand out root-scoped `DynamicToolProvider` views. The package-private constructor gains a `rootDirectories` parameter; the five existing tests get that parameter added.

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`
- Modify: `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`
- Modify: `src/test/resources/application.properties` (add `mcp.root-config.enabled=false`)

- [ ] **Step 1: Update the five existing tests to the new constructor signature**

In `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`, every existing `new McpToolBridge(<path>, 30, <factory>)` call becomes `new McpToolBridge(<path>, List.of(), 30, <factory>)`. There are five call sites (in `toolEntries_noConfigFile_returnsEmpty`, `toolEntries_emptyMcpServers_returnsEmpty`, `toolEntries_serverFailsToConnect_skipsServer`, `toolEntries_validServer_returnsNamespacedToolSpec`, `toolEntries_twoServers_returnsAllToolsNamespaced`). Example for the first:

```java
        McpToolBridge bridge = new McpToolBridge(
                tempDir.resolve("nonexistent.json").toString(), List.of(), 30,
                (name, config) -> { throw new AssertionError("Should not create client"); });
```

- [ ] **Step 2: Write the new failing tests**

Add to `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java` (also add imports `java.util.concurrent.atomic.AtomicReference` and `java.nio.file.Path` if missing — `Path` is already imported):

```java
    // --- helpers for per-root tests ---

    private McpSyncClient mockClientWithTool(String toolName, String description) {
        Map<String, Object> emptySchema = Map.of("type", "object");
        McpSchema.ListToolsResult result = new McpSchema.ListToolsResult(
                List.of(McpSchema.Tool.builder(toolName, emptySchema).description(description).build()), null);
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.listTools()).thenReturn(result);
        return client;
    }

    private Path writeRootConfig(String dirName, String json) throws IOException {
        Path root = tempDir.resolve(dirName);
        Files.createDirectories(root);
        Files.writeString(root.resolve(".agent-suite-mcp.json"), json);
        return root;
    }

    @Test
    void scopedProvider_rootWithConfig_mergesGlobalAndRootTools() throws IOException {
        Path globalConfig = tempDir.resolve(".mcp.json");
        Files.writeString(globalConfig, """
                {"mcpServers": {"global-srv": {"command": "g", "args": []}}}""");
        Path root = writeRootConfig("vault", """
                {"mcpServers": {"obsidian": {"command": "npx", "args": ["-y", "obsidian-mcp", "${root}"]}}}""");

        Map<String, McpSyncClient> clients = Map.of(
                "global-srv", mockClientWithTool("g_tool", "Global tool"),
                "obsidian", mockClientWithTool("read_note", "Read a note"));
        McpToolBridge bridge = new McpToolBridge(globalConfig.toString(),
                List.of(root.toString()), 30, (name, cfg) -> clients.get(name));

        // global view unchanged
        assertThat(bridge.toolEntries().keySet().stream().map(ToolSpecification::name))
                .containsExactly("mcp__global-srv__g_tool");
        // scoped view merges both layers
        assertThat(bridge.scopedProvider(root.toString()).toolEntries().keySet().stream()
                .map(ToolSpecification::name))
                .containsExactlyInAnyOrder("mcp__global-srv__g_tool", "mcp__obsidian__read_note");
    }

    @Test
    void scopedProvider_unknownOrEmptyRoot_returnsGlobalOnly() throws IOException {
        Path globalConfig = tempDir.resolve(".mcp.json");
        Files.writeString(globalConfig, """
                {"mcpServers": {"global-srv": {"command": "g", "args": []}}}""");

        McpToolBridge bridge = new McpToolBridge(globalConfig.toString(),
                List.of(), 30, (name, cfg) -> mockClientWithTool("g_tool", "Global tool"));

        assertThat(bridge.scopedProvider("C:/no/such/root").toolEntries()).hasSize(1);
        assertThat(bridge.scopedProvider("").toolEntries()).hasSize(1);
        assertThat(bridge.scopedProvider(null).toolEntries()).hasSize(1);
    }

    @Test
    void rootConfig_expandsRootPlaceholderInArgsEnvAndCommand() throws IOException {
        Path root = writeRootConfig("vault", """
                {"mcpServers": {"obsidian": {
                    "command": "${root}/bin/run",
                    "args": ["-y", "obsidian-mcp", "${root}"],
                    "env": {"VAULT": "${root}/notes"}
                }}}""");

        AtomicReference<McpToolBridge.McpServerConfig> captured = new AtomicReference<>();
        new McpToolBridge(tempDir.resolve("no-global.json").toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> { captured.set(cfg); return mockClientWithTool("t", "d"); });

        String expectedRoot = root.toString().replace('\\', '/');
        assertThat(captured.get().command()).isEqualTo(expectedRoot + "/bin/run");
        assertThat(captured.get().args()).containsExactly("-y", "obsidian-mcp", expectedRoot);
        assertThat(captured.get().env()).containsEntry("VAULT", expectedRoot + "/notes");
    }

    @Test
    void rootConfig_malformedJson_skipsRootGracefully() throws IOException {
        Path root = writeRootConfig("vault", "this is not json");

        McpToolBridge bridge = new McpToolBridge(tempDir.resolve("no-global.json").toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> { throw new AssertionError("Should not create client"); });

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).isEmpty();
    }

    @Test
    void rootConfig_missingFile_rootScopedSameAsGlobal() throws IOException {
        Path root = tempDir.resolve("vault-without-config");
        Files.createDirectories(root);

        McpToolBridge bridge = new McpToolBridge(tempDir.resolve("no-global.json").toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> { throw new AssertionError("Should not create client"); });

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).isEmpty();
    }
```

Note: `rootConfig_expandsRootPlaceholderInArgsEnvAndCommand` requires the `McpServerConfig` record to be visible to the test — it already is (`record` nested in `McpToolBridge`, same package, package-private by default).

- [ ] **Step 3: Run tests to verify the new ones fail**

Run: `.\mvnw.cmd test -Dtest=McpToolBridgeTest`
Expected: COMPILATION ERROR — no constructor with `List` argument, no `scopedProvider` method.

- [ ] **Step 4: Implement in `McpToolBridge`**

Modify `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`:

1. Add imports:

```java
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.function.UnaryOperator;
```

2. Add the filename constant and the per-root field next to the existing fields:

```java
    static final String ROOT_CONFIG_FILENAME = ".agent-suite-mcp.json";

    private final Map<ToolSpecification, ToolExecutor> toolEntries;
    private final Map<String, Map<ToolSpecification, ToolExecutor>> rootToolEntries;
    private final List<McpSyncClient> clients;
```

3. Replace both constructors:

```java
    @Autowired
    public McpToolBridge(
            @Value("${mcp.config.path:.mcp.json}") String configPath,
            @Value("${mcp.call-timeout-seconds:90}") int callTimeoutSeconds,
            @Value("${mcp.root-config.enabled:true}") boolean rootConfigEnabled) {
        this(configPath,
                rootConfigEnabled ? com.example.agentsuite.config.RootDirectories.nonEmpty() : Set.of(),
                callTimeoutSeconds,
                (name, cfg) -> defaultCreateClient(name, cfg, callTimeoutSeconds));
    }

    McpToolBridge(String configPath, Collection<String> rootDirectories, int callTimeoutSeconds,
                  BiFunction<String, McpServerConfig, McpSyncClient> clientFactory) {
        this.clients = new ArrayList<>();
        this.toolEntries = loadEntries(new File(configPath), null, callTimeoutSeconds, clientFactory);

        Map<String, Map<ToolSpecification, ToolExecutor>> scoped = new LinkedHashMap<>();
        for (String root : rootDirectories) {
            File rootConfig = new File(root, ROOT_CONFIG_FILENAME);
            if (!rootConfig.exists()) {
                continue;
            }
            Map<ToolSpecification, ToolExecutor> entries =
                    loadEntries(rootConfig, root, callTimeoutSeconds, clientFactory);
            if (!entries.isEmpty()) {
                scoped.put(root, entries);
                warnOnCollisions(root, entries);
            }
        }
        this.rootToolEntries = scoped;
    }

    private void warnOnCollisions(String root, Map<ToolSpecification, ToolExecutor> rootEntries) {
        Set<String> globalNames = toolEntries.keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        rootEntries.keySet().stream()
                .map(ToolSpecification::name)
                .filter(globalNames::contains)
                .forEach(name -> log.warn(
                        "MCP tool name collision: '{}' defined globally and in root '{}' — root-scoped wins", name, root));
    }
```

(Use a normal import for `RootDirectories` instead of the fully-qualified name: add `import com.example.agentsuite.config.RootDirectories;` and write `RootDirectories.nonEmpty()`.)

4. Rename `buildToolEntries` to `loadEntries` and change its signature/body to take a `File` and an optional root (the body keeps its existing logic; only the marked lines change):

```java
    private Map<ToolSpecification, ToolExecutor> loadEntries(
            File configFile, String rootOrNull, int callTimeoutSeconds,
            BiFunction<String, McpServerConfig, McpSyncClient> clientFactory) {

        McpConfig config;
        try {
            if (!configFile.exists()) {
                log.info("No MCP config found at {} — skipping", configFile);
                return Map.of();
            }
            config = MAPPER.readValue(configFile, McpConfig.class);
        } catch (Exception e) {
            log.warn("Failed to parse MCP config at {}: {}", configFile, e.getMessage());
            return Map.of();
        }

        if (config.mcpServers() == null || config.mcpServers().isEmpty()) {
            return Map.of();
        }

        Map<ToolSpecification, ToolExecutor> entries = new LinkedHashMap<>();

        for (Map.Entry<String, McpServerConfig> serverEntry : config.mcpServers().entrySet()) {
            String serverName = serverEntry.getKey();
            McpServerConfig serverConfig = rootOrNull != null
                    ? expandRoot(serverEntry.getValue(), rootOrNull)
                    : serverEntry.getValue();

            McpSyncClient client;
            try {
                client = clientFactory.apply(serverName, serverConfig);
                clients.add(client);
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                log.error("Failed to connect to MCP server '{}': {} (root: {})", serverName, e.getMessage(), root.getMessage(), e);
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
                        .parameters(McpJsonSchemaConverter.convertMap(tool.inputSchema()))
                        .build();

                ToolExecutor executor = (req, memId) -> callMcpTool(
                        client, serverName, originalName, req.arguments(), callTimeoutSeconds);

                entries.put(spec, executor);
                log.info("Registered MCP tool: {}{}", namespacedName,
                        rootOrNull != null ? " (root: " + rootOrNull + ")" : "");
            }
        }

        return entries;
    }
```

5. Add the `${root}` expansion helper:

```java
    /** Expands the literal {@code ${root}} in command, args, env values, and url to the root directory (forward-slash form). */
    private static McpServerConfig expandRoot(McpServerConfig cfg, String root) {
        String r = root.replace('\\', '/');
        UnaryOperator<String> ex = s -> s == null ? null : s.replace("${root}", r);
        List<String> args = cfg.args() == null ? null
                : cfg.args().stream().map(ex).collect(Collectors.toList());
        Map<String, String> env = cfg.env() == null ? null
                : cfg.env().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> ex.apply(e.getValue())));
        return new McpServerConfig(ex.apply(cfg.command()), args, env, ex.apply(cfg.url()), cfg.transport());
    }
```

6. Add `ScopedTools` and `scopedProvider` after the existing `toolEntries()` method:

```java
    /** Root-scoped view over the bridge's tools: global entries plus the root's entries (root wins on name collision). */
    public record ScopedTools(Map<ToolSpecification, ToolExecutor> entries) implements DynamicToolProvider {
        @Override
        public Map<ToolSpecification, ToolExecutor> toolEntries() {
            return entries;
        }
    }

    public DynamicToolProvider scopedProvider(String rootDirectory) {
        Map<ToolSpecification, ToolExecutor> rootEntries = rootDirectory == null
                ? Map.of()
                : rootToolEntries.getOrDefault(rootDirectory, Map.of());
        if (rootEntries.isEmpty()) {
            return new ScopedTools(toolEntries);
        }
        Set<String> rootNames = rootEntries.keySet().stream()
                .map(ToolSpecification::name)
                .collect(Collectors.toSet());
        Map<ToolSpecification, ToolExecutor> merged = new LinkedHashMap<>();
        toolEntries.forEach((spec, exec) -> {
            if (!rootNames.contains(spec.name())) {
                merged.put(spec, exec);
            }
        });
        merged.putAll(rootEntries);
        return new ScopedTools(Collections.unmodifiableMap(merged));
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=McpToolBridgeTest`
Expected: PASS (10 tests: 5 existing + 5 new).

- [ ] **Step 6: Keep the Spring-context test hermetic**

Add to `src/test/resources/application.properties`:

```properties
mcp.root-config.enabled=false
```

(Without this, `AgentSuiteApplicationTests` context-load would scan the real allowed roots and — once Task 5 creates the vault config — spawn a real `obsidian-mcp` process during `./mvnw test`.)

- [ ] **Step 7: Run the context-load test**

Run: `.\mvnw.cmd test -Dtest=AgentSuiteApplicationTests`
Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add src/main/java/com/example/agentsuite/tools/McpToolBridge.java src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java src/test/resources/application.properties
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: per-root .agent-suite-mcp.json loading with \${root} expansion in McpToolBridge"
```

---

### Task 3: Collision precedence + root-aware `toolNames`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/McpToolBridge.java` (the `toolNames` method)
- Modify: `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `McpToolBridgeTest` (uses the helpers from Task 2):

```java
    @Test
    void scopedProvider_toolNameCollision_rootWins() throws IOException {
        Path globalConfig = tempDir.resolve(".mcp.json");
        Files.writeString(globalConfig, """
                {"mcpServers": {"fs": {"command": "g", "args": []}}}""");
        Path root = writeRootConfig("vault", """
                {"mcpServers": {"fs": {"command": "r", "args": []}}}""");

        // both servers are named "fs" and expose a tool named "search" -> same namespaced name
        McpToolBridge bridge = new McpToolBridge(globalConfig.toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> "g".equals(cfg.command())
                        ? mockClientWithTool("search", "global search")
                        : mockClientWithTool("search", "root search"));

        Map<ToolSpecification, ToolExecutor> merged = bridge.scopedProvider(root.toString()).toolEntries();
        assertThat(merged).hasSize(1);
        ToolSpecification spec = merged.keySet().iterator().next();
        assertThat(spec.name()).isEqualTo("mcp__fs__search");
        assertThat(spec.description()).isEqualTo("root search");
    }

    @Test
    void toolNames_withRoot_returnsMergedSortedNames() throws IOException {
        Path globalConfig = tempDir.resolve(".mcp.json");
        Files.writeString(globalConfig, """
                {"mcpServers": {"zeta": {"command": "g", "args": []}}}""");
        Path root = writeRootConfig("vault", """
                {"mcpServers": {"alpha": {"command": "r", "args": []}}}""");

        Map<String, McpSyncClient> clients = Map.of(
                "zeta", mockClientWithTool("z_tool", "Z"),
                "alpha", mockClientWithTool("a_tool", "A"));
        McpToolBridge bridge = new McpToolBridge(globalConfig.toString(),
                List.of(root.toString()), 30, (name, cfg) -> clients.get(name));

        assertThat(bridge.toolNames(root.toString()))
                .containsExactly("mcp__alpha__a_tool", "mcp__zeta__z_tool");
        assertThat(bridge.toolNames()).containsExactly("mcp__zeta__z_tool");
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\mvnw.cmd test -Dtest=McpToolBridgeTest`
Expected: COMPILATION ERROR — `toolNames(String)` does not exist. (`scopedProvider_toolNameCollision_rootWins` may already pass — the merge logic from Task 2 handles it; that's fine, it locks the behavior in.)

- [ ] **Step 3: Implement `toolNames(String)`**

In `McpToolBridge`, replace the existing `toolNames()` method with:

```java
    public List<String> toolNames() {
        return toolNames("");
    }

    public List<String> toolNames(String rootDirectory) {
        return scopedProvider(rootDirectory).toolEntries().keySet().stream()
                .map(ToolSpecification::name)
                .sorted()
                .collect(Collectors.toList());
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=McpToolBridgeTest`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```powershell
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add src/main/java/com/example/agentsuite/tools/McpToolBridge.java src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: root-aware McpToolBridge.toolNames with per-root collision precedence"
```

---

### Task 4: `AiController` wiring — scoped provider + root-aware `/ai/config/mcp-tools`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java` (`buildToolInstances` mcp case; `getMcpTools` endpoint)
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Update existing tests and write the new failing ones**

In `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`:

1. Add to the `setUpAuth()` `@BeforeEach` (so every chat-flow test gets a real provider object from the mocked bridge):

```java
        lenient().when(mcpToolBridge.scopedProvider(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new McpToolBridge.ScopedTools(Map.of()));
```

(Add `import java.util.Map;` if not already present.)

2. Replace `buildToolInstances_mcpGroup_withBridge_returnsBridge` with:

```java
    @Test
    void buildToolInstances_mcpGroup_withBridge_returnsRootScopedProvider() {
        when(mcpToolBridge.scopedProvider("C:/some/root"))
                .thenReturn(new McpToolBridge.ScopedTools(Map.of()));
        Object[] result = AiController.buildToolInstances("mcp", "C:/some/root", "", mcpToolBridge);
        assertThat(result).hasSize(1);
        assertThat(result[0]).isInstanceOf(McpToolBridge.ScopedTools.class);
        verify(mcpToolBridge).scopedProvider("C:/some/root");
    }
```

(Add `import static org.mockito.Mockito.verify;` if not already present — check first, the file may already have it.)

3. In the two chat-flow assertions that currently check `instanceof McpToolBridge` in the tool array (around lines 536 and 565), change `McpToolBridge` to `McpToolBridge.ScopedTools`:

```java
                        && t[0] instanceof WebTools && t[1] instanceof McpToolBridge.ScopedTools));
```

```java
                        && t[2] instanceof McpToolBridge.ScopedTools
```

4. Update `mcpTools_adminUser_returnsToolNames` to stub the root-aware overload (the endpoint now always passes the rootDirectory param, defaulting to `""`):

```java
    @Test
    void mcpTools_adminUser_returnsToolNames() throws Exception {
        when(mcpToolBridge.toolNames("")).thenReturn(List.of("mcp__server__tool"));
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mcp__server__tool"));
    }
```

5. Add two new endpoint tests:

```java
    @Test
    void mcpTools_adminUser_withRootDirectory_returnsRootScopedNames() throws Exception {
        when(mcpToolBridge.toolNames("C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"))
                .thenReturn(List.of("mcp__obsidian__read_note"));
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("rootDirectory", "C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("mcp__obsidian__read_note"));
    }

    @Test
    void mcpTools_adminUser_disallowedRootDirectory_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/ai/config/mcp-tools")
                        .header("Authorization", ADMIN_BEARER)
                        .param("rootDirectory", "C:/not/allowed"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Run tests to verify the new/changed ones fail**

Run: `.\mvnw.cmd test -Dtest=AiControllerTest`
Expected: FAIL — `buildToolInstances` still returns the raw bridge; `getMcpTools` has no `rootDirectory` param (param test fails with wrong stubbing/status).

- [ ] **Step 3: Implement in `AiController`**

1. In `buildToolInstances`, replace the `"mcp"` case:

```java
                case "mcp" -> {
                    if (mcpToolBridge != null) {
                        DynamicToolProvider scoped = mcpToolBridge.scopedProvider(rootDirectory);
                        if (scoped != null) instances.add(scoped);
                    }
                }
```

(Add `import com.example.agentsuite.service.DynamicToolProvider;`.)

2. Replace the `getMcpTools` endpoint:

```java
    @GetMapping("/ai/config/mcp-tools")
    public ResponseEntity<List<String>> getMcpTools(
            @RequestParam(defaultValue = "") String rootDirectory,
            HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        if (!isAdmin) {
            // mcp is an admin-only tool group; don't disclose the connected tool inventory to others.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!RootDirectories.ALLOWED.contains(rootDirectory)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(mcpToolBridge.toolNames(rootDirectory));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\mvnw.cmd test -Dtest=AiControllerTest`
Expected: PASS (all tests, including the updated chat-flow ones).

- [ ] **Step 5: Commit**

```powershell
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add src/main/java/com/example/agentsuite/controller/AiController.java src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: pass root-scoped MCP provider to chat; root-aware /ai/config/mcp-tools"
```

---

### Task 5: Vault config file, docs, full verification

**Files:**
- Create: `C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian/.agent-suite-mcp.json` (outside the repo — NOT committed)
- Modify: `CLAUDE.md`

- [ ] **Step 1: Create the vault config file**

Write `C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian/.agent-suite-mcp.json`:

```json
{
  "mcpServers": {
    "obsidian": {
      "command": "npx",
      "args": ["-y", "obsidian-mcp", "${root}"]
    }
  }
}
```

- [ ] **Step 2: Smoke-test the obsidian-mcp package standalone**

Run in PowerShell (verifies the npm package exists and accepts the vault path; it speaks MCP over stdio so it will sit waiting for input — kill it after it starts cleanly):

```powershell
$p = Start-Process -FilePath "npx.cmd" -ArgumentList "-y","obsidian-mcp","C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian" -PassThru -NoNewWindow -RedirectStandardError "$env:TEMP\obsidian-mcp-err.txt"; Start-Sleep -Seconds 20; if ($p.HasExited) { Get-Content "$env:TEMP\obsidian-mcp-err.txt" } else { "started OK"; Stop-Process -Id $p.Id -Confirm:$false }
```

Expected: `started OK`. If it exits immediately, read the error output — if the package name or args are wrong (e.g. it requires a `--vault` flag), fix `.agent-suite-mcp.json` accordingly and note the deviation in the final report.

- [ ] **Step 3: Update CLAUDE.md**

In `CLAUDE.md`, **Key layers** section:

1. Extend the `McpToolBridge` bullet — replace:

> `McpToolBridge` — Spring singleton; parses `.mcp.json` at startup, connects MCP servers (stdio + Streamable HTTP via MCP SDK 2.0.0), discovers tools via `tools/list`, builds namespaced `ToolSpecification+ToolExecutor` pairs (`mcp__<serverName>__<toolName>`). `@PreDestroy` closes all connections. Implements `DynamicToolProvider`. Registered as the `"mcp"` tool group (admin-only). Gracefully no-ops when `.mcp.json` is absent.

with:

> `McpToolBridge` — Spring singleton; parses `.mcp.json` at startup, connects MCP servers (stdio + Streamable HTTP via MCP SDK 2.0.0), discovers tools via `tools/list`, builds namespaced `ToolSpecification+ToolExecutor` pairs (`mcp__<serverName>__<toolName>`). Additionally scans each allowed root directory for `<root>/.agent-suite-mcp.json` (same schema; the literal `${root}` in command/args/env/url expands to that directory, forward-slash form) and connects those servers per root — e.g. the Obsidian vault's config runs `npx -y obsidian-mcp ${root}` (filesystem-based, no Obsidian app needed). `scopedProvider(rootDirectory)` returns a `DynamicToolProvider` merging global + that root's tools (per-root wins on name collision, warning logged at startup); `AiController` passes it to the chat services for the `mcp` group, so per-root tools appear only when that root is selected. Per-root scanning is disabled in tests via `mcp.root-config.enabled=false`. `@PreDestroy` closes all connections. Registered as the `"mcp"` tool group (admin-only). Gracefully no-ops when configs are absent or malformed.

2. Add a bullet after `WebConfig`:

> `RootDirectories` — shared allowlist of root directories (previously hardcoded in `AiController`). `ALLOWED` includes `""` (no root selected); `nonEmpty()` is used by `McpToolBridge` for per-root config scanning.

3. In the **API** section, update the `/ai/config/mcp-tools` entry to:

```
GET /ai/config/mcp-tools
  ?rootDirectory=<path>            (optional; must be in allowlist, 400 otherwise)
  Admin-only. Returns JSON array of connected MCP tool names (mcp__<server>__<tool>),
  merged global + per-root for the given rootDirectory. Non-admins get 403.
```

- [ ] **Step 4: Run the full test suite**

Run: `.\mvnw.cmd test`
Expected: PASS (all tests, BUILD SUCCESS).

- [ ] **Step 5: Commit**

```powershell
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add CLAUDE.md
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "docs: per-root MCP servers in CLAUDE.md; obsidian vault config created"
```

---

### Task 6: Dev build + manual test handoff (NOT a subagent task — done by the orchestrator)

- [ ] **Step 1: Build and restart dev servers**

Per the build.cmd workflow (PowerShell steps directly — do not run via bash or `cmd /c`): run the steps from `build.cmd` in PowerShell from the repo root, or run `.\build.cmd` directly in the PowerShell tool if that is what `build.cmd` supports. This rebuilds the JAR and restarts dev (8090/5177) only.

- [ ] **Step 2: Verify startup logs**

Check `logs/agent-suite-dev.log` for lines like:

```
Registered MCP tool: mcp__obsidian__... (root: C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian)
```

and no `Failed to connect to MCP server 'obsidian'` errors.

- [ ] **Step 3: Hand off for manual testing (required before merge)**

Ask the user to verify in the dev UI (http://localhost:5177) as admin:
1. Select the obsidian vault as root directory → ask the model to list/read a note → obsidian MCP tools are called.
2. `GET /ai/config/mcp-tools?rootDirectory=C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian` (admin bearer) → includes `mcp__obsidian__*` names; without the param → global tools only.
3. As guest with the vault root selected → no MCP tools offered.

- [ ] **Step 4: After user confirms, merge via superpowers:finishing-a-development-branch**

---

## Self-Review Notes

- **Spec coverage:** layered config (Task 2), `${root}` expansion (Task 2), distinct filename (Task 2), eager startup (Task 2 ctor), admin-only (unchanged — verified by existing AiControllerTest auth tests), scopedProvider + collision precedence (Tasks 2-3), root-aware endpoint (Task 4), RootDirectories relocation (Task 1), error tolerance (Task 2 tests), vault file + docs (Task 5), manual test (Task 6). No gaps.
- **Type consistency:** `scopedProvider(String) -> DynamicToolProvider` (concrete `McpToolBridge.ScopedTools`), `toolNames()/toolNames(String) -> List<String>`, ctor `(String, Collection<String>, int, BiFunction<String, McpServerConfig, McpSyncClient>)` — used consistently across Tasks 2-4.
- **Known deviation risk:** the exact `obsidian-mcp` CLI contract (Task 5 Step 2 smoke test catches it; fix the JSON config if args differ).
