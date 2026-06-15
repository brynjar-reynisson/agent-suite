# Design: Screenshot Delivery via Clickable Thumbnails

**Date:** 2026-06-15  
**Branch:** `feature/deliver_screenshots`  
**Status:** Approved

---

## Problem

The `computer-control-mcp` MCP server (scoped to the REAPER root directory) can take screenshots via its `take_screenshot` tool. That tool returns an MCP `ImageContent` object (base64-encoded PNG). `McpToolBridge.callMcpTool()` currently filters responses to `TextContent` only, so `ImageContent` is silently dropped — the AI receives an empty result and cannot show the screenshot.

## Goal

Make screenshots from `computer-control-mcp` visible in the chat UI as clickable thumbnails that open a full-screen lightbox, in a pattern consistent with how `AudioTools`/`AudioController` deliver audio files.

---

## Architecture

### Delivery flow (Approach A — Automatic)

```
AI calls mcp__computer-control__take_screenshot
  → McpToolBridge receives CallToolResult containing ImageContent
  → delegates to ImageContentHandler.handle(imageContent)
    → decodes base64, writes screenshot_<UUID>.png to tmp_screenshot_files/
    → returns "![screenshot](http://<base-url>/images/screenshot_<UUID>.png)"
  → McpToolBridge joins any TextContent + image markdown, returns to AI
  → AI includes the markdown image link verbatim in its response
  → frontend ReactMarkdown renders it as a thumbnail
  → user clicks thumbnail → ImageLightbox opens full-screen
```

No extra AI tool call is required. Unlike audio (where the AI explicitly chooses where to render the file), screenshots are produced by the MCP server into a temp directory — the serve step is automatic.

---

## Backend Components

### 1. `application.properties` — new property

```properties
agent.image.dir=./tmp_screenshot_files
```

Mirrors `agent.audio.dir=./tmp_audio_files`.

### 2. `ImageContentHandler` (`@Component`)

- Package: `com.example.agentsuite.tools`
- Constructor-injected: `@Value("${agent.base-url}") String baseUrl`, `@Value("${agent.image.dir}") String imageDirStr`
- `@PostConstruct`: creates `tmp_screenshot_files/` if absent, logs the path
- Single public method: `String handle(McpSchema.ImageContent content)`
  - Reads `content.mimeType()` to determine extension; supported: `image/png → .png`, `image/jpeg → .jpg`, `image/webp → .webp`. Unsupported types return an error string.
  - Decodes `content.data()` (base64) → writes `screenshot_<UUID><ext>` to `imageDirPath`
  - Returns `![screenshot](<baseUrl>/images/<encoded-filename>)` (filename URL-encoded, `+` → `%20`)
  - On `IOException`: logs and returns an error string (does not throw)

### 3. `ImageController` (`@RestController`)

- Mirrors `AudioController` exactly, for images
- `@PostConstruct`: creates directory, logs path
- `GET /images/{filename}` — serves from `tmp_screenshot_files/`
  - Rejects filenames containing `/`, `\`, or `..` → 404
  - Extension-locked to `.png`, `.jpg`, `.jpeg`, `.webp`; unknown extension → 400
  - Path confinement check: resolved path must start with `imageDirPath` → 404 if not
  - Returns bytes with correct `Content-Type` and `X-Content-Type-Options: nosniff`
  - Intentionally unauthenticated (path-confined + extension-locked, same rationale as `/audio/`)

Content-Type map:
| Extension | Media Type |
|---|---|
| `.png` | `image/png` |
| `.jpg` / `.jpeg` | `image/jpeg` |
| `.webp` | `image/webp` |

### 4. `McpToolBridge` — modify `callMcpTool()`

- Add `ImageContentHandler imageContentHandler` to both constructors (the `@Autowired` Spring constructor and the package-private test constructor)
- In `callMcpTool()`, after collecting `TextContent` lines, also collect `ImageContent` items:

```java
List<String> parts = new ArrayList<>();

result.content().stream()
    .filter(c -> c instanceof McpSchema.TextContent)
    .map(c -> ((McpSchema.TextContent) c).text())
    .forEach(parts::add);

result.content().stream()
    .filter(c -> c instanceof McpSchema.ImageContent)
    .map(c -> imageContentHandler.handle((McpSchema.ImageContent) c))
    .forEach(parts::add);

String output = String.join("\n", parts);
```

- Error handling and `isError` check remain unchanged.

---

## Frontend Components

### 5. `ImageLightbox.tsx` — new component

```tsx
interface Props { src: string; alt: string; onClose: () => void; }
```

- Fixed full-viewport overlay (`position: fixed, inset: 0, z-index: 50`)
- Background: `bg-black/80` (semi-transparent dark)
- Image centred: `max-h-[90vh] max-w-[90vw]`, `object-contain`, subtle drop-shadow
- **X button** (`✕`): absolute top-right, closes on click
- **"Press Esc to close"** hint: absolute bottom-centre, muted text
- **Click overlay** (outside image): closes lightbox
- `useEffect` adds/removes `keydown` listener for `Escape` key on mount/unmount
- Renders via `ReactDOM.createPortal` into `document.body` (avoids stacking context issues)

### 6. `App.tsx` — two additions

**New state:**
```tsx
const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
```

**Custom `img` renderer** added to `ReactMarkdown` `components`:
```tsx
img: ({ src, alt }) => (
  <div style={{ position: 'relative', display: 'inline-block', maxWidth: 380 }}>
    <img
      src={src}
      alt={alt ?? 'screenshot'}
      onClick={() => src && setLightboxSrc(src)}
      style={{ maxWidth: '100%', borderRadius: 8, border: '1px solid #d0d8e8',
               display: 'block', cursor: 'pointer' }}
    />
    <div style={{ position: 'absolute', bottom: 8, right: 8,
                  background: 'rgba(0,0,0,0.55)', borderRadius: 5,
                  padding: '3px 8px', color: '#fff', fontSize: '0.72rem' }}>
      click to expand
    </div>
  </div>
)
```

**Lightbox rendered at App root** (just before closing `</div>`):
```tsx
{lightboxSrc && (
  <ImageLightbox src={lightboxSrc} alt="screenshot" onClose={() => setLightboxSrc(null)} />
)}
```

The `img` renderer applies to **all** markdown images, not just screenshots — any future markdown image links get the lightbox for free.

---

## Configuration

| Property | Dev value | Prod value |
|---|---|---|
| `agent.image.dir` | `./tmp_screenshot_files` | `./tmp_screenshot_files` |
| `agent.base-url` | `http://localhost:8090` | `https://agent.breynisson.org` |

`tmp_screenshot_files/` is gitignored (add alongside `tmp_audio_files/`).

No authentication required on `/images/` endpoint — same rationale as `/audio/`: path-confined to a single directory, extension-locked to known image types.

---

## Out of Scope

- Screenshot cleanup / expiry (files accumulate in `tmp_screenshot_files/`)
- Access control on `/images/` endpoint (MCP tool is already admin+REAPER-root scoped)
- Thumbnail generation at a reduced resolution (full PNG served as-is)
- Support for MCP servers other than `computer-control` returning images (handled generically)
