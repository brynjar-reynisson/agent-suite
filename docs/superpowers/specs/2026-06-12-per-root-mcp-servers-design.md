# Per-Root-Directory MCP Servers (Obsidian First) — Design

**Date:** 2026-06-12
**Status:** Approved design, pending implementation plan

## Problem

agent-suite needs MCP servers that make sense for the chat app but not for Claude Code — starting with an Obsidian server for the vault. Today `McpToolBridge` reads the same `.mcp.json` that Claude Code uses, so agent-suite and Claude Code share one server list. Additionally, some MCP servers are intrinsically tied to a single root directory (the Obsidian server only makes sense for the vault), so the tool set should vary with the request's `rootDirectory`.

## Decisions (from brainstorming)

- **Layered config:** keep loading the global `.mcp.json` (agent-lsp, supabase, supabase-prod stay available to admins) and add per-root-directory config files on top.
- **Per-root configs live inside the root directories** as `<root>/.agent-suite-mcp.json`. The distinct filename prevents the agent-suite repo root (itself an allowed root directory) from double-loading its Claude Code `.mcp.json`.
- **Obsidian server is filesystem-based** (reads/writes the vault's markdown directly; no Obsidian app or REST plugin required). Candidate package: `obsidian-mcp` via npx, vault path as argument — exact package name/args to be verified during planning.
- **Eager connection at startup:** all per-root servers are connected at boot, like the global ones. Requests only filter.
- **Admin-only:** per-root MCP tools ride inside the existing admin-only `mcp` tool group. Guests never see them, even with the vault root selected.

## Config Model

Two layers, both using the existing `{"mcpServers": {...}}` schema (`McpServerConfig`: `command`, `args`, `env`, `url`, `transport`):

1. **Global:** `.mcp.json` via the `mcp.config.path` property — unchanged.
2. **Per-root:** `<root>/.agent-suite-mcp.json` for each non-empty entry in the root-directory allowlist.

**`${root}` placeholder:** in per-root configs, the literal `${root}` is expanded in `command`, each `args` element, `env` values, and `url`. It resolves to the directory containing the config file (forward-slash form). This keeps configs portable and avoids hardcoding the directory path twice.

First concrete file, in the vault:

```json
// C:/Users/Lenovo/Documents/obsidian/brynjar-obsidian/.agent-suite-mcp.json
{
  "mcpServers": {
    "obsidian": {
      "command": "npx",
      "args": ["-y", "obsidian-mcp", "${root}"]
    }
  }
}
```

## Components

### `RootDirectories` (new, `config` package)

Owns the root-directory allowlist currently hardcoded as `ALLOWED_ROOT_DIRECTORIES` in `AiController`: an `ALLOWED` set plus a `nonEmpty()` helper (the allowlist contains `""` for "no root selected", which is meaningless for filesystem scanning). `AiController` and `McpToolBridge` both reference it. Pure relocation — no behavior change.

### `McpToolBridge` (extended)

- **Startup:** load the global config as today, then scan each non-empty allowed root for `.agent-suite-mcp.json`. Parse with the same `McpConfig` record, expand `${root}`, connect via the existing client factory (reusing the Windows npx resolution, timeouts, and error handling verbatim), and store entries in a `Map<String, Map<ToolSpecification, ToolExecutor>>` keyed by root directory. All clients join the existing `clients` list, so the existing `@PreDestroy` closes everything.
- **`toolEntries()`** (the `DynamicToolProvider` contract) keeps returning **global entries only** — existing behavior preserved for any caller that doesn't pass a root.
- **New `scopedProvider(String rootDirectory)`** returns a lightweight `DynamicToolProvider` whose `toolEntries()` is global ∪ that root's entries. The merge dedupes by tool name; **per-root wins on collision**, with a warning logged. Tool namespacing stays `mcp__<serverName>__<toolName>`.
- **`toolNames(String rootDirectory)`** overload for the config endpoint.

### `AiController`

- References `RootDirectories.ALLOWED` instead of its own constant.
- `buildToolInstances` case `"mcp"` adds `mcpToolBridge.scopedProvider(rootDirectory)` instead of the bridge itself.
- `/ai/config/mcp-tools` gains an optional `rootDirectory` param (validated against the allowlist; invalid → error) and returns the merged tool names for that root.

### Untouched

`DynamicToolProvider` interface, `ChatService` implementations (including `DeepSeekService`), `ChatOrchestrationService`, and the frontend. The scoped provider implements the same interface the chat services already consume. The `mcp` ToolStrip icon already appears for admins; the tool inventory inside the group varies server-side.

## Authorization

Unchanged: `mcp` is admin-only via `AuthorizationService.grantedToolGroups`. Root-directory validation already happens before tool building in both `/ai/chat` and `/ai/tools`, so a scoped provider is only ever created for an allowlisted root.

## Error Handling

All failures are non-fatal, matching the existing graceful pattern:

| Failure | Behavior |
|---|---|
| Per-root config file absent | Skip silently (debug/info log) |
| Malformed JSON in per-root file | Warn, skip that root |
| Server connect or `tools/list` failure | Error log, skip that server (existing pattern) |
| Tool name collision (global vs per-root) | Warn at startup; per-root wins in scoped merge |

A broken vault config can never block backend startup.

## Testing

- **`McpToolBridge` unit tests** (via the existing package-private constructor with injectable client factory and config path; per-root scan path made injectable the same way): per-root file discovery, `${root}` expansion in command/args/env/url, scoped-provider merge and collision precedence, missing/malformed file tolerance, `toolNames(root)`.
- **`buildToolInstances` test:** the `mcp` case yields a root-scoped provider, not the raw bridge.
- **Manual test before merge** (standing workflow): as admin with the vault root selected, Obsidian tools appear in `/ai/config/mcp-tools` and are callable in chat; as guest with the vault root selected, they are absent; with no root selected, only global MCP tools appear.

## Operational Notes

- Dev (8090) and prod (8091) backends each spawn their own per-root server processes at startup (e.g. two `obsidian-mcp` npx processes). They are stateless filesystem servers; this is harmless.
- The vault's `.agent-suite-mcp.json` contains no secrets (the filesystem Obsidian server needs no API key). It lives outside this repo; its content is documented here and in CLAUDE.md.

## Out of Scope

- Lazy / on-demand server connection.
- Per-root authorization finer than the existing admin-only `mcp` group.
- Hot-reload of MCP configs (restart required, as today).
- REST-API-based Obsidian integration.
