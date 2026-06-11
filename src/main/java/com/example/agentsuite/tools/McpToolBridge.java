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
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

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

    @Autowired
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
                        .parameters(McpJsonSchemaConverter.convertMap(tool.inputSchema()))
                        .build();

                ToolExecutor executor = (req, memId) -> callMcpTool(
                        client, serverName, originalName, req.arguments(), callTimeoutSeconds);

                entries.put(spec, executor);
                log.info("Registered MCP tool: {}", namespacedName);
            }
        }

        return entries;
    }

    @SuppressWarnings("unchecked")
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
                    .collect(Collectors.joining("\n"));

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
            String command = config.command();
            List<String> args = config.args() != null ? new ArrayList<>(config.args()) : new ArrayList<>();

            // On Windows, ProcessBuilder cannot execute .cmd scripts (e.g. npx.cmd) directly.
            // Wrapping with cmd.exe /c lets the shell resolve the script and inherit PATH.
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                args.add(0, command);
                args.add(0, "/c");
                command = "cmd.exe";
            }

            // Inherit parent-process env (PATH etc.) then overlay any server-specific overrides.
            Map<String, String> env = new HashMap<>(System.getenv());
            if (config.env() != null) env.putAll(config.env());

            ServerParameters params = ServerParameters.builder(command)
                    .args(args)
                    .env(env)
                    .build();
            client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
        } else if (config.url() != null) {
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder(config.url()).build())
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
        } else {
            throw new IllegalArgumentException("MCP server config must have either 'command' or 'url'");
        }
        client.initialize();
        return client;
    }
}
