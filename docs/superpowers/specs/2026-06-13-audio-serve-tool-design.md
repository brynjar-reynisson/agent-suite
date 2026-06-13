# Audio Serve Tool Design

**Date:** 2026-06-13
**Branch:** feature/audio_play
**Status:** Approved

## Overview

Add an `"audio"` tool group (admin-only) that lets the AI agent instruct REAPER to render an audio file into a local temp directory, then return a fully-qualified public URL the user can click to play the audio in their browser — including on remote devices.

## User Flow

1. User asks the AI to render something (e.g. "render a preview of the current project").
2. AI calls REAPER MCP tools to render to `{agent-working-dir}/tmp_audio_files/{filename}.wav`.
3. AI calls `serve_audio_file("{abs_path}")`.
4. Tool validates and returns `https://agent.breynisson.org/audio/{filename}.wav`.
5. AI includes the URL in its markdown response.
6. Frontend detects `.wav`/`.mp3` links and renders an inline `<audio>` player.

## Backend

### `AudioTools`

Plain Java class (same pattern as `UnixTools`, `WebTools`). Instantiated in `AiController.buildToolInstances` with `baseUrl` and `audioDir`.

**Constructor:** `AudioTools(String baseUrl, Path audioDir)`

**Tool method:** `serve_audio_file(String absolutePath) -> String`

Validation (in order):
1. Resolve the path and check it stays within `audioDir` (same `canonical-path` confinement check as `UnixTools.escapesRoot`). Return error string on violation — never expose the real path.
2. Extension must be `.wav` or `.mp3` (case-insensitive). Return error string otherwise.
3. File must exist and be a regular file. Return error string otherwise.

On success: return `{baseUrl}/audio/{filename}` (filename only, no directory).

Error strings follow existing tool convention (plain text, no stack traces, no filesystem paths):
- `"Error: file not found."`
- `"Error: only .wav and .mp3 files are supported."`
- `"Error: access to paths outside the audio directory is not allowed."`

### `AudioController`

New `@RestController`. Endpoint: `GET /audio/{filename}`

- Resolves `audioDir.resolve(filename)`, canonical-path confinement check.
- Rejects filenames containing `/` or `\` (extra guard; `{filename}` path variable won't span segments but explicit check adds clarity).
- Extension check: `.wav` → `audio/wav`, `.mp3` → `audio/mpeg`. Returns `400` for other extensions.
- File not found → `404`. No path information in error body.
- Streams file as response body using `Files.copy(path, response.getOutputStream())`.

### Configuration

`application.properties`:
```properties
agent.base-url=http://localhost:8090
agent.audio.dir=./tmp_audio_files
```

`application-prod.properties`:
```properties
agent.base-url=https://agent.breynisson.org
```

Both `AudioTools` and `AudioController` receive `agent.base-url` and `agent.audio.dir` via `@Value` injection in `AiController` (for `AudioTools`) and directly in `AudioController`.

`AudioController` creates `tmp_audio_files/` on startup (`@PostConstruct`) if it does not exist.

### Auth & Tool Group Wiring

`AuthorizationService.grantedToolGroups(isAdmin)`:
- Before: `["web", "md-writer", "mcp"]` for admins
- After: `["web", "md-writer", "mcp", "audio"]` for admins

`AiController.buildToolInstances` — new case:
```java
case "audio" -> instances.add(new AudioTools(baseUrl, audioDirPath));
```

`AiController` gains two new `@Value` fields: `agent.base-url` and `agent.audio.dir`.

### `.gitignore`

Add `tmp_audio_files/` to `.gitignore`.

## Frontend

`App.tsx`: add `components` prop to the existing `<ReactMarkdown>` with a custom `a` renderer.

**Rule:** if `href` ends with `.wav` or `.mp3` (case-insensitive), render:
```tsx
<audio controls src={href} className="w-full mt-1" />
<a href={href} target="_blank" rel="noopener noreferrer" className="text-xs text-gray-400">
  {children}
</a>
```
Otherwise render a normal `<a>` tag (preserving current behaviour for all other links).

No new npm dependencies required.

## Security

- **Path confinement:** both `AudioTools` and `AudioController` independently apply the canonical-path check. A compromised or confused AI cannot use the serve endpoint to read arbitrary files.
- **Extension allowlist:** only `.wav` and `.mp3` are ever read or served. No executable, config, or other file types can be reached even if path confinement were bypassed.
- **No path in errors:** error responses from both tool and endpoint never include filesystem paths.
- **Admin-only:** the `"audio"` tool group is only granted to admins via `AuthorizationService`.
- **CORS:** existing CORS config already covers `localhost:5176/5177` and `agent.breynisson.org` — no changes needed.

## Error Handling

| Scenario | `AudioTools` returns | `AudioController` returns |
|---|---|---|
| File not found | error string | 404 |
| Wrong extension | error string | 400 |
| Path traversal | error string | 404 |
| I/O error on serve | n/a | 500 (Spring default) |

## Testing

### `AudioToolsTest`

Unit tests (no Spring context):
- Valid `.wav` path in audio dir → returns correct URL
- Valid `.mp3` path in audio dir → returns correct URL
- Path traversal (`../secrets.txt`) → returns confinement error
- Absolute path outside audio dir → returns confinement error
- Unsupported extension (`.ogg`) → returns extension error
- File does not exist → returns not-found error
- Extension check is case-insensitive (`.WAV`, `.MP3`)

### `AudioControllerTest`

MockMvc tests:
- `GET /audio/valid.wav` with file present → 200, `Content-Type: audio/wav`
- `GET /audio/valid.mp3` with file present → 200, `Content-Type: audio/mpeg`
- `GET /audio/missing.wav` → 404
- `GET /audio/bad.ogg` → 400
- `GET /audio/../secrets.txt` → 404 (Spring path variable won't match but explicit check verified)

## Out of Scope

- Temp file cleanup (deferred).
- Audio file size limits (deferred).
- Waveform visualisation in the frontend (deferred).
- Non-admin access to the `/audio/` endpoint (currently unauthenticated but path-confined and extension-locked; auth can be added later if needed).
