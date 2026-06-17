# Vite Access Log — Design

**Date:** 2026-06-17  
**Branch:** feature/vite_access_log  
**Status:** Approved

## Goal

Add HTTP access logging to the Vite dev and preview servers using morgan, writing to separate files per environment. Logs land alongside the existing Spring Boot log files in the project-root `logs/` directory (already gitignored).

## Architecture

A local Vite plugin (`frontend/plugins/morganPlugin.ts`) injects morgan as connect middleware into both the dev server and the preview server. `vite.config.ts` imports and registers the plugin.

## New File: `frontend/plugins/morganPlugin.ts`

Exports a single factory function:

```ts
interface MorganPluginOptions {
  devLogFile: string      // path relative to frontend working directory
  previewLogFile: string  // used for vite preview (prod)
  format?: string         // defaults to 'combined'
}

export function morganPlugin(options: MorganPluginOptions): Plugin
```

**`configureServer` hook** (dev):
1. Opens an append-mode `fs.WriteStream` to `options.devLogFile` (creates file if absent)
2. Creates `morgan(format, { stream })` middleware
3. Calls `server.middlewares.use(morganMiddleware)`

**`configurePreviewServer` hook** (preview/prod):
1. Same as above but using `options.previewLogFile`
2. Calls `server.middlewares.use(morganMiddleware)`

Morgan is injected before Vite's own middleware, so every request — including proxied `/ai` and `/audio` calls — is logged.

## Changes to `frontend/vite.config.ts`

```ts
import { morganPlugin } from './plugins/morganPlugin'

plugins: [
  react(),
  morganPlugin({
    devLogFile: '../logs/frontend-dev-access.log',
    previewLogFile: '../logs/frontend-prod-access.log',
    format: 'combined',
  }),
],
```

## Dependencies

Add to `frontend/package.json` devDependencies:
- `morgan`
- `@types/morgan`

## Log File Locations

| Environment | File |
|-------------|------|
| Dev (port 5177) | `logs/frontend-dev-access.log` |
| Preview/prod (port 5176) | `logs/frontend-prod-access.log` |

Both paths are in `logs/` at the project root, which is already gitignored. The write stream opens in append mode, so restarts do not truncate existing logs.

## Out of Scope

- Log rotation (can be added later with `rotating-file-stream` if logs grow too large)
- Stdout logging
- Filtering specific routes
