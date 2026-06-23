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

- `App.tsx` — shell: layout, header, settings panel, footer input. Owns UI state (model, prompt, rootDirectory, disabledTools, panel/modal/lightbox open flags). Wires `useConversation` and `useUserConfig` hooks together; derives `availableTools` from `grantedToolGroups` + `rootDirectory`; filters `PROMPT_BANK` for non-admins.
- `useConversation.ts` — all conversation logic. Owns messages, loading, error toast, conversationId, lastSentModel/Prompt refs. Exposes `handleSend` (dispatches `!exec`, `/compact`, `/compact-merge`, and normal chat streaming), `loadConversation` (returns `ConversationDetail` for App to apply settings from), and `resetConversation`. Resets on sign-out by watching `useAuth()` internally.
- `useUserConfig.ts` — fetches `UserConfig` (`isAdmin`, `grantedToolGroups`) via `/ai/config/user` on load and on auth change.
- `MessageList.tsx` — renders all message types (user, ai with tool calls, meta, compact). Owns the auto-scroll ref and history-size MB pill (gray < 1.5 MB, amber 1.5–2.5 MB, red ≥ 2.5 MB). Custom `img` renderer produces clickable thumbnails (max 380px) that fire `onImageClick` to open `ImageLightbox`. Custom `pre` renderer routes fenced code blocks that carry a language hint through `FileContentRenderer`. Messages with `sourceLanguage` set bypass ReactMarkdown entirely and render directly via `FileContentRenderer`.
- `FileContentRenderer.tsx` — extensible file content renderer. Dispatches on language: `md` → full ReactMarkdown rendering (styled card, scrollable); all others → plain dark `<pre><code>`. Add new language cases here as needed.
- `config.ts` — `MODELS` array and `PROMPT_BANK` definitions.
- `MetaMessage.tsx` — renders model/system-prompt change markers in the chat timeline.
- `PromptCombobox.tsx` — text input with a dropdown preset picker backed by `PROMPT_BANK`.
- `ToolStrip.tsx` — icon-only strip above the input showing active tool groups from `availableTools`. Click-to-toggle disabled state; disabled tools are excluded from the `tools` param sent to the backend (opt-out).
- `ImageLightbox.tsx` — full-screen lightbox overlay rendered via `ReactDOM.createPortal`. Closes on X button, Escape key, or click outside the image.
- `api.ts` — `chatStream()`, `getDirectories()`, `getUserConfig()` (returns `UserConfig`), `execTool()` API client. `ChatRequest` includes optional `conversationId` and `tools`. `Message` includes optional `sourceLanguage` for client-side file rendering (not persisted to backend).
- `plugins/morganPlugin.ts` — Vite plugin that injects morgan HTTP access logging middleware into both the dev server (`configureServer`) and preview server (`configurePreviewServer`). Opens an append-mode write stream to a log file per environment; uses `config.root` (from `configResolved`) as the path anchor. Log files: `logs/frontend-dev-access.log` (dev) and `logs/frontend-prod-access.log` (prod). Format: Apache Combined Log. `logs/` is gitignored.
