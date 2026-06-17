# Vite Access Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add morgan HTTP access logging to the Vite dev and preview servers, writing to separate append-mode log files in `logs/`.

**Architecture:** A local Vite plugin (`frontend/plugins/morganPlugin.ts`) exports a factory function that injects morgan into the connect middleware stack via Vite's `configureServer` and `configurePreviewServer` hooks. `vite.config.ts` imports and registers it with dev/prod log file paths. No test framework exists in this frontend — verification is manual.

**Tech Stack:** Vite 8, TypeScript, morgan, Node.js `fs` streams

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `frontend/plugins/morganPlugin.ts` | Plugin factory — opens write stream, injects morgan middleware |
| Modify | `frontend/vite.config.ts` | Import and register the plugin |
| Modify | `frontend/package.json` | Add `morgan` + `@types/morgan` devDependencies |

---

### Task 1: Create feature branch

- [ ] **Step 1: Create and switch to feature branch**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite checkout -b feature/vite_access_log
```

Expected: `Switched to a new branch 'feature/vite_access_log'`

---

### Task 2: Install morgan dependencies

**Files:**
- Modify: `frontend/package.json`

- [ ] **Step 1: Install morgan and its types**

Run from the `frontend/` directory:

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npm install --save-dev morgan @types/morgan
```

Expected: both packages appear under `devDependencies` in `package.json` and `package-lock.json` is updated.

- [ ] **Step 2: Verify installation**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npm list morgan @types/morgan
```

Expected output (versions may differ):
```
frontend@0.0.0
├── @types/morgan@...
└── morgan@...
```

- [ ] **Step 3: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/package.json frontend/package-lock.json
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "chore: add morgan and @types/morgan devDependencies"
```

---

### Task 3: Create the morgan Vite plugin

**Files:**
- Create: `frontend/plugins/morganPlugin.ts`

- [ ] **Step 1: Create the plugin file**

Create `frontend/plugins/morganPlugin.ts` with this exact content:

```typescript
import fs from 'node:fs'
import path from 'node:path'
import morgan from 'morgan'
import type { Plugin } from 'vite'

export interface MorganPluginOptions {
  devLogFile: string
  previewLogFile: string
  format?: string
}

export function morganPlugin(options: MorganPluginOptions): Plugin {
  const format = options.format ?? 'combined'

  return {
    name: 'morgan-access-log',

    configureServer(server) {
      const logPath = path.resolve(process.cwd(), options.devLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.middlewares.use(morgan(format, { stream }))
    },

    configurePreviewServer(server) {
      const logPath = path.resolve(process.cwd(), options.previewLogFile)
      fs.mkdirSync(path.dirname(logPath), { recursive: true })
      const stream = fs.createWriteStream(logPath, { flags: 'a' })
      server.middlewares.use(morgan(format, { stream }))
    },
  }
}
```

Key details:
- `path.resolve(process.cwd(), ...)` — `process.cwd()` is `frontend/` when Vite runs, so `'../logs/...'` resolves to the project-root `logs/` directory.
- `fs.mkdirSync(..., { recursive: true })` — creates `logs/` if it doesn't exist yet.
- `flags: 'a'` — append mode; restarts do not truncate existing log files.
- Morgan is injected before Vite's own middleware, so every request (including proxied `/ai` and `/audio`) is logged.

- [ ] **Step 2: Type-check**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/plugins/morganPlugin.ts
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: add morganPlugin Vite plugin for access logging"
```

---

### Task 4: Wire plugin into vite.config.ts

**Files:**
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Update vite.config.ts**

Replace the entire contents of `frontend/vite.config.ts` with:

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { morganPlugin } from './plugins/morganPlugin'

export default defineConfig(() => {
  return {
    plugins: [
      react(),
      morganPlugin({
        devLogFile: '../logs/frontend-dev-access.log',
        previewLogFile: '../logs/frontend-prod-access.log',
        format: 'combined',
      }),
    ],
    server: {
      port: 5177,
      host: '0.0.0.0',
      allowedHosts: ['dev.agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: 'http://localhost:8090',
          changeOrigin: true,
        },
        '/audio': {
          target: 'http://localhost:8090',
          changeOrigin: true,
        },
      },
    },
    preview: {
      port: 5176,
      host: '0.0.0.0',
      allowedHosts: ['agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: 'http://localhost:8091',
          changeOrigin: true,
        },
        '/audio': {
          target: 'http://localhost:8091',
          changeOrigin: true,
        },
      },
    },
  }
})
```

- [ ] **Step 2: Type-check**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add frontend/vite.config.ts
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "feat: register morganPlugin in vite.config.ts"
```

---

### Task 5: Manual verification

No automated tests exist for Vite plugins in this project. Verify manually.

- [ ] **Step 1: Start the dev server**

```bash
cd C:/Users/Lenovo/IdeaProjects/agent-suite/frontend && npm run dev
```

Expected: server starts on port 5177 with no errors.

- [ ] **Step 2: Make a request and check the dev log**

In a separate terminal, make any HTTP request to the dev server:

```bash
curl -s http://localhost:5177/ > /dev/null
```

Then check the log:

```bash
cat C:/Users/Lenovo/IdeaProjects/agent-suite/logs/frontend-dev-access.log
```

Expected: a line in Apache Combined Log Format, e.g.:
```
::1 - - [17/Jun/2026:10:00:00 +0000] "GET / HTTP/1.1" 200 1234 "-" "curl/8.x"
```

- [ ] **Step 3: Stop the dev server and restart it**

Stop with Ctrl+C, restart with `npm run dev`, make another request, then re-check the log.

Expected: the new line is **appended** — the previous line is still there (append mode confirmed).

- [ ] **Step 4: Update docs**

Add a row to the logging table in `docs/dev/build-and-run.md` under a new **Logging** section (or append to the existing content):

```markdown
## Frontend Logging

| Environment | Log file |
|-------------|----------|
| Dev (port 5177) | `logs/frontend-dev-access.log` |
| Prod/preview (port 5176) | `logs/frontend-prod-access.log` |

Morgan `combined` format. Append-mode — not truncated on restart. `logs/` is gitignored.
```

- [ ] **Step 5: Commit docs**

```bash
git -C C:/Users/Lenovo/IdeaProjects/agent-suite add docs/dev/build-and-run.md
git -C C:/Users/Lenovo/IdeaProjects/agent-suite commit -m "docs: document frontend access log files"
```
