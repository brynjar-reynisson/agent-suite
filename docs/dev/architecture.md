# Architecture

Spring Boot 3.5 + LangChain4j 1.16.2 agent application. Java 21.

## Request Flow

`AiController` → `ChatOrchestrationService` → provider `ChatService` → LLM API → `UnixTools` (tool calls) → repeat until no tool calls remain → stream SSE events to client.

If `conversationId` is provided, `ChatOrchestrationService` persists all messages to the database and replays conversation history to the LLM on subsequent turns.

## Key Components

**API & Orchestration**
- `AiController` — `/ai/chat` (GET/POST, streaming SSE) and `/ai/tools` (GET, synchronous tool exec). Validates `rootDirectory` against a hardcoded allowlist. Builds an authoritative tool set from `AuthorizationService.grantedToolGroups()` plus context (`unix`/`md-writer` require a non-empty `rootDirectory`), then intersects with the frontend's `tools` param as an opt-out hint. Accepts optional `conversationId` param (UUID); blank = stateless mode. On `/ai/tools`, `ls`/`cat`/`grep` are available to any user (mirrors the broadly-granted `unix` group), but all `git` subcommands require the admin role.
- `ChatOrchestrationService` — sits between `AiController` and `ChatService`. Owns conversation lifecycle: creates conversation on first turn, loads history from DB, persists messages. `compact(externalId, userId)` summarises a conversation via the LLM and stores the result as a `compact` message. `loadHistory` uses the most recent `compact` record as a truncation point — messages before it are dropped, the compact summary is injected as a `HistoryMessage.User`, and subsequent messages accumulate normally.
- `RootDirectories` — shared allowlist of root directories. `ALLOWED` includes `""` (no root selected); `nonEmpty()` is used by `McpToolBridge` for per-root config scanning.

**Chat Services**
- `ChatService` — interface defining `chat()`, `chatStream()`, and `chatStreamWithHistory()` for all providers.
- `AbstractLangChain4jChatService` — base class for LangChain4j-backed providers. Builds tool specs from `@Tool` annotations (and from `DynamicToolProvider` instances) and runs the agentic loop (max 20 iterations).
- `AnthropicChatService` — extends `AbstractLangChain4jChatService` for Claude models.
- `GoogleChatService` — extends `AbstractLangChain4jChatService` for Gemini models.
- `DeepSeekService` — hand-rolled OpenAI-compatible REST client for DeepSeek. Drives the agentic loop manually; caches reasoning content between turns via fingerprinting.
- `ModelRegistry` — maps model aliases to `ChatService` instances; lazily creates Anthropic/Google services only if API keys are present.

**DTOs & Events**
- `HistoryMessage` — sealed DTO interface bridging DB message rows to LLM message types (SystemPrompt, User, Assistant, ToolCall, ToolResult).
- `ChatEvent` — sealed interface (Java records) for SSE event types: `ToolBatch` (per-iteration tool calls+results), `Content`, `Error`, `Done` (optionally carries a `TurnUsage` with input/output/cache token counts).
- `ChatResponse` — data class holding tool call list and response text.

**Auth & Authorization**
- `UserResolverFilter` — extracts Supabase JWT, resolves to `user_id`, loads admin flag via `AuthorizationService` (skipped for guest), sets both as request attributes. Falls back to guest `user_id = 1` and `isAdmin = false` on invalid/missing JWT. Verification pins the algorithm to **HS256** (shared project secret) or **ES256** (asymmetric key from JWKS) — HS384/512 and `none` are rejected — and requires `aud = "authenticated"` plus `iss = <supabase.url>/auth/v1`.
- `UserRoleRepository` — checks `user_role` table for admin membership. Manual jOOQ DSL (no codegen).
- `AuthorizationService` — wraps `UserRoleRepository`. `grantedToolGroups(isAdmin)` returns `["web"]` for all users, plus `["md-writer", "mcp"]` for admins. `unix` and `md-writer` are additionally gated on a non-empty `rootDirectory` in `AiController`.
- `JwtSecretValidator` — startup guard (`@PostConstruct`) that fails fast if `supabase.jwt-secret` is blank, or equals the well-known local Supabase default while the `prod` profile is active.

**Tools**
- `UnixTools` — exposes `ls`, `cat`, and `grep` as AI-callable tools. `ls`/`cat` confine access to the root directory via path canonicalization (`escapesRoot`) — blocks `..` traversal and absolute paths. Gitignore-aware when a `.git` is present.
- `MarkDownWriter` — exposes `newMarkDownFile` as an AI-callable tool; writes spec/plan markdown files under `docs/specs/` or `docs/plans/`. Registered as the `"md-writer"` tool group.
- `WebTools` — exposes `webSearch` and `webFetch` as AI-callable tools. Registered as the `"web"` tool group. `webSearch` requires `BRAVE_SEARCH_API_KEY`. `webFetch` validates URLs against SSRF: non-http(s) schemes rejected, redirects disabled, all resolved addresses checked against an internal-range denylist (loopback, link-local, cloud metadata, private, multicast, CGNAT `100.64.0.0/10`, IPv6 ULA `fc00::/7`).
- `AudioTools` — exposes `serveAudioFile` as an AI-callable tool. Validates `.wav`/`.mp3` extension and path confinement, returns the fully-qualified public URL. Registered as the `"audio"` tool group (admin-only).
- `DynamicToolProvider` — interface for objects supplying `Map<ToolSpecification, ToolExecutor>` pairs at runtime, bypassing `@Tool` annotation reflection. Implemented by `McpToolBridge`.

**MCP**
- `McpToolBridge` — Spring singleton; parses `.mcp.json` at startup, connects MCP servers (stdio + Streamable HTTP via MCP SDK 2.0.0), discovers tools via `tools/list`, builds namespaced `ToolSpecification+ToolExecutor` pairs (`mcp__<serverName>__<toolName>`). Also scans each allowed root for `<root>/.agent-suite-mcp.json` (same schema; `${root}` expands to that directory). `scopedProvider(rootDirectory)` merges global + per-root tools. Per-root scanning disabled in tests via `mcp.root-config.enabled=false`. `@PreDestroy` closes all connections. Registered as the `"mcp"` tool group (admin-only). `callMcpTool()` delegates `McpSchema.ImageContent` results to `ImageContentHandler`.
- `McpJsonSchemaConverter` — converts MCP tool input schemas (`Map<String,Object>`) to LangChain4j `JsonObjectSchema`.

> **Per-root MCP server notes (`C:\REAPER\Projects\.agent-suite-mcp.json`):**
> `computer-control` — provides `take_screenshot_with_ocr` and `list_windows`. Requires `"env": {"PYTHONUTF8": "1"}` in its config entry; without it, the PyInstaller-bundled server crashes with `UnicodeEncodeError` when OCR detects non-ASCII characters (e.g. CJK) because Windows defaults the console to cp1252.

**Media**
- `ImageContentHandler` — receives `McpSchema.ImageContent` from `McpToolBridge`, decodes base64, writes `screenshot_<UUID>.<ext>` to `tmp_screenshot_files/`, returns a markdown image link. Supports `image/png`, `image/jpeg`, `image/webp`. Appends an italicised note to the tool result telling non-vision models (e.g. DeepSeek) to call `mcp__computer-control__take_screenshot_with_ocr` to read the image rather than guessing its contents.
- `ImageController` — `GET /images/{filename}` endpoint serving PNG/JPG/JPEG/WEBP from `tmp_screenshot_files/`. Path-confined, extension-locked, unauthenticated.
- `AudioController` — `GET /audio/{filename}` endpoint serving WAV/MP3 from `tmp_audio_files/`. Path-confined, extension-locked, unauthenticated. Supports HTTP Range requests (returns 206 Partial Content + `Content-Range` for Range requests, 200 for full-file requests) so embedded `<audio>` players can stream and seek without buffering the entire file first.

**Config**
- `WebConfig` — CORS config allowing `localhost:5176/5177`, `127.0.0.1:5176/5177`, `https://agent.breynisson.org`, `https://dev.agent.breynisson.org`.
- `LangChain4jConfig` — placeholder for advanced LangChain4j wiring (currently empty).

## AI Model Config

`application.properties`: default model `deepseek-v4-pro`, temperature `0.1`, max tokens `8192`. LangChain4j request/response logging is **off** (`dev.langchain4j` log level `INFO`) — request logging would expose `Authorization: Bearer <key>` headers.

## Logging

Each profile writes to its own log file so dev and prod don't interleave:
- Dev → `./logs/agent-suite-dev.log`
- Prod → `./logs/agent-suite-prod.log`
- No-profile fallback → `./logs/agent-suite.log`

`logs/` is gitignored.
