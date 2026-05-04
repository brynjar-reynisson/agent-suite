# Favicon: Circuit Brain Design

## Summary

Replace the current Anthropic/Claude lightning bolt favicon with a custom circuit-brain icon that better represents AgentSuite as a multi-provider AI agent platform.

## Visual Design

**Canvas**: 48×48 square viewBox (SVG)

**Brain shape**: Two overlapping rounded lobes forming a classic brain silhouette, with a center cleft at top and bottom. Filled solid with `#863bff` (existing purple, carried over for brand continuity).

**Circuit overlay**:
- 5 nodes (white filled circles, radius 2px) at: top-left lobe, top-right lobe, center-left, center-right, bottom-center
- 4 straight lines connecting the nodes in an angular circuit trace pattern (orthogonal/diagonal — no curves)
- Lines in white at 70% opacity so the brain fill shows through
- One accent node (top-right) in cyan `#47bfff` — the secondary accent color from the existing favicon

**No blur/glow filters** — crisp edges for readability at 16×16 browser tab size.

## Files Affected

- `frontend/public/favicon.svg` — primary source
- `frontend/dist/favicon.svg` — built output (updated via `npm run build` or manually synced)

## Success Criteria

- Favicon renders cleanly at 16×16, 32×32, and 48×48
- Brain silhouette is recognizable at small sizes
- Circuit overlay adds tech/agentic character without becoming noise
