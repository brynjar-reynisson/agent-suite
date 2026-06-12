# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build JAR, restart dev servers (kills dev ports only, leaves prod untouched)
./build.sh        # or build.cmd on Windows

# Promote to prod (freeze JAR to release/, build frontend dist/, restart prod servers)
./promote.sh      # or promote.cmd on Windows

# Test
./mvnw test

# Single test class
./mvnw test -Dtest=AgentSuiteApplicationTests
```

**Dev:** frontend `http://localhost:5177`, backend `http://localhost:8090` (local Supabase, no external OAuth).  
**Prod:** frontend `http://localhost:5176` → `https://agent.breynisson.org`, backend `http://localhost:8091` (supabase.co, OAuth enabled).

**Prod artifacts:** JAR is frozen in `release/` (copied from `target/` by `promote.*`); frontend is a static build in `frontend/dist/` served by `vite preview`. Neither updates unless you explicitly promote.

**Required env vars (dev):** `DEEPSEEK_API_KEY`, `SUPABASE_JWT_SECRET` (no baked-in fallback — startup fails fast if unset; set it to the local Supabase JWT secret, which the dev startup script provides).  
**Required env vars (prod):** `DEEPSEEK_API_KEY`, `SUPABASE_PROD_DB_HOST`, `SUPABASE_PROD_DB_PASSWORD`, `SUPABASE_PROD_JWT_SECRET`, `SUPABASE_PROD_URL`, `SPRING_PROFILES_ACTIVE=prod`.  
**Optional:** `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `MISTRAL_AI_API_KEY`, `BRAVE_SEARCH_API_KEY`.

Auto-starts on login via `C:\Users\Lenovo\start-agent-suite-dev.ps1` and `start-agent-suite-prod.ps1` (Windows Startup folder shortcuts).

## Database Migrations

Migrations live in `supabase/migrations/`. Local dev migrations are applied automatically by the local Supabase instance.

```bash
# Apply all pending migrations to prod (supabase.co)
npx supabase db push

# First-time setup (one-off, persists credentials):
npx supabase login                                          # opens browser
npx supabase link --project-ref grgspbzqzjblsoxmmojy       # prompts for DB password
```

## Prod Database Backup & Restore

```bash
# Dump prod app data (public schema only) to backups/prod-data-<timestamp>.sql
./backup-prod-db.sh      # or backup-prod-db.cmd on Windows

# Truncate app tables and restore from newest backup (or pass a file; -y skips confirmation)
./restore-prod-db.sh [-y] [backups/prod-data-<timestamp>.sql]   # or restore-prod-db.cmd
```

Both scripts load `SUPABASE_PROD_DB_HOST/USERNAME/PASSWORD` from the gitignored `.env.production` via the global `dotenv` CLI (dotenv-cli). Backups are data-only (schema comes from migrations) and scoped to the `public` schema via `--schema public` — Supabase-managed `auth` tables are excluded. Restore runs in a single transaction via Dockerized `psql` (`postgres:17`) — a failure rolls back, leaving prod data unchanged. `backups/` is gitignored (contains user conversation data).

## Architecture

Spring Boot 3.5 + LangChain4j 1.16.2 agent application. Java 21.

**Request flow:** `AiController` → `ChatOrchestrationService` → provider `ChatService` → LLM API → `UnixTools` (tool calls) → repeat until no tool calls remain → stream SSE events to client. If `conversationId` is provided, `ChatOrchestrationService` persists all messages to the database and replays conversation history to the LLM on subsequent turns.

**Key layers:**
- `AiController` — `/ai/chat` (GET/POST, streaming SSE) and `/ai/tools` (GET, synchronous tool exec). Validates `rootDirectory` against a hardcoded allowlist. Builds an authoritative tool set from `AuthorizationService.grantedToolGroups()` plus context (`unix`/`md-writer` require a non-empty `rootDirectory`), then intersects with the frontend's `tools` param as an opt-out hint. Accepts optional `conversationId` param (UUID); blank = stateless mode. On `/ai/tools`, `ls`/`cat`/`grep` are available to any user (mirrors the broadly-granted `unix` group), but all `git` subcommands (read and write) require the admin role — guests/non-admins get `"Admin role required for git commands."` The frontend `execTool` forwards the Supabase bearer token so admins can run git from the `!`-command UI.
- `ChatOrchestrationService` — sits between `AiController` and `ChatService`. Owns conversation lifecycle: creates conversation on first turn, loads history from DB, persists messages (system_prompt, user, assistant, tool_call, tool_result, model_change).
- `ChatService` — interface defining `chat()`, `chatStream()`, and `chatStreamWithHistory()` for all providers.
- `HistoryMessage` — sealed DTO interface bridging DB message rows to LLM message types (SystemPrompt, User, Assistant, ToolCall, ToolResult).
- `ChatEvent` — sealed interface (Java records) for SSE event types: `ToolBatch` (per-iteration tool calls+results), `Content`, `Error`, `Done`.
- `ChatResponse` — data class holding tool call list and response text.
- `ModelRegistry` — maps model aliases to `ChatService` instances; lazily creates Anthropic/Google services only if API keys are present.
- `DeepSeekService` — hand-rolled OpenAI-compatible REST client for DeepSeek. Drives the agentic loop manually; caches reasoning content between turns via fingerprinting.
- `DynamicToolProvider` — interface for objects that supply `Map<ToolSpecification, ToolExecutor>` pairs at runtime, bypassing `@Tool` annotation reflection. Implemented by `McpToolBridge`.
- `AbstractLangChain4jChatService` — base class for LangChain4j-backed providers. Builds tool specs from `@Tool` annotations (and from `DynamicToolProvider` instances) and runs the agentic loop (max 20 iterations).
- `AnthropicChatService` — extends `AbstractLangChain4jChatService` for Claude models.
- `GoogleChatService` — extends `AbstractLangChain4jChatService` for Gemini models.
- `UserResolverFilter` — extracts Supabase JWT, resolves to `user_id`, loads admin flag via `AuthorizationService` (skipped for guest), sets both as request attributes (`ATTR_USER_ID`, `ATTR_IS_ADMIN`). Falls back to guest `user_id = 1` and `isAdmin = false` on invalid/missing JWT.
- `UserRoleRepository` — checks `user_role` table for admin membership. Manual jOOQ DSL (no codegen). Single method: `isAdmin(userId)`.
- `AuthorizationService` — wraps `UserRoleRepository`. `isAdmin(userId)` delegates to repo. `grantedToolGroups(isAdmin)` returns the role-based tool entitlement set: `["web"]` for all users, plus `["md-writer", "mcp"]` for admins. `unix` and `md-writer` are additionally gated on a non-empty `rootDirectory` in `AiController`.
- `UnixTools` — exposes `ls`, `cat`, and `grep` as AI-callable tools. Blocks `..` path traversal; gitignore-aware (filters git-ignored paths).
- `MarkDownWriter` — exposes `newMarkDownFile` as an AI-callable tool; writes spec/plan markdown files under `docs/specs/` or `docs/plans/`. Registered as the `"md-writer"` tool group.
- `WebTools` — exposes `webSearch` and `webFetch` as AI-callable tools. Registered as the `"web"` tool group (both tools are granted together). `webSearch` requires `BRAVE_SEARCH_API_KEY`; `webFetch` works without a key but validates URLs against SSRF (rejects private/loopback addresses and non-http(s) schemes).
- `McpToolBridge` — Spring singleton; parses `.mcp.json` at startup, connects MCP servers (stdio + Streamable HTTP via MCP SDK 2.0.0), discovers tools via `tools/list`, builds namespaced `ToolSpecification+ToolExecutor` pairs (`mcp__<serverName>__<toolName>`). `@PreDestroy` closes all connections. Implements `DynamicToolProvider`. Registered as the `"mcp"` tool group (admin-only). Gracefully no-ops when `.mcp.json` is absent.
- `McpJsonSchemaConverter` — converts MCP tool input schemas (`Map<String,Object>` from SDK 2.0.0 `tool.inputSchema()`) to LangChain4j `JsonObjectSchema`. Used by `McpToolBridge` when building `ToolSpecification` per tool.
- `WebConfig` — CORS config allowing `localhost:5176/5177`, `127.0.0.1:5176/5177`, `https://agent.breynisson.org`, and `https://dev.agent.breynisson.org`.
- `LangChain4jConfig` — placeholder for advanced LangChain4j wiring (currently empty).
- `JwtSecretValidator` — startup guard (`@PostConstruct`) that fails the application fast if `supabase.jwt-secret` is blank, or if it equals the well-known local Supabase default while the `prod` profile is active. The secret is supplied by the environment (`SUPABASE_JWT_SECRET` / `SUPABASE_PROD_JWT_SECRET`); no usable default is committed to the repo.

**AI model config** (`application.properties`): default model `deepseek-v4-pro`, temperature `0.1`, max tokens `8192`, request/response logging enabled, LangChain4j debug logging on.

**Logging:** each profile writes to its own app-log file so the concurrently-running dev and prod backends don't interleave: dev → `./logs/agent-suite-dev.log`, prod → `./logs/agent-suite-prod.log` (base `application.properties` keeps `./logs/agent-suite.log` as a no-profile fallback). All are under the backend working directory (`logs/` is gitignored).

## API

```
GET/POST /ai/chat
  ?message=<user message>          (default: "Hello, how are you?")
  ?prompt=<system prompt>          (default: empty)
  ?rootDirectory=<path>            (default: empty; must be in allowlist)
  ?model=<model alias>             (default: "deepseek-v4-pro")
  ?tools=<comma-separated groups>  (default: empty; opt-out hint only — backend computes authoritative set from role + context)
  ?conversationId=<UUID>           (default: empty; blank = stateless mode)

GET /ai/config/directories
  Returns JSON array of allowed rootDirectory values

GET /ai/config/user
  Returns { "isAdmin": boolean, "grantedToolGroups": string[] } for the authenticated user (guest → false, ["web"])
```

**Message types** stored in the `message` table:

| Type | Sent to LLM? | Content |
|---|---|---|
| `system_prompt` | Yes | System prompt text |
| `user` | Yes | User message |
| `assistant` | Yes | LLM text response |
| `tool_call` | Yes | JSON `[{"name":"...","arguments":"..."}]` per iteration |
| `tool_result` | Yes | JSON `[{"name":"...","result":"..."}]` per iteration |
| `model_change` | No | Model alias string |

Streaming response: Server-Sent Events with event types `tool_call`, `content`, `error`, `done`.

Supported model aliases:

| Alias | Provider | Requires env var |
|---|---|---|
| `deepseek-v4-pro` | DeepSeek (hand-rolled) | `DEEPSEEK_API_KEY` |
| `deepseek-v4-flash` | DeepSeek (hand-rolled) | `DEEPSEEK_API_KEY` |
| `sonnet-4.6` | Anthropic Claude Sonnet 4.6 | `ANTHROPIC_API_KEY` |
| `opus-4.7` | Anthropic Claude Opus 4.7 | `ANTHROPIC_API_KEY` |
| `opus-4.8` | Anthropic Claude Opus 4.8 | `ANTHROPIC_API_KEY` |
| `haiku-4.5` | Anthropic Claude Haiku 4.5 | `ANTHROPIC_API_KEY` |
| `gemini-2.5-flash` | Google Gemini 2.5 Flash | `GOOGLE_API_KEY` |
| `mistral-large` | Mistral Large (latest) | `MISTRAL_AI_API_KEY` |
| `mistral-small` | Mistral Small (latest) | `MISTRAL_AI_API_KEY` |

## Frontend

React 19 + Vite 8 + Tailwind CSS 4 chat UI located in `frontend/`. Dev server: `npm run dev` runs on port `5177` (dev, proxies to backend 8090); `npm run prod` runs on port `5176` (prod, proxies to backend 8091).

```bash
cd frontend
cp frontend/.env.example frontend/.env  # first-time setup: copy dev defaults
npm install
npm run dev    # http://localhost:5177 (dev environment)
npm run prod   # http://localhost:5176 (prod environment, uses supabase.co)
npm run build  # output to frontend/dist/
```

Key files:
- `App.tsx` — chat UI: model selector, SSE streaming, tool call display, system prompt and root directory inputs. Generates a UUID per session (`crypto.randomUUID()` in a `useRef`) and passes it as `conversationId` on every request. Fetches `UserConfig` (isAdmin + grantedToolGroups) via `/ai/config/user` on load and auth change; derives `availableTools` from `grantedToolGroups` plus `unix`/`md-writer` context gates; filters `PROMPT_BANK` to hide `md-writer` prompts for non-admins; resets all conversation state on sign-out.
- `ToolStrip.tsx` — icon-only strip rendered above the input showing active tool groups driven by `availableTools` from the server. Click-to-toggle disabled state; disabled tools are excluded from the `tools` param sent to the backend (opt-out).
- `api.ts` — `chatStream()`, `getDirectories()`, `getUserConfig()` (returns `UserConfig`), `execTool()` API client. `ChatRequest` includes optional `conversationId` and `tools`.

Production deployment: `https://agent.breynisson.org`

<!-- agent-lsp:rules:start -->
## agent-lsp Skills

agent-lsp provides 66 code intelligence tools and 23 workflow skills.
Prefer these tools over text search for code intelligence tasks.

**Before editing code:** call `blast_radius` for blast-radius analysis.
**Before applying edits:** call `preview_edit` to preview the diagnostic delta.
**After any change:** call `get_diagnostics`, then `run_build` and `run_tests`.

**Task-to-tool mapping (use these instead of Read/Grep for code):**

| Task | Use this | Not this |
|------|----------|----------|
| See file structure | `list_symbols` | `Read` + manual scanning |
| Find a symbol by name | `find_symbol` | `Grep` across files |
| Find all usages | `find_references` | `Grep` for the name |
| Understand a symbol | `inspect_symbol` | `Read` the file |
| What calls this function | `find_callers` | `Grep` for the name |
| Replace a function body | `replace_symbol_body` | `Edit` with text matching |
| Delete unused symbol | `safe_delete_symbol` | `Edit` to remove lines |

| Skill | Description |
|-------|-------------|
| `/lsp-architecture` | Generate a structural architecture overview of a codebase: languages, package map, entry points, dependency graph, an... |
| `/lsp-concurrency-audit` | Concurrency safety audit for a type or file. Maps all fields, traces which are accessed from concurrent contexts (gor... |
| `/lsp-cross-repo` | Cross-repository analysis — find all callers of a library symbol in one or more consumer repos. Use when refactorin... |
| `/lsp-dead-code` | Enumerate exported symbols in a file and surface those with zero references across the workspace. Use when auditing f... |
| `/lsp-docs` | Three-tier documentation lookup for any symbol — hover → offline toolchain doc → source definition. Use when ho... |
| `/lsp-edit-export` | Safe workflow for editing exported symbols or public APIs. Use when changing a function signature, modifying a public... |
| `/lsp-edit-symbol` | Edit a named symbol without knowing its file or position. Use when you want to change a function, type, or variable b... |
| `/lsp-explore` | Tell me about this symbol": hover + implementations + call hierarchy + references in one pass — for navigating unfa... |
| `/lsp-extract-function` | Extract a selected code block into a named function. Primary path uses the language server's extract-function code ac... |
| `/lsp-fix-all` | Apply available quick-fix code actions for all current diagnostics in a file, one at a time with re-collection betwee... |
| `/lsp-format-code` | Format a file or selection using the language server's formatter. Use before committing to apply consistent style, or... |
| `/lsp-generate` | Trigger language server code generation — implement interface stubs, generate test skeletons, add missing methods, ... |
| `/lsp-impact` | Blast-radius analysis for a symbol or file — shows all callers, type supertypes/subtypes, and reference count befor... |
| `/lsp-implement` | Find all concrete implementations of an interface or abstract type. Use when you need to know what types satisfy an i... |
| `/lsp-inspect` | Full code quality audit for a file, package, or directory. Supports batch mode (directory walk with --top ranking), c... |
| `/lsp-local-symbols` | Fast file-scoped symbol analysis — find all usages of a symbol within the current file, list all symbols defined in... |
| `/lsp-onboard` | First-session project onboarding. Explores the project structure, detects build system, test runner, entry points, an... |
| `/lsp-refactor` | End-to-end safe refactor workflow — blast-radius analysis, speculative preview, apply to disk, verify build, run af... |
| `/lsp-rename` | Two-phase safe rename across the entire workspace. Use when renaming any symbol, function, method, variable, type, or... |
| `/lsp-safe-edit` | Wrap any code edit with before/after diagnostic comparison. Speculatively previews the change first (preview_edit), t... |
| `/lsp-simulate` | Speculative code editing session — simulate changes in memory before touching disk. Use when planning edits that mi... |
| `/lsp-test-correlation` | Find and run the tests that cover a source file. Use after editing a file to discover exactly which test files and te... |
| `/lsp-understand` | Deep-dive exploration of unfamiliar code — given a symbol or file, builds a complete Code Map showing type info, im... |
| `/lsp-verify` | Full three-layer verification after any change — LSP diagnostics + compiler build + test suite, ranked by severity.... |

Call `prompts/get` with any skill name for full workflow instructions.
<!-- agent-lsp:rules:end -->
