package com.example.agentsuite.tools;

import com.example.agentsuite.config.RootDirectories;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Service
public class McpToolBridge implements DynamicToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ROOT_CONFIG_FILENAME = ".agent-suite-mcp.json";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record McpServerConfig(String command, List<String> args, Map<String, String> env,
                           String url, String transport) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record McpConfig(Map<String, McpServerConfig> mcpServers) {}

    private record ManagedClient(
            AtomicReference<McpSyncClient> ref,
            ReentrantLock lock,
            String serverName,
            McpServerConfig config,
            BiFunction<String, McpServerConfig, McpSyncClient> factory) {

        McpSyncClient get() { return ref.get(); }
    }

    private final Map<ToolSpecification, ToolExecutor> toolEntries;
    private final Map<String, Map<ToolSpecification, ToolExecutor>> rootToolEntries;
    private final List<ManagedClient> clients;
    private final ImageContentHandler imageContentHandler;

    @Autowired
    public McpToolBridge(
            @Value("${mcp.config.path:.mcp.json}") String configPath,
            @Value("${mcp.call-timeout-seconds:90}") int callTimeoutSeconds,
            @Value("${mcp.root-config.enabled:true}") boolean rootConfigEnabled,
            ImageContentHandler imageContentHandler) {
        this(configPath,
                rootConfigEnabled ? RootDirectories.nonEmpty() : Set.of(),
                callTimeoutSeconds,
                (name, cfg) -> defaultCreateClient(name, cfg, callTimeoutSeconds),
                imageContentHandler);
    }

    McpToolBridge(String configPath, Collection<String> rootDirectories, int callTimeoutSeconds,
                  BiFunction<String, McpServerConfig, McpSyncClient> clientFactory,
                  ImageContentHandler imageContentHandler) {
        this.clients = new ArrayList<>();
        this.imageContentHandler = imageContentHandler;
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

    @Override
    public Map<ToolSpecification, ToolExecutor> toolEntries() {
        return toolEntries;
    }

    public List<String> toolNames() {
        return toolNames("");
    }

    public List<String> toolNames(String rootDirectory) {
        return scopedProvider(rootDirectory).toolEntries().keySet().stream()
                .map(ToolSpecification::name)
                .sorted()
                .collect(Collectors.toList());
    }

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
            return new ScopedTools(Collections.unmodifiableMap(toolEntries));
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

    @PreDestroy
    void close() {
        for (ManagedClient managed : clients) {
            managed.lock().lock();
            try {
                managed.get().closeGracefully();
            } catch (Exception e) {
                log.warn("Error closing MCP client", e);
            } finally {
                managed.lock().unlock();
            }
        }
    }

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
                        managed, originalName, req.arguments());

                entries.put(spec, executor);
                log.info("Registered MCP tool: {}{}", namespacedName,
                        rootOrNull != null ? " (root: " + rootOrNull + ")" : "");
            }
        }

        return entries;
    }

    /** Expands the literal {@code ${root}} in command, args, env values, and url to the root directory (forward-slash form). */
    private static McpServerConfig expandRoot(McpServerConfig cfg, String root) {
        String r = root.replace('\\', '/');
        UnaryOperator<String> ex = s -> s == null ? null : s.replace("${root}", r);
        List<String> args = cfg.args() == null ? null
                : cfg.args().stream().map(ex).collect(Collectors.toList());
        Map<String, String> env = null;
        if (cfg.env() != null) {
            env = new HashMap<>();
            for (Map.Entry<String, String> e : cfg.env().entrySet()) {
                env.put(e.getKey(), ex.apply(e.getValue()));
            }
        }
        return new McpServerConfig(ex.apply(cfg.command()), args, env, ex.apply(cfg.url()), cfg.transport());
    }

    private void tryReconnect(ManagedClient managed, McpSyncClient failedClient) {
        if (!managed.lock().tryLock()) {
            log.debug("Reconnect for '{}' already in progress, skipping", managed.serverName());
            return;
        }
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

    @SuppressWarnings("unchecked")
    private String callMcpTool(ManagedClient managed, String toolName,
                                String argumentsJson) {
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

    private static McpSyncClient defaultCreateClient(String serverName, McpServerConfig config, int requestTimeoutSeconds) {
        McpSyncClient client;
        if (config.command() != null) {
            String command = config.command();
            List<String> args = config.args() != null ? new ArrayList<>(config.args()) : new ArrayList<>();

            // On Windows, ProcessBuilder cannot execute .cmd scripts directly.
            // For npx/npm: resolve node.exe + *-cli.js directly — cmd.exe /c breaks the
            // stdin pipe so the MCP server never receives the initialize request.
            // For other commands: fall back to cmd.exe /c.
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                if ("npx".equalsIgnoreCase(command) || "npm".equalsIgnoreCase(command)) {
                    String[] resolved = resolveNodeCliOnWindows(command, args);
                    if (resolved != null) {
                        command = resolved[0];
                        args = new ArrayList<>(Arrays.asList(Arrays.copyOfRange(resolved, 1, resolved.length)));
                    } else {
                        args.add(0, command);
                        args.add(0, "/c");
                        command = "cmd.exe";
                    }
                } else {
                    args.add(0, command);
                    args.add(0, "/c");
                    command = "cmd.exe";
                }
            }

            // Inherit parent-process env (PATH etc.) then overlay any server-specific overrides.
            Map<String, String> env = new HashMap<>(System.getenv());
            if (config.env() != null) env.putAll(config.env());

            ServerParameters params = ServerParameters.builder(command)
                    .args(args)
                    .env(env)
                    .build();
            client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
                    .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .initializationTimeout(Duration.ofSeconds(requestTimeoutSeconds * 2L))
                    .build();
        } else if (config.url() != null) {
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder(config.url()).build())
                    .requestTimeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .initializationTimeout(Duration.ofSeconds(requestTimeoutSeconds * 2L))
                    .build();
        } else {
            throw new IllegalArgumentException("MCP server config must have either 'command' or 'url'");
        }
        client.initialize();
        return client;
    }

    /**
     * Resolves {@code npx} or {@code npm} to {@code [node.exe, <cmd>-cli.js, ...originalArgs]}
     * on Windows, bypassing cmd.exe so the stdio pipe reaches node directly.
     * Looks for {@code npx.cmd} in each PATH directory; node.exe and npx-cli.js are co-located.
     */
    private static String[] resolveNodeCliOnWindows(String npmCommand, List<String> originalArgs) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(";")) {
            String trimmed = dir.trim();
            if (trimmed.isEmpty()) continue;
            File cmdScript = new File(trimmed, npmCommand + ".cmd");
            File nodeExe = new File(trimmed, "node.exe");
            File cliJs = new File(trimmed + File.separator + "node_modules"
                    + File.separator + "npm" + File.separator + "bin"
                    + File.separator + npmCommand + "-cli.js");
            if (cmdScript.exists() && nodeExe.exists() && cliJs.exists()) {
                log.debug("Resolved {} → {} {} on Windows (bypassing cmd.exe)", npmCommand, nodeExe, cliJs);
                List<String> result = new ArrayList<>();
                result.add(nodeExe.getAbsolutePath());
                result.add(cliJs.getAbsolutePath());
                result.addAll(originalArgs);
                return result.toArray(new String[0]);
            }
        }
        log.warn("Could not resolve node.exe+{}-cli.js in PATH; falling back to cmd.exe /c", npmCommand);
        return null;
    }
}
