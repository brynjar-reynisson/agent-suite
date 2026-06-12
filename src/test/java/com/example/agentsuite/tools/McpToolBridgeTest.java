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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpToolBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void toolEntries_noConfigFile_returnsEmpty() {
        McpToolBridge bridge = new McpToolBridge(
                tempDir.resolve("nonexistent.json").toString(), List.of(), 30,
                (name, config) -> { throw new AssertionError("Should not create client"); });

        assertThat(bridge.toolEntries()).isEmpty();
    }

    @Test
    void toolEntries_emptyMcpServers_returnsEmpty() throws IOException {
        Path config = tempDir.resolve(".mcp.json");
        Files.writeString(config, "{\"mcpServers\": {}}");

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
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
                      "command": "nonexistent-binary",
                      "args": []
                    }
                  }
                }""");

        McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
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
                (name, cfg) -> clients.get(name));

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
                (name, cfg) -> mockClientWithTool("t", "d"));

        assertThat(bridge.scopedProvider(root.toString()).toolEntries()).hasSize(1);
    }
}
