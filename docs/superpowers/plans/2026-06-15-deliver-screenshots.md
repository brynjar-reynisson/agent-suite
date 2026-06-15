# Screenshot Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make screenshots from the `computer-control-mcp` MCP server visible in the chat UI as clickable thumbnails that open a full-screen lightbox.

**Architecture:** `McpToolBridge.callMcpTool()` currently silently drops `ImageContent` from MCP responses. We add an `ImageContentHandler` Spring bean that saves the base64 PNG to `tmp_screenshot_files/` and returns a markdown image URL. A new `ImageController` serves those files. The React frontend adds a custom `img` renderer (thumbnail + "click to expand") and a new `ImageLightbox` component (full-screen overlay with X button and Escape key).

**Tech Stack:** Java 21, Spring Boot 3.5, MCP SDK 2.0.0 (`io.modelcontextprotocol.spec.McpSchema`), React 19, TypeScript, Tailwind CSS 4, ReactDOM portals.

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Modify | `src/main/resources/application.properties` | Add `agent.image.dir` property |
| Modify | `.gitignore` | Ignore `tmp_screenshot_files/` |
| **Create** | `src/main/java/com/example/agentsuite/tools/ImageContentHandler.java` | Save `ImageContent` to disk, return markdown URL |
| **Create** | `src/test/java/com/example/agentsuite/tools/ImageContentHandlerTest.java` | Unit tests for above |
| **Create** | `src/main/java/com/example/agentsuite/controller/ImageController.java` | Serve `GET /images/{filename}` |
| **Create** | `src/test/java/com/example/agentsuite/controller/ImageControllerTest.java` | Unit tests for above |
| Modify | `src/main/java/com/example/agentsuite/tools/McpToolBridge.java` | Inject `ImageContentHandler`; handle `ImageContent` in `callMcpTool` |
| Modify | `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java` | Pass `null` handler to existing tests; add image content test |
| **Create** | `frontend/src/ImageLightbox.tsx` | Full-screen overlay component |
| Modify | `frontend/src/App.tsx` | Add `lightboxSrc` state, `img` renderer, `<ImageLightbox>` render |

---

## Task 1: Feature branch + config

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `.gitignore`

- [ ] **Step 1: Create the feature branch**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" checkout -b feature/deliver_screenshots
```

- [ ] **Step 2: Add `agent.image.dir` to application.properties**

Open `src/main/resources/application.properties`. After the `agent.audio.dir` line (line 30), add:

```properties
agent.image.dir=./tmp_screenshot_files
```

- [ ] **Step 3: Add `tmp_screenshot_files/` to .gitignore**

Open `.gitignore`. After the `tmp_audio_files/` entry (line 58), add:

```
tmp_screenshot_files/
```

- [ ] **Step 4: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/resources/application.properties .gitignore
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "config: add agent.image.dir property and gitignore tmp_screenshot_files"
```

---

## Task 2: `ImageContentHandler` (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/tools/ImageContentHandlerTest.java`
- Create: `src/main/java/com/example/agentsuite/tools/ImageContentHandler.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/tools/ImageContentHandlerTest.java`:

```java
package com.example.agentsuite.tools;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ImageContentHandlerTest {

    @TempDir
    Path imageDir;

    ImageContentHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ImageContentHandler("http://localhost:8090", imageDir.toString());
        handler.init();
    }

    private McpSchema.ImageContent imageContent(String mimeType) {
        byte[] fakeBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes
        String base64 = Base64.getEncoder().encodeToString(fakeBytes);
        return McpSchema.ImageContent.builder(base64, mimeType).build();
    }

    @Test
    void handle_pngContent_savesFileAndReturnsMarkdownUrl() throws Exception {
        String result = handler.handle(imageContent("image/png"));

        assertThat(result).startsWith("![screenshot](http://localhost:8090/images/screenshot_");
        assertThat(result).endsWith(".png)");

        // file was written to the image dir
        String filename = result.replaceAll(".*images/(.+)\\)", "$1");
        assertThat(Files.exists(imageDir.resolve(filename))).isTrue();
    }

    @Test
    void handle_jpegContent_savesFileWithJpgExtension() {
        String result = handler.handle(imageContent("image/jpeg"));

        assertThat(result).endsWith(".jpg)");
    }

    @Test
    void handle_webpContent_savesFileWithWebpExtension() {
        String result = handler.handle(imageContent("image/webp"));

        assertThat(result).endsWith(".webp)");
    }

    @Test
    void handle_unsupportedMimeType_returnsErrorString() {
        String result = handler.handle(imageContent("image/bmp"));

        assertThat(result).contains("Error").contains("image/bmp");
    }

    @Test
    void handle_filenameIsUrlEncoded() {
        // All generated filenames are alphanumeric + underscores + hyphens — no encoding needed.
        // This test verifies the URL does not contain spaces or raw special characters.
        String result = handler.handle(imageContent("image/png"));
        String url = result.replaceAll("!\\[screenshot]\\((.+)\\)", "$1");
        assertThat(url).doesNotContain(" ");
        assertThat(url).startsWith("http://localhost:8090/images/screenshot_");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=ImageContentHandlerTest -q 2>&1 | tail -5
```

Expected: compilation error (`ImageContentHandler` does not exist).

- [ ] **Step 3: Implement `ImageContentHandler`**

Create `src/main/java/com/example/agentsuite/tools/ImageContentHandler.java`:

```java
package com.example.agentsuite.tools;

import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
public class ImageContentHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageContentHandler.class);
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/png",  ".png",
            "image/jpeg", ".jpg",
            "image/webp", ".webp"
    );

    private final String baseUrl;
    private final Path imageDir;

    public ImageContentHandler(
            @Value("${agent.base-url}") String baseUrl,
            @Value("${agent.image.dir}") String imageDirStr) {
        this.baseUrl = baseUrl;
        this.imageDir = Path.of(imageDirStr).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(imageDir);
        log.info("Image serve directory: {}", imageDir);
    }

    public String handle(McpSchema.ImageContent content) {
        String ext = MIME_TO_EXT.get(content.mimeType());
        if (ext == null) {
            return "Error: unsupported image MIME type: " + content.mimeType();
        }

        String filename = "screenshot_" + UUID.randomUUID() + ext;
        Path dest = imageDir.resolve(filename);
        try {
            byte[] bytes = Base64.getDecoder().decode(content.data());
            Files.write(dest, bytes);
        } catch (IOException e) {
            log.error("Failed to save screenshot {}", filename, e);
            return "Error: failed to save screenshot: " + e.getMessage();
        }

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "![screenshot](" + baseUrl + "/images/" + encoded + ")";
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=ImageContentHandlerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/tools/ImageContentHandler.java src/test/java/com/example/agentsuite/tools/ImageContentHandlerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add ImageContentHandler to save MCP ImageContent to disk"
```

---

## Task 3: `ImageController` (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/controller/ImageControllerTest.java`
- Create: `src/main/java/com/example/agentsuite/controller/ImageController.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/controller/ImageControllerTest.java`:

```java
package com.example.agentsuite.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageControllerTest {

    @TempDir
    Path imageDir;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        ImageController controller = new ImageController(imageDir.toString());
        controller.init();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getImage_validPng_returns200WithCorrectContentType() throws Exception {
        Files.write(imageDir.resolve("shot.png"), new byte[]{(byte)0x89, 0x50, 0x4E, 0x47});

        mockMvc.perform(get("/images/shot.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"));
    }

    @Test
    void getImage_validJpg_returns200WithCorrectContentType() throws Exception {
        Files.write(imageDir.resolve("shot.jpg"), new byte[]{(byte)0xFF, (byte)0xD8});

        mockMvc.perform(get("/images/shot.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void getImage_validJpeg_returns200WithCorrectContentType() throws Exception {
        Files.write(imageDir.resolve("shot.jpeg"), new byte[]{(byte)0xFF, (byte)0xD8});

        mockMvc.perform(get("/images/shot.jpeg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void getImage_validWebp_returns200WithCorrectContentType() throws Exception {
        Files.write(imageDir.resolve("shot.webp"), "RIFF fake webp".getBytes());

        mockMvc.perform(get("/images/shot.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"));
    }

    @Test
    void getImage_missingFile_returns404() throws Exception {
        mockMvc.perform(get("/images/missing.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getImage_badExtension_returns400() throws Exception {
        mockMvc.perform(get("/images/script.exe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getImage_dotDotTraversal_returns404() {
        ImageController controller = new ImageController(imageDir.toString());
        ResponseEntity<byte[]> response = controller.serveImage("../secret.png");
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getImage_responseBodyMatchesFile() throws Exception {
        byte[] data = {1, 2, 3, 4};
        Files.write(imageDir.resolve("data.png"), data);

        mockMvc.perform(get("/images/data.png"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(data));
    }

    @Test
    void getImage_hasNoSniffHeader() throws Exception {
        Files.write(imageDir.resolve("shot.png"), new byte[]{1});

        mockMvc.perform(get("/images/shot.png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=ImageControllerTest -q 2>&1 | tail -5
```

Expected: compilation error (`ImageController` does not exist).

- [ ] **Step 3: Implement `ImageController`**

Create `src/main/java/com/example/agentsuite/controller/ImageController.java`:

```java
package com.example.agentsuite.controller;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
public class ImageController {

    private static final Logger log = LoggerFactory.getLogger(ImageController.class);
    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            ".png",  MediaType.parseMediaType("image/png"),
            ".jpg",  MediaType.parseMediaType("image/jpeg"),
            ".jpeg", MediaType.parseMediaType("image/jpeg"),
            ".webp", MediaType.parseMediaType("image/webp")
    );

    private final Path imageDir;

    public ImageController(@Value("${agent.image.dir}") String imageDirStr) {
        this.imageDir = Path.of(imageDirStr).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(imageDir);
        log.info("Image serve directory: {}", imageDir);
    }

    // Intentionally unauthenticated: access is path-confined to tmp_screenshot_files/ and
    // extension-locked to known image types.
    @GetMapping("/images/{filename}")
    public ResponseEntity<byte[]> serveImage(@PathVariable String filename) {
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return ResponseEntity.notFound().build();
        }

        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.')).toLowerCase()
                : "";
        MediaType mediaType = CONTENT_TYPES.get(ext);
        if (mediaType == null) {
            return ResponseEntity.badRequest().build();
        }

        Path file = imageDir.resolve(filename).normalize();
        if (!file.startsWith(imageDir)) {
            return ResponseEntity.notFound().build();
        }

        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] bytes = Files.readAllBytes(file);
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        } catch (IOException e) {
            log.error("Failed to read image file {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=ImageControllerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/controller/ImageController.java src/test/java/com/example/agentsuite/controller/ImageControllerTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add ImageController serving GET /images/{filename}"
```

---

## Task 4: Wire `ImageContentHandler` into `McpToolBridge`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`
- Modify: `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`

- [ ] **Step 1: Add the new test for ImageContent handling**

Open `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`.

Add these imports at the top (after existing imports):

```java
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import java.nio.file.Path;
import java.util.Base64;
import static org.mockito.Mockito.any;
```

Add this test method at the end of the class, before the closing `}`:

```java
@Test
void callMcpTool_imageContent_savesFileAndReturnsMarkdownUrl() throws IOException {
    Path config = tempDir.resolve(".mcp.json");
    Files.writeString(config, """
            {
              "mcpServers": {
                "cc": { "command": "x", "args": [] }
              }
            }""");

    Map<String, Object> emptySchema = Map.of("type", "object");
    McpSchema.Tool tool = McpSchema.Tool.builder("take_screenshot", emptySchema)
            .description("Take a screenshot").build();
    McpSchema.ListToolsResult listResult = new McpSchema.ListToolsResult(List.of(tool), null);

    // ImageContent with 4-byte PNG header as base64
    String base64Png = Base64.getEncoder().encodeToString(new byte[]{(byte)0x89,0x50,0x4E,0x47});
    McpSchema.ImageContent imageContent = McpSchema.ImageContent.builder(base64Png, "image/png").build();
    McpSchema.CallToolResult callResult = new McpSchema.CallToolResult(List.of(imageContent), false, null, null);

    McpSyncClient mockClient = mock(McpSyncClient.class);
    when(mockClient.listTools()).thenReturn(listResult);
    when(mockClient.callTool(any())).thenReturn(callResult);

    // Use a real ImageContentHandler backed by a temp dir
    Path imageDir = tempDir.resolve("images");
    Files.createDirectories(imageDir);
    ImageContentHandler imgHandler = new ImageContentHandler("http://localhost:8090", imageDir.toString());
    imgHandler.init();

    McpToolBridge bridge = new McpToolBridge(config.toString(), List.of(), 30,
            (name, cfg) -> mockClient, imgHandler);

    Map<ToolSpecification, ToolExecutor> entries = bridge.toolEntries();
    ToolExecutor executor = entries.values().iterator().next();
    dev.langchain4j.agent.tool.ToolExecutionRequest req =
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name("mcp__cc__take_screenshot")
                    .arguments("{}")
                    .build();
    String result = executor.execute(req, null);

    assertThat(result).startsWith("![screenshot](http://localhost:8090/images/screenshot_");
    assertThat(result).endsWith(".png)");
}
```

- [ ] **Step 2: Run the new test to confirm it fails**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=McpToolBridgeTest#callMcpTool_imageContent_savesFileAndReturnsMarkdownUrl -q 2>&1 | tail -10
```

Expected: compilation error (package-private constructor doesn't yet accept `ImageContentHandler`).

- [ ] **Step 3: Update existing tests to pass `null` for the new parameter**

In `src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java`, every call to the package-private constructor currently looks like:

```java
new McpToolBridge(config.toString(), List.of(), 30, clientFactory)
```

Replace **all** such calls (there are ~13) by appending `, null`:

```java
new McpToolBridge(config.toString(), List.of(), 30, clientFactory, null)
```

Each occurrence is in a different test method. Use find-and-replace in the file. The pattern to replace is:
- Old: `new McpToolBridge(` ... `, (name, cfg) -> ...)` (4-arg)  
- New: same but with `, null)` at the end

The exact lines to change (search for `, 30,` to find them):
- `toolEntries_noConfigFile_returnsEmpty` → replace `(name, config) -> { throw new AssertionError(...)` with `(name, config) -> { throw new AssertionError(...)}, null`
- Repeat for every other test that constructs `McpToolBridge` directly

Full list of methods to update (append `, null` to `McpToolBridge` constructor call in each):
1. `toolEntries_noConfigFile_returnsEmpty`
2. `toolEntries_emptyMcpServers_returnsEmpty`
3. `toolEntries_serverFailsToConnect_skipsServer`
4. `toolEntries_validServer_returnsNamespacedToolSpec`
5. `toolEntries_twoServers_returnsAllToolsNamespaced`
6. `scopedProvider_rootWithConfig_mergesGlobalAndRootTools`
7. `scopedProvider_unknownOrEmptyRoot_returnsGlobalOnly`
8. `rootConfig_expandsRootPlaceholderInArgsEnvAndCommand`
9. `rootConfig_malformedJson_skipsRootGracefully`
10. `rootConfig_missingFile_rootScopedSameAsGlobal`
11. `rootConfig_nullEnvValue_doesNotThrow`
12. `scopedProvider_toolNameCollision_rootWins`
13. `toolNames_withRoot_returnsMergedSortedNames`

- [ ] **Step 4: Modify `McpToolBridge` to accept and use `ImageContentHandler`**

Open `src/main/java/com/example/agentsuite/tools/McpToolBridge.java`.

**4a.** Add field after the existing `clients` field:

```java
private final ImageContentHandler imageContentHandler;
```

**4b.** Update the `@Autowired` public constructor — add `ImageContentHandler imageContentHandler` as a new parameter and pass it through:

```java
@Autowired
public McpToolBridge(
        @Value("${mcp.config.path:.mcp.json}") String configPath,
        @Value("${mcp.call-timeout-seconds:90}") int callTimeoutSeconds,
        @Value("${mcp.root-config.enabled:true}") boolean rootConfigEnabled,
        ImageContentHandler imageContentHandler) {
    this(configPath,
            rootConfigEnabled ? RootDirectories.nonEmpty() : Set.of(),
            callTimeoutSeconds,
            (name, cfg) -> defaultCreateClient(name, cfg, callTimeoutSeconds),
            imageContentHandler);
}
```

**4c.** Update the package-private test constructor — add `ImageContentHandler imageContentHandler` as a last parameter and assign the field:

```java
McpToolBridge(String configPath, Collection<String> rootDirectories, int callTimeoutSeconds,
              BiFunction<String, McpServerConfig, McpSyncClient> clientFactory,
              ImageContentHandler imageContentHandler) {
    this.imageContentHandler = imageContentHandler;
    this.clients = new ArrayList<>();
    this.toolEntries = loadEntries(new File(configPath), null, callTimeoutSeconds, clientFactory);
    // ... rest of constructor unchanged
```

**4d.** Replace `callMcpTool` method body. Find the existing `callMcpTool` method and replace its entire body:

```java
@SuppressWarnings("unchecked")
private String callMcpTool(McpSyncClient client, String serverName,
                            String toolName, String argumentsJson, int timeoutSeconds) {
    try {
        Map<String, Object> args = argumentsJson != null && !argumentsJson.isBlank()
                ? MAPPER.readValue(argumentsJson, Map.class)
                : Map.of();

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, args));

        List<String> parts = new ArrayList<>();

        result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .forEach(parts::add);

        if (imageContentHandler != null) {
            result.content().stream()
                    .filter(c -> c instanceof McpSchema.ImageContent)
                    .map(c -> imageContentHandler.handle((McpSchema.ImageContent) c))
                    .forEach(parts::add);
        }

        String output = String.join("\n", parts);

        if (Boolean.TRUE.equals(result.isError())) {
            return "Error from MCP server '" + serverName + "': " + output;
        }
        return output;
    } catch (Exception e) {
        log.error("MCP tool call failed: server={}, tool={}", serverName, toolName, e);
        return "Error calling MCP tool '" + toolName + "' on server '" + serverName + "': " + e.getMessage();
    }
}
```

- [ ] **Step 5: Run the full McpToolBridgeTest suite**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -Dtest=McpToolBridgeTest -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests pass including the new `callMcpTool_imageContent_savesFileAndReturnsMarkdownUrl`.

- [ ] **Step 6: Run all backend tests**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add src/main/java/com/example/agentsuite/tools/McpToolBridge.java src/test/java/com/example/agentsuite/tools/McpToolBridgeTest.java
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: wire ImageContentHandler into McpToolBridge to handle ImageContent results"
```

---

## Task 5: `ImageLightbox.tsx`

**Files:**
- Create: `frontend/src/ImageLightbox.tsx`

- [ ] **Step 1: Create the component**

Create `frontend/src/ImageLightbox.tsx`:

```tsx
import { useEffect } from 'react';
import ReactDOM from 'react-dom';

interface Props {
  src: string;
  alt: string;
  onClose: () => void;
}

export function ImageLightbox({ src, alt, onClose }: Props) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return ReactDOM.createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
      onClick={onClose}
    >
      {/* Stop click on the image itself from closing */}
      <img
        src={src}
        alt={alt}
        className="max-h-[90vh] max-w-[90vw] object-contain rounded shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      />
      <button
        onClick={onClose}
        aria-label="Close"
        className="absolute top-4 right-4 flex items-center justify-center w-9 h-9 rounded bg-white/10 text-white text-xl hover:bg-white/25 transition-colors"
      >
        ✕
      </button>
      <p className="absolute bottom-4 left-1/2 -translate-x-1/2 text-white/40 text-xs tracking-wide select-none">
        Press Esc to close
      </p>
    </div>,
    document.body
  );
}
```

- [ ] **Step 2: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/ImageLightbox.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add ImageLightbox component with Escape key and click-outside close"
```

---

## Task 6: Wire thumbnail + lightbox into `App.tsx`

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Add the `ImageLightbox` import**

Open `frontend/src/App.tsx`. After the existing imports (around line 11), add:

```tsx
import { ImageLightbox } from './ImageLightbox';
```

- [ ] **Step 2: Add `lightboxSrc` state**

Inside the `App` function, after the `disabledTools` state declaration (around line 181), add:

```tsx
const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
```

- [ ] **Step 3: Add the `img` renderer to `ReactMarkdown`**

In `App.tsx`, find the existing `ReactMarkdown` `components` prop (around line 470). It currently has only an `a` key. Add an `img` key alongside it:

```tsx
components={{
  a: ({ href, children }: { href?: string; children?: React.ReactNode }) => {
    // ... existing audio handler unchanged ...
  },
  img: ({ src, alt }: { src?: string; alt?: string }) => (
    <div style={{ position: 'relative', display: 'inline-block', maxWidth: 380 }}>
      <img
        src={src}
        alt={alt ?? 'screenshot'}
        onClick={() => src && setLightboxSrc(src)}
        style={{
          maxWidth: '100%',
          borderRadius: 8,
          border: '1px solid #d0d8e8',
          display: 'block',
          cursor: 'pointer',
        }}
      />
      <div
        style={{
          position: 'absolute',
          bottom: 8,
          right: 8,
          background: 'rgba(0,0,0,0.55)',
          borderRadius: 5,
          padding: '3px 8px',
          color: '#fff',
          fontSize: '0.72rem',
        }}
      >
        click to expand
      </div>
    </div>
  ),
}}
```

- [ ] **Step 4: Render `ImageLightbox` at the App root**

In `App.tsx`, find the final closing `</div>` of the outermost `return` (just before `export default App`). Add the lightbox just before `</div>`:

```tsx
      {lightboxSrc && (
        <ImageLightbox
          src={lightboxSrc}
          alt="screenshot"
          onClose={() => setLightboxSrc(null)}
        />
      )}
    </div>
```

- [ ] **Step 5: Run the frontend type-check**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite/frontend" && npx tsc --noEmit 2>&1 | tail -20
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add frontend/src/App.tsx
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "feat: add screenshot thumbnail and ImageLightbox to chat UI"
```

---

## Task 7: Final verification

- [ ] **Step 1: Run the full backend test suite**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Build the JAR**

```bash
cd "C:/Users/Lenovo/IdeaProjects/agent-suite" && ./build.cmd 2>&1 | tail -20
```

Expected: build completes without errors, dev servers restart.

- [ ] **Step 3: Update CLAUDE.md**

Open `CLAUDE.md`. In the **Architecture** section, add `ImageContentHandler` and `ImageController` entries after their audio counterparts:

```markdown
- `ImageContentHandler` — `@Component` in the `tools` package. Receives `McpSchema.ImageContent` from `McpToolBridge`, decodes base64, writes `screenshot_<UUID>.<ext>` to `tmp_screenshot_files/`, returns a markdown image link `![screenshot](<base-url>/images/<filename>)`. Supports `image/png`, `image/jpeg`, `image/webp`.
- `ImageController` — `GET /images/{filename}` endpoint serving PNG/JPG/JPEG/WEBP files from `tmp_screenshot_files/`. Path-confined, extension-locked, unauthenticated (same rationale as `/audio/`). Creates `tmp_screenshot_files/` on startup via `@PostConstruct`.
```

Also add `ImageLightbox` to the **Frontend** section:

```markdown
- `ImageLightbox.tsx` — full-screen lightbox overlay rendered via `ReactDOM.createPortal`. Closes on X button click, Escape key, or click outside the image. Used by `App.tsx` when any markdown image thumbnail is clicked.
```

And update the `App.tsx` entry to note the `img` renderer and `lightboxSrc` state.

- [ ] **Step 4: Commit CLAUDE.md**

```bash
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" add CLAUDE.md
git -C "C:/Users/Lenovo/IdeaProjects/agent-suite" commit -m "docs: document ImageContentHandler, ImageController, ImageLightbox in CLAUDE.md"
```
