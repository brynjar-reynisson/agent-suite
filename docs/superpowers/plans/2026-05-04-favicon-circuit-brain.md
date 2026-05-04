# Favicon: Circuit Brain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Claude lightning bolt favicon with a circuit-brain SVG that represents AgentSuite as a multi-provider AI agent platform.

**Architecture:** Edit `frontend/public/favicon.svg` (the Vite source). Vite copies `public/` to `dist/` at build time; `frontend/dist/` is gitignored and should not be edited or committed directly. The SVG is hand-authored with a brain-silhouette path, four circuit trace lines, and five node circles.

**Tech Stack:** SVG (hand-authored), Vite (serves `public/` files as-is)

---

### Task 1: Write the new favicon

**Files:**
- Modify: `frontend/public/favicon.svg`

- [ ] **Step 1: Replace `frontend/public/favicon.svg` with the new design**

Overwrite the entire file with:

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" width="48" height="48" fill="none">
  <!-- Brain silhouette: two lobes with top cleft at (24,10) -->
  <path d="M24,10 C20,4 8,4 5,14 C3,22 5,32 12,37 C16,40 20,41 24,40 C28,41 32,40 36,37 C43,32 45,22 43,14 C40,4 28,4 24,10Z" fill="#863bff"/>
  <!-- Circuit traces (white, 70% opacity, angular) -->
  <line x1="14" y1="18" x2="34" y2="18" stroke="white" stroke-width="1.5" stroke-opacity="0.7"/>
  <line x1="14" y1="18" x2="12" y2="29" stroke="white" stroke-width="1.5" stroke-opacity="0.7"/>
  <line x1="34" y1="18" x2="36" y2="29" stroke="white" stroke-width="1.5" stroke-opacity="0.7"/>
  <line x1="12" y1="29" x2="24" y2="36" stroke="white" stroke-width="1.5" stroke-opacity="0.7"/>
  <!-- Circuit nodes (rendered above traces) -->
  <circle cx="14" cy="18" r="2.5" fill="white"/>
  <circle cx="34" cy="18" r="2.5" fill="#47bfff"/>
  <circle cx="12" cy="29" r="2.5" fill="white"/>
  <circle cx="36" cy="29" r="2.5" fill="white"/>
  <circle cx="24" cy="36" r="2.5" fill="white"/>
</svg>
```

Node layout:
- `(14,18)` — top-left, white
- `(34,18)` — top-right, cyan `#47bfff` (accent)
- `(12,29)` — center-left, white
- `(36,29)` — center-right, white (dead-end — realistic for circuit boards)
- `(24,36)` — bottom-center, white

Trace pattern (H with a tail): TL→TR (horizontal), TL→CL (left vertical), TR→CR (right vertical), CL→BC (bottom diagonal).

- [ ] **Step 2: Open the favicon in a browser to verify it looks right**

Open `frontend/public/favicon.svg` directly in a browser (drag-and-drop or `file://` URL). Check:
- Brain silhouette fills the canvas with visible top cleft
- White circuit traces and nodes are visible on the purple fill
- Cyan accent node at top-right is visible
- Everything reads cleanly when the browser tab shrinks the icon to ~16px

- [ ] **Step 3: Commit**

```bash
git add frontend/public/favicon.svg
git commit -m "feat: replace favicon with circuit-brain icon"
```
