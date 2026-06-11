package com.example.agentsuite.controller;

import com.example.agentsuite.filter.UserResolverFilter;
import com.example.agentsuite.jooq.service.ConversationService;
import com.example.agentsuite.service.AuthorizationService;
import com.example.agentsuite.service.ChatEvent;
import com.example.agentsuite.service.ChatOrchestrationService;
import com.example.agentsuite.service.ModelRegistry;
import com.example.agentsuite.tools.Git;
import com.example.agentsuite.tools.MarkDownWriter;
import com.example.agentsuite.tools.McpToolBridge;
import com.example.agentsuite.tools.UnixTools;
import com.example.agentsuite.tools.WebTools;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = {"http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org"})
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private static final Set<String> ALLOWED_ROOT_DIRECTORIES = Set.of(
            "",
            "C:/Users/Lenovo/misc_projects/dragon",
            "C:/Users/Lenovo/misc_projects/gexplorer",
            "C:/Users/Lenovo/IdeaProjects/agent-suite"
    );

    private final ChatOrchestrationService orchestrationService;
    private final ModelRegistry modelRegistry;
    private final ConversationService conversationService;
    private final AuthorizationService authorizationService;
    private final String braveApiKey;
    private final McpToolBridge mcpToolBridge;
    private final ExecutorService executor = Executors.newCachedThreadPool();

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

    @GetMapping("/ai/tools")
    public String executeTool(@RequestParam String command,
                              @RequestParam(defaultValue = "") String rootDirectory) {
        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory)) {
            return "Error: Access to the specified root directory is not allowed.";
        }
        if (rootDirectory.isEmpty()) {
            return "Error: Select a root directory to use this command.";
        }

        List<String> tokens = parseCommand(command);
        if (tokens.isEmpty()) {
            return "Error: No command specified. Use: ls, cat, grep, or git";
        }

        String tool = tokens.getFirst();
        UnixTools unixTools = new UnixTools(rootDirectory);

        return switch (tool) {
            case "ls" -> unixTools.ls(tokens.size() > 1 ? tokens.get(1) : ".");
            case "cat" -> tokens.size() > 1 ? unixTools.cat(tokens.get(1)) : "Error: cat requires a file path";
            case "grep" -> tokens.size() > 2
                    ? unixTools.grep(tokens.get(1), tokens.get(2))
                    : "Error: grep requires search text and file filter";
            case "git" -> {
                if (tokens.size() < 2) {
                    yield "Error: git requires a subcommand: status, add, commit, pull, push, newBranch, checkoutBranch";
                }
                Git git = new Git(rootDirectory);
                yield switch (tokens.get(1)) {
                    case "status" -> git.status();
                    case "add" -> tokens.size() > 2
                            ? git.add(tokens.get(2))
                            : "Error: add requires a file path";
                    case "commit" -> tokens.size() > 2
                            ? git.commit(String.join(" ", tokens.subList(2, tokens.size())))
                            : "Error: commit requires a message";
                    case "push" -> git.push();
                    case "pull" -> git.pull();
                    case "newBranch" -> tokens.size() > 2
                            ? git.newBranch(tokens.get(2))
                            : "Error: newBranch requires a branch name";
                    case "checkoutBranch" -> tokens.size() > 2
                            ? git.checkoutBranch(tokens.get(2))
                            : "Error: checkoutBranch requires a branch name";
                    default -> "Error: Unknown git subcommand '" + tokens.get(1)
                            + "'. Use: status, add, commit, pull, push, newBranch, checkoutBranch";
                };
            }
            default -> "Error: Unknown command '" + tool + "'. Use: ls, cat, grep, or git";
        };
    }

    @GetMapping("/ai/config/directories")
    public Set<String> getAllowedDirectories() {
        return ALLOWED_ROOT_DIRECTORIES;
    }

    @GetMapping("/ai/config/user")
    public Map<String, Object> getUserConfig(HttpServletRequest request) {
        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        return Map.of(
                "isAdmin", isAdmin,
                "grantedToolGroups", authorizationService.grantedToolGroups(isAdmin)
        );
    }

    @GetMapping("/ai/config/mcp-tools")
    public List<String> getMcpTools() {
        return mcpToolBridge.toolNames();
    }

    @GetMapping("/ai/conversations")
    public List<ConversationSummaryDto> getConversations(HttpServletRequest request) {
        long userId = currentUserId(request);
        return conversationService.getConversationSummaries(userId);
    }

    @GetMapping("/ai/conversations/{externalId}")
    public ResponseEntity<ConversationDetailDto> getConversationDetail(
            @PathVariable String externalId,
            HttpServletRequest request) {
        try {
            long userId = currentUserId(request);
            return ResponseEntity.ok(conversationService.getConversationDetail(externalId, userId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @RequestMapping(path = "/ai/chat", method = {RequestMethod.GET, RequestMethod.POST})
    public SseEmitter chat(@RequestParam(defaultValue = "Hello, how are you?") String message,
                           @RequestParam(defaultValue = "") String prompt,
                           @RequestParam(defaultValue = "") String rootDirectory,
                           @RequestParam(defaultValue = "deepseek-v4-pro") String model,
                           @RequestParam(defaultValue = "") String tools,
                           @RequestParam(defaultValue = "") String conversationId,
                           HttpServletRequest request) {

        SseEmitter emitter = new SseEmitter(300000L);
        long userId = currentUserId(request);

        if (!ALLOWED_ROOT_DIRECTORIES.contains(rootDirectory)) {
            sendEvent(emitter, "error", "Error: Access to the specified root directory is not allowed.");
            emitter.complete();
            return emitter;
        }

        if (!conversationId.isEmpty() && !conversationId.matches("[0-9a-fA-F\\-]{36}")) {
            sendEvent(emitter, "error", "Error: Invalid conversationId format.");
            emitter.complete();
            return emitter;
        }

        log.info("Chat request - model: {}, conversationId: {}, rootDirectory: {}",
                model, conversationId.isEmpty() ? "(none)" : conversationId, rootDirectory);

        boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
        Set<String> authorized = new LinkedHashSet<>(authorizationService.grantedToolGroups(isAdmin));
        if (!rootDirectory.isEmpty()) {
            // unix is context-dependent (requires rootDirectory), never in grantedToolGroups
            authorized.add("unix");
        } else {
            // md-writer also requires a project root — strip it when none is selected
            authorized.remove("md-writer");
        }
        if (!tools.isBlank()) {
            Set<String> requested = Arrays.stream(tools.split(","))
                    .map(String::trim)
                    .filter(g -> !g.isEmpty())
                    .collect(Collectors.toSet());
            authorized.retainAll(requested);
        }
        Object[] toolArray = buildToolInstances(String.join(",", authorized), rootDirectory, braveApiKey, mcpToolBridge);

        String effectivePrompt = rootDirectory.isEmpty() ? prompt
                : (prompt.isEmpty() ? "" : prompt + "\n") + "Working directory: " + rootDirectory;

        CompletableFuture.runAsync(() -> {
            try {
                orchestrationService.chatStream(
                        conversationId.isEmpty() ? null : conversationId,
                        userId,
                        model, effectivePrompt, message, rootDirectory,
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
            } catch (Exception e) {
                sendEvent(emitter, "error", e.getMessage());
                emitter.complete();
            }
        }, executor);

        return emitter;
    }

    private long currentUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(UserResolverFilter.ATTR_USER_ID);
        return attr instanceof Long id ? id : 1L;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

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
                    // MarkDownWriter requires a rootDirectory anchor; entitlement is still granted, tool just can't be instantiated without it
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

    private List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens;
    }
}
