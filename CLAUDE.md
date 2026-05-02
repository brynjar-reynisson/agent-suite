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

Server runs on `http://localhost:8090`. Requires `DEEPSEEK_API_KEY` environment variable pointing to the DeepSeek API.

## Architecture

Spring Boot 3.5 + LangChain4j 0.36.2 agent application. Java 25.

**Request flow:** `AiController` → `DeepSeekService` → DeepSeek API (OpenAI-compatible interface at `https://api.deepseek.com/v1`)

**Key layers:**
- `AiController` — single `/ai/chat` GET/POST endpoint. Validates `rootDirectory` against a hardcoded allowlist before passing it to the service.
- `DeepSeekService` — drives the agentic loop: sends messages to the model, executes tool calls returned by the model, caches reasoning content between turns, repeats until no more tool calls.
- `UnixTools` — exposes `ls` and `cat` as AI-callable tools. Blocks `..` path traversal. Scoped to the `rootDirectory` passed at request time.
- `LangChain4jConfig` — placeholder for advanced LangChain4j wiring (currently empty).

**AI model config** (`application.properties`): model `deepseek-v4-pro`, temperature `0.1`, max tokens `8192`, request/response logging enabled, LangChain4j debug logging on.

## API

```
GET/POST /ai/chat
  ?message=<user message>          (default: "Hello, how are you?")
  ?prompt=<system prompt>          (default: empty)
  ?rootDirectory=<path>            (default: empty; must be in allowlist)
```

Returns plain text AI response.
