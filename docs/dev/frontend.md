# Frontend

React 19 + Vite 8 + Tailwind CSS 4 chat UI located in `frontend/`.

## Setup & Dev

```bash
cd frontend
cp frontend/.env.example frontend/.env  # first-time setup: copy dev defaults
npm install
npm run dev    # http://localhost:5177 (dev environment, proxies to backend 8090)
npm run prod   # http://localhost:5176 (prod environment, proxies to backend 8091)
npm run build  # output to frontend/dist/
```

Production deployment: `https://agent.breynisson.org`

## Key Files

- `App.tsx` — chat UI: model selector, SSE streaming, tool call display, system prompt and root directory inputs. Generates a UUID per session (`crypto.randomUUID()` in a `useRef`) and passes it as `conversationId` on every request. Fetches `UserConfig` (isAdmin + grantedToolGroups) via `/ai/config/user` on load and auth change; derives `availableTools` from `grantedToolGroups` plus `unix`/`md-writer` context gates; filters `PROMPT_BANK` to hide `md-writer` prompts for non-admins; resets all conversation state on sign-out. Computes `historySizeBytes` (useMemo over messages since last compact) and renders a colour-coded MB pill in the bottom-right of the chat area (gray < 1.5 MB, amber 1.5–2.5 MB, red ≥ 2.5 MB). Adds a custom `img` renderer to `ReactMarkdown` that renders markdown images (e.g. screenshots from MCP tools) as clickable thumbnails (max 380px); clicking opens `ImageLightbox` via `lightboxSrc` state.
- `ToolStrip.tsx` — icon-only strip above the input showing active tool groups from `availableTools`. Click-to-toggle disabled state; disabled tools are excluded from the `tools` param sent to the backend (opt-out).
- `ImageLightbox.tsx` — full-screen lightbox overlay rendered via `ReactDOM.createPortal`. Closes on X button, Escape key, or click outside the image.
- `api.ts` — `chatStream()`, `getDirectories()`, `getUserConfig()` (returns `UserConfig`), `execTool()` API client. `ChatRequest` includes optional `conversationId` and `tools`.
