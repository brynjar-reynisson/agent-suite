# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build
./mvnw clean package

# Run (requires DEEPSEEK_API_KEY env var)
./mvnw spring-boot:run

# Test
./mvnw test

# Single test class
./mvnw test -Dtest=AgentSuiteApplicationTests
```

Server runs on `http://localhost:8090`. Requires `DEEPSEEK_API_KEY` environment variable. `ANTHROPIC_API_KEY` and `GOOGLE_API_KEY` are optional — those providers are skipped if their keys are absent.

## Architecture

Spring Boot 3.5 + LangChain4j 0.36.2 agent application. Java 21.

**Request flow:** `AiController` → `ModelRegistry` → provider `ChatService` → LLM API → `UnixTools` (tool calls) → repeat until no tool calls remain → stream SSE events to client

**Key layers:**
- `AiController` — `/ai/chat` (GET/POST, streaming SSE) and `/ai/tools` (GET, returns allowed root directories). Validates `rootDirectory` against a hardcoded allowlist.
- `ChatService` — interface defining `chat()` and `chatStream()` for all providers.
- `ChatEvent` — sealed interface (Java records) for SSE event types: `ToolCall`, `Content`, `Error`, `Done`.
- `ChatResponse` — data class holding tool call list and response text.
- `ModelRegistry` — maps model aliases to `ChatService` instances; lazily creates Anthropic/Google services only if API keys are present.
- `DeepSeekService` — hand-rolled OpenAI-compatible REST client for DeepSeek. Drives the agentic loop manually; caches reasoning content between turns via fingerprinting.
- `AbstractLangChain4jChatService` — base class for LangChain4j-backed providers. Builds tool specs from `@Tool` annotations and runs the agentic loop (max 20 iterations).
- `AnthropicChatService` — extends `AbstractLangChain4jChatService` for Claude models.
- `GoogleChatService` — extends `AbstractLangChain4jChatService` for Gemini models.
- `UnixTools` — exposes `ls`, `cat`, and `grep` as AI-callable tools. Blocks `..` path traversal; gitignore-aware (filters git-ignored paths).
- `MarkDownWriter` — exposes `newMarkDownFile` as an AI-callable tool; writes spec/plan markdown files under `docs/specs/` or `docs/plans/`. Registered as the `"md-writer"` tool group.
- `WebConfig` — CORS config allowing `localhost:5176`, `127.0.0.1:5176`, and `https://agent.breynisson.org`.
- `LangChain4jConfig` — placeholder for advanced LangChain4j wiring (currently empty).

**AI model config** (`application.properties`): default model `deepseek-v4-pro`, temperature `0.1`, max tokens `8192`, request/response logging enabled, LangChain4j debug logging on.

## API

```
GET/POST /ai/chat
  ?message=<user message>          (default: "Hello, how are you?")
  ?prompt=<system prompt>          (default: empty)
  ?rootDirectory=<path>            (default: empty; must be in allowlist)
  ?model=<model alias>             (default: "deepseek-v4-pro")

GET /ai/tools
  Returns JSON array of allowed rootDirectory values
```

Streaming response: Server-Sent Events with event types `tool_call`, `content`, `error`, `done`.

Supported model aliases:

| Alias | Provider | Requires env var |
|---|---|---|
| `deepseek-v4-pro` | DeepSeek (hand-rolled) | `DEEPSEEK_API_KEY` |
| `sonnet-4.6` | Anthropic Claude Sonnet 4.6 | `ANTHROPIC_API_KEY` |
| `opus-4.7` | Anthropic Claude Opus 4.7 | `ANTHROPIC_API_KEY` |
| `haiku-4.5` | Anthropic Claude Haiku 4.5 | `ANTHROPIC_API_KEY` |
| `gemini-2.5-pro` | Google Gemini 2.5 Pro | `GOOGLE_API_KEY` |
| `gemini-2.5-flash` | Google Gemini 2.5 Flash | `GOOGLE_API_KEY` |

## Frontend

React 19 + Vite 8 + Tailwind CSS 4 chat UI located in `frontend/`. Dev server runs on port 5176 and proxies `/ai` requests to the backend.

```bash
cd frontend
npm install
npm run dev    # http://localhost:5176
npm run build  # output to frontend/dist/
```

Key files:
- `App.tsx` — chat UI: model selector, SSE streaming, tool call display, system prompt and root directory inputs
- `api.ts` — `chatStream()`, `getDirectories()`, `execTool()` API client

Production deployment: `https://agent.breynisson.org`
