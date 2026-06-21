# API Reference

## Endpoints

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

GET /ai/config/mcp-tools
  ?rootDirectory=<path>            (optional; must be in allowlist, 400 otherwise)
  Admin-only. Returns JSON array of connected MCP tool names (mcp__<server>__<tool>),
  merged global + per-root for the given rootDirectory. Non-admins get 403.

POST /ai/exec
  ?command=<shell command>        (required; passed to cmd /c on Windows, sh -c on Unix)
  ?rootDirectory=<path>           (required; must be non-empty and in allowlist; used as CWD)
  Admin-only. Executes a shell command with rootDirectory as CWD. Returns text/event-stream.
  Events: output/<line>, done/<exit code>, error/<message>. Non-admins get 403.
  Empty or invalid rootDirectory gets 400. Process timeout: 10 minutes.

POST /ai/conversations/{externalId}/compact
  Summarises conversation history via LLM and stores result as a `compact` message.
  Auth required; caller must own the conversation (404 otherwise).
  Returns { "summary": "..." } on success, 400 if nothing to compact, 404 if not found.

POST /ai/conversations/{externalId}/compact-merge
  Concatenates the last two compact rows for a conversation (older first, newer second,
  separated by ---) and stores the result as a new compact row. No LLM is involved.
  Auth required; caller must own the conversation (404 otherwise).
  Returns { "summary": "..." } on success, 400 if fewer than two compacts exist, 404 if not found.

GET /audio/{filename}
  Serves a WAV or MP3 file from tmp_audio_files/. Intentionally unauthenticated (path-confined + extension-locked).
  Supports HTTP Range requests (responds 206 Partial Content with Content-Range when a Range header is present,
  200 for full-file requests). Advertises Accept-Ranges: bytes. 404 if not found, 400 for unsupported extension.

GET /images/{filename}
  Serves a PNG/JPG/JPEG/WEBP screenshot from tmp_screenshot_files/. Intentionally unauthenticated (path-confined + extension-locked).
  Returns 200 with the appropriate image content-type, 404 if not found, 400 for unsupported extension.
```

Streaming response: Server-Sent Events with event types `tool_call`, `content`, `error`, `done`.

## Supported Models

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
