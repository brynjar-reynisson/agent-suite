---
title: MCP as Common Tool-Set
date: 2026-06-11
status: draft
---

# MCP as Common Tool-Set

## Summary

Add Model Context Protocol (MCP) as a new `mcp` tool group in the AgentSuite, alongside the existing `unix`, `web`, and `md-writer` groups. MCP servers configured via `.mcp.json` are connected at startup, their tools discovered, and those tools are exposed to the AI model as individually-typed callable tools — making any MCP-compatible server instantly available to the agent's tool-set.

## Business Requirement

An admin user of the AgentSuite chat application must be able to configure external tool providers via MCP servers (both local stdio-based and remote HTTP-based) and have those tools automatically discovered and available to the AI model during chat sessions, without any code changes.

## User Problem

Today the AgentSuite has a fixed, hand-curated set of tools: file system operations (`unix`), web search/fetch (`web`), and markdown file writing (`md-writer`). Adding a new tool capability requires writing Java code (`@Tool`-annotated methods in a new class), wiring it into `AiController.buildToolInstances()`, updating the tool group authorization logic, adding frontend `TOOL_META` entries, and redeploying.

This means:
- Users cannot bring their own tools (e.g., a database query MCP server, a Jira MCP server, a custom API integration).
- Every new tool requires a development cycle.
- The ecosystem of hundreds of existing MCP servers is inaccessible.

MCP is the industry-standard protocol for connecting AI models to external tools. By supporting it as a first-class tool group, the AgentSuite gains access to the entire MCP ecosystem with zero code per integration.

## Success Criteria

1. A `.mcp.json` file in the project root (or a configurable path) defines MCP server connections in the standard MCP configuration format.
2. On application startup, the backend parses `.mcp.json`, establishes connections to each configured MCP server, and discovers all tools they expose.
3. Discovered MCP tools appear as individually-typed callable tools alongside built-in tools when the `mcp` tool group is active — the AI model sees each MCP tool's name, description, and parameter schema directly, without any system prompt augmentation.
4. When the AI model invokes an MCP tool, the request is routed to the correct MCP server, executed, and the result returned to the model.
5. Both stdio transport (local subprocess) and Streamable HTTP transport (remote server) are supported for MCP server connections.
6. The `mcp` tool group is restricted to admin users only, and is visible in the frontend tool strip with an appropriate icon when the user is an admin.
7. MCP tool names are namespaced with the server name to prevent collisions between tools from different servers and with built-in tools.
8. If a configured MCP server fails to connect at startup, the application logs the error and continues without that server's tools (graceful degradation).
9. Tool execution errors from MCP servers are propagated to the AI model as error results (with `isError: true` semantics), enabling the model to self-correct.

## Flaws Corrected from Source Spec

The original spec (`docs/specs/2026-06-11-mcp-common-tool-set.md`) had these issues, fixed here:

1. **Frontmatter date wrong**: `2025-07-16` corrected to `2026-06-11`.
2. **Meta-tool design unnecessary**: The original proposed a single `callMcpTool(serverName, toolName, arguments)` meta-tool with system prompt augmentation to compensate for its weak schema. `AbstractLangChain4jChatService.buildToolProvider()` already pairs `ToolSpecification` + `ToolExecutor` without `@Tool` annotations — per-tool specs from MCP discovery work directly without any meta-tool or prompt augmentation.
3. **System Prompt Augmentation section removed**: It was a workaround for the meta-tool flaw. With proper `ToolSpecification` per MCP tool, the model gets full schemas natively.
4. **`buildToolInstances` is static**: Cannot access a Spring bean. Fixed by injecting `McpToolBridge` into `AiController` and converting `buildToolInstances` to an instance method.
5. **`ChatOrchestrationService` listed as affected file**: Needs no changes. The original listed it for system prompt augmentation pass-through; that concern is gone with the proper tool spec approach.
6. **Success criterion #8 retry claim removed**: "Server can be retried at runtime" contradicted the Out of Scope exclusion of dynamic server registration at runtime.
7. **"SEP-986" citation incorrect**: MCP tool name constraints come from the MCP specification itself, not SEP-986.
8. **Redundant timeout properties**: `mcp.tool.timeout-seconds` and `mcp.tool.call-timeout-seconds` had an unclear distinction. Simplified to a single `mcp.call-timeout-seconds`.
9. **MCP BOM artifact unspecified**: The dependency section said "with BOM" but never gave the BOM artifact ID. Added `io.modelcontextprotocol.sdk:mcp-bom`.
10. **Affected files incomplete**: `DynamicToolProvider.java` (new interface) and `AbstractLangChain4jChatService.java` (modified) were not listed.

## Scope

### In Scope

- Parsing `.mcp.json` (standard MCP client configuration format) from the application working directory.
- Supporting the `stdio` transport: launching a local process specified by `command` and `args`, communicating via JSON-RPC over stdin/stdout.
- Supporting the `streamableHttp` transport: connecting to a remote MCP server via HTTP POST/GET with optional SSE streaming.
- Protocol version negotiation with the MCP server (supporting 2024-11-05, 2025-03-26, and 2025-06-18 protocol versions via the Java SDK's built-in negotiation).
- Tool discovery via `tools/list` and exposing discovered tools as LangChain4j `ToolSpecification` + `ToolExecutor` pairs — one per MCP tool, not a single meta-tool.
- Namespacing tool names as `mcp__<serverName>__<toolName>` to avoid collisions.
- Admin-only authorization for the `mcp` tool group (via `AuthorizationService`).
- Frontend tool strip entry for `mcp` (icon: `🔌`, tooltip showing connected server names).
- Graceful degradation when a server is unreachable: log warning, skip that server's tools.
- Timeout handling for MCP tool calls (configurable, default 30 seconds).
- Integration with the existing `AiController` and `AbstractLangChain4jChatService` tool provider pipeline via a new `DynamicToolProvider` interface.

### Out of Scope

- MCP server implementation (the AgentSuite is an MCP *client*, not a server).
- MCP resource or prompt exposure to the AI model — tools only for the initial implementation.
- OAuth 2.0 authorization for remote MCP servers (servers requiring auth are not supported in v1).
- Dynamic server registration or retry at runtime (servers are configured statically in `.mcp.json` and loaded at startup).
- User-level MCP server configuration (only the global `.mcp.json` is supported).
- The `SSE` (legacy 2024-11-05 HTTP+SSE) transport — only stdio and Streamable HTTP are supported.
- MCP `elicitation` (server-initiated user prompts) — if a server requests elicitation, the call fails with an error.
- MCP `roots` — the client does not advertise roots capability.
- MCP `sampling` — the client does not advertise sampling capability.
- Tool output schema validation — results are passed through as-is.
- `listChanged` notifications for dynamic tool updates after initial discovery.

## Design Notes

### Configuration Format (`.mcp.json`)

The `.mcp.json` file follows the standard MCP client configuration format, compatible with Claude Desktop and other MCP hosts:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allowed/dir"]
    },
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "remote-api": {
      "url": "https://mcp.example.com/mcp",
      "transport": "streamableHttp"
    }
  }
}
```

- Each key under `mcpServers` is the server name (used for namespacing tools).
- `command` + `args` defines a stdio transport.
- `url` + `transport: "streamableHttp"` defines a Streamable HTTP transport.
- Environment variables for a server can be specified via an `env` map.

### Tool Namespacing

To prevent collisions between tools from different MCP servers and with built-in tools, MCP tool names are prefixed:

**Pattern:** `mcp__<serverName>__<toolName>`

Example: A `read_file` tool from a server named `filesystem` becomes `mcp__filesystem__read_file`.

The double-underscore separator is chosen because:
- It is unlikely to appear in MCP tool names (which follow `[A-Za-z0-9_.-]` per the MCP specification).
- It is visually distinct and easy to parse.
- Single underscore could collide with tool names that already use underscores.

### DynamicToolProvider Interface

The existing `AbstractLangChain4jChatService.buildToolProvider()` discovers tools by reflecting on `@Tool`-annotated methods. MCP tools are discovered at runtime and cannot be represented as `@Tool` annotations at compile time.

A new `DynamicToolProvider` interface provides the bridge:

```java
public interface DynamicToolProvider {
    Map<ToolSpecification, ToolExecutor> toolEntries();
}
```

`buildToolProvider()` is extended to detect objects implementing `DynamicToolProvider` and add their entries directly to the `ToolProviderResult`, alongside the normal annotation-based discovery path. This gives the AI model the full per-tool name, description, and parameter schema for each MCP tool — no system prompt augmentation is needed.

`McpToolBridge` implements `DynamicToolProvider`. At construction it builds one `ToolSpecification` per discovered MCP tool (name = namespaced key, description and input schema from `tools/list` response), paired with a `ToolExecutor` lambda that calls the appropriate MCP server's `tools/call` endpoint.

### Architecture Integration

```
┌─────────────────────────────────────────────────────┐
│                    AiController                      │
│  buildToolInstances() → adds McpToolBridge instance  │
│  when "mcp" is in the authorized tool groups         │
│  (instance method; McpToolBridge injected via ctor)  │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│               McpToolBridge                          │
│               implements DynamicToolProvider         │
│                                                      │
│  - Parses .mcp.json at construction                  │
│  - Creates McpSyncClient per server                  │
│  - Discovers tools via tools/list on each server     │
│  - Returns ToolSpecification+ToolExecutor per tool   │
│  - Routes calls: namespace → server → tools/call     │
│                                                      │
│  toolEntries() → Map<ToolSpecification, ToolExecutor>│
│    keyed by mcp__<server>__<tool> names              │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│       AbstractLangChain4jChatService                 │
│       buildToolProvider()                            │
│                                                      │
│  for each tool object:                               │
│    if DynamicToolProvider → add toolEntries()        │
│    else → @Tool annotation reflection (existing)     │
└────────────────────────┬────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
    ┌──────────┐  ┌──────────┐  ┌──────────┐
    │  Server  │  │  Server  │  │  Server  │
    │  "fs"    │  │ "fetch"  │  │ "remote" │
    │ (stdio)  │  │ (stdio)  │  │ (HTTP)   │
    └──────────┘  └──────────┘  └──────────┘
```

### Injecting McpToolBridge into AiController

The current `AiController.buildToolInstances()` is a `static` method that instantiates tool objects with `new`. Since `McpToolBridge` is a Spring singleton, it must be injected:

- `McpToolBridge` is added as a constructor parameter of `AiController`.
- `buildToolInstances()` becomes an instance method so it can reference `this.mcpToolBridge`.
- The `"mcp"` case adds `mcpToolBridge` to the instances list — the singleton is safe to share across requests since `McpSyncClient` connections are thread-safe for concurrent calls.

### Dependency

The official MCP Java SDK is used:

```xml
<!-- In <properties>: -->
<mcp-sdk.version><!-- use latest stable --></mcp-sdk.version>

<!-- In <dependencyManagement>/<dependencies>: -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-bom</artifactId>
    <version>${mcp-sdk.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>

<!-- In <dependencies>: -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
</dependency>
```

Pin `mcp-sdk.version` to the current latest stable release of `io.modelcontextprotocol.sdk` at implementation time.

### Thread Safety and Lifecycle

- `McpToolBridge` is created as a singleton Spring bean.
- MCP clients are initialized once at construction and held for the lifetime of the application.
- Tool calls are synchronous (`McpSyncClient`) since they run within the existing agentic loop thread.
- On application shutdown, `@PreDestroy` gracefully closes all MCP client connections.

### Error Handling

| Scenario | Behavior |
|----------|----------|
| `.mcp.json` not found or unparseable | Log warning, `mcp` tool group has zero tools (graceful). |
| Server `command` not found (stdio) | Log error for that server, skip its tools, continue with others. |
| Server URL unreachable (HTTP) | Log error for that server, skip its tools, continue with others. |
| `tools/list` fails | Log error, skip that server's tools. |
| `tools/call` timeout | Return error result to the AI model with timeout details. |
| `tools/call` returns `isError: true` | Propagate the error content to the AI model for self-correction. |
| Server disconnects mid-session | Return error result; server is not retried automatically. |

### Security Considerations

1. **Admin-only**: The `mcp` tool group is only granted to admin users, matching the principle that arbitrary tool execution is a privileged operation.
2. **No network bypass**: Remote MCP servers go through the backend, not the browser, so same-network restrictions apply as with `webFetch`.
3. **Tool input logging**: All MCP tool calls are logged (tool name, server, arguments) for audit purposes.
4. **No filesystem escape via MCP**: The stdio transport launches the server process with the same OS permissions as the AgentSuite backend. No additional filesystem access is granted by the AgentSuite itself.

## Affected Files

| File | Change |
|------|--------|
| `pom.xml` | Add `io.modelcontextprotocol.sdk:mcp-bom` to `<dependencyManagement>` and `io.modelcontextprotocol.sdk:mcp` to `<dependencies>` |
| `src/main/java/.../service/DynamicToolProvider.java` | **New**: interface returning `Map<ToolSpecification, ToolExecutor>` for dynamic tool registration |
| `src/main/java/.../tools/McpToolBridge.java` | **New**: MCP client wrapper; implements `DynamicToolProvider`; tool discovery, routing, `@PreDestroy` lifecycle |
| `src/main/java/.../service/AbstractLangChain4jChatService.java` | Extend `buildToolProvider()` to handle `DynamicToolProvider` objects alongside `@Tool`-annotated ones |
| `src/main/java/.../service/AuthorizationService.java` | Add `"mcp"` to admin tool groups |
| `src/main/java/.../controller/AiController.java` | Inject `McpToolBridge`; convert `buildToolInstances` from static to instance method; add `"mcp"` case |
| `frontend/src/ToolStrip.tsx` | Add `mcp` to `TOOL_META` (icon: `🔌`) |
| `src/main/resources/application.properties` | Add `mcp.config.path` and `mcp.call-timeout-seconds` |

## Configuration Reference

### `.mcp.json` Example

```json
{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "git": {
      "command": "uvx",
      "args": ["mcp-server-git", "--repository", "."]
    },
    "sqlite": {
      "command": "uvx",
      "args": ["mcp-server-sqlite", "--db-path", "data.db"]
    },
    "remote-service": {
      "url": "https://mcp.example.com/mcp",
      "transport": "streamableHttp"
    }
  }
}
```

### Application Properties (new)

```properties
# MCP configuration
mcp.config.path=.mcp.json
mcp.call-timeout-seconds=30
```
