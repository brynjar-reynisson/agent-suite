package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void toolEntries_noConfigFile_returnsEmpty() {
        McpToolBridge bridge = new McpToolBridge(
                tempDir.resolve("nonexistent.json").toString(), List.of(), 30,
                (name, config) -> { throw new AssertionError("Should not create client"); }, null);

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_emptyMcpServers_returnsEmpty() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, "{\"mcpServers\": {}}");

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
                (name, cfg) -> { throw new AssertionError("Should not create client"); }, null);

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_serverFailsToConnect_skipsServer() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {
                  "mcpServers": {
                    "bad-server": {
                      "command": "nonexistent-binary",
                      "args": []
                    }
                  }
                }""");

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
                (name, cfg) -> { throw new RuntimeException("connection refused"); }, null);

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

        // McpSchema.Tool.inputSchema() returns Map<String,Object>, use builder
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "File path")),
                "required", List.of("path"));
        McpSchema.Tool mcpTool = McpSchema.Tool.builder("read_file", inputSchema)
                .description("Read a file")
                .build();
        McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(List.of(mcpTool), null);

        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.listTools()).thenReturn(listResult);

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
                (name, cfg) -> mockClient, null);

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

        Map<String, Object> emptySchema = Map.of("type", "object");
        McpSchema.ListToolsResult result1 = new McpSchema.ListToolsResult(
                List.of(McpSchema.Tool.builder("tool_a", emptySchema).description("Tool A").build()), null);
        McpSchema.ListToolsResult result2 = new McpSchema.ListToolsResult(
                List.of(McpSchema.Tool.builder("tool_b", emptySchema).description("Tool B").build()), null);

        McpSyncClient client1 = mock(McpSyncClient.class);
        McpSyncClient client2 = mock(McpSyncClient.class);
        when(client1.listTools()).thenReturn(result1);
        when(client2.listTools()).thenReturn(result2);

        Map<String, McpSyncClient> clients = Map.of("server1", client1, "server2", client2);
        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
                (name, cfg) -> clients.get(name), null);

        Map<ToolSpecification, ToolExecutor> entries = bridge.toolEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries.keySet().stream().map(ToolSpecification::name))
                .containsExactlyInAnyOrder("mcp__server1__tool_a", "mcp__server2__tool_b");
    }

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
                List.of(root.toString()), 30, (name, cfg) -> clients.get(name), null);

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
                List.of(), 30, (name, cfg) -> mockClientWithTool("g_tool", "Global tool"), null);

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
                (name, cfg) -> { captured.set(cfg); return mockClientWithTool("t", "d"); }, null);

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
                (name, cfg) -> { throw new AssertionError("Should not create client"); }, null);

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).isEmpty();
    }

    @Test
    void rootConfig_missingFile_rootScopedSameAsGlobal() throws IOException {
        Path root = tempDir.resolve("vault-without-config");
        Files.createDirectories(root);

        McpToolBridge bridge = new McpToolBridge(tempDir.resolve("no-global.json").toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> { throw new AssertionError("Should not create client"); }, null);

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).isEmpty();
    }

    @Test
    void rootConfig_nullEnvValue_doesNotThrow() throws IOException {
        Path root = writeRootConfig("vault", """
                {"mcpServers": {"obsidian": {
                    "command": "npx",
                    "args": [],
                    "env": {"FOO": null}
                }}}""");

        McpToolBridge bridge = new McpToolBridge(tempDir.resolve("no-global.json").toString(),
                List.of(root.toString()), 30,
                (name, cfg) -> mockClientWithTool("t", "d"), null);

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).hasSize(1);
    }

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
                        : mockClientWithTool("search", "root search"), null);

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
                List.of(root.toString()), 30, (name, cfg) -> clients.get(name), null);

        assertThat(bridge.toolNames(root.toString()))
                .containsExactly("mcp__alpha__a_tool", "mcp__zeta__z_tool");
        assertThat(bridge.toolNames()).containsExactly("mcp__zeta__z_tool");
    }

    @Test
    void callMcpTool_imageContent_savesFileAndReturnsMarkdownUrl() throws Exception {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, """
                {
                  "mcpServers": {
                    "cc": { "command": "x", "args": [] }
                  }
                }""");

        Map<String, Object> emptySchema = Map.of("type", "object");
        McpSchema.Tool tool = McpSchema.Tool.builder("take_screenshot", emptySchema)
                .description("Take a screenshot").build();
        McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(List.of(tool), null);

        String base64Png = Base64.getEncoder().encodeToString(new byte[]{(byte)0x89,0x50,0x4E,0x47});
        McpSchema.ImageContent imageContent = McpSchema.ImageContent.builder(base64Png, "image/png").build();
        McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(List.of(imageContent), false, null, null);

        McpSyncClient mockClient = mock(McpSyncClient.class);
        when(mockClient.listTools()).thenReturn(listResult);
        when(mockClient.callTool(any())).thenReturn(callResult);

        Path imageDir = tempDir.resolve("images");
        Files.createDirectories(imageDir);
        ImageContentHandler imgHandler = new ImageContentHandler("http://localhost:8090", imageDir.toString());
        imgHandler.init();

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
                (name, cfg) -> mockClient, imgHandler);

        Map<ToolSpecification, ToolExecutor> entries = bridge.toolEntries();
        ToolExecutor executor = entries.values().iterator().next();
        dev.langchain4j.agent.tool.ToolExecutionRequest req =
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("mcp__cc__take_screenshot")
                        .arguments("{}")
                        .build();
        String result = executor.execute(req, null);

        String firstLine = result.lines().findFirst().orElseThrow();
        assertThat(firstLine).startsWith("![screenshot](http://localhost:8090/images/screenshot_");
        assertThat(firstLine).endsWith(".png)");
    }

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
        verify(firstClient).closeGracefully();
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
}
