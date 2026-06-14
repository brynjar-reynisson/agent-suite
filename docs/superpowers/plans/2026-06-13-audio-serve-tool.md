# Audio Serve Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an admin-only `serve_audio_file` tool that validates a rendered audio file in `./tmp_audio_files/`, then returns a fully-qualified public URL the user can play in the browser on any device.

**Architecture:** A new `AudioTools` plain-Java class holds the `@Tool` method and validates paths; a new `AudioController` serves files from `tmp_audio_files/` with content-type and confinement checks; `AiController` wires `AudioTools` as the `"audio"` tool group (admin-only via `AuthorizationService`); the frontend's `ReactMarkdown` detects `.wav`/`.mp3` links and renders inline `<audio>` players.

**Tech Stack:** Spring Boot 3.5, Java 21, LangChain4j `@Tool`, MockMvc, AssertJ, React 19, ReactMarkdown + remark-gfm.

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/example/agentsuite/tools/AudioTools.java` | `@Tool` method: path confinement + extension check + URL construction |
| Create | `src/main/java/com/example/agentsuite/controller/AudioController.java` | `GET /audio/{filename}`: confinement + extension check + file streaming |
| Create | `src/test/java/com/example/agentsuite/tools/AudioToolsTest.java` | Unit tests for `AudioTools` |
| Create | `src/test/java/com/example/agentsuite/controller/AudioControllerTest.java` | MockMvc tests for `AudioController` |
| Modify | `src/main/resources/application.properties` | Add `agent.base-url` and `agent.audio.dir` |
| Modify | `src/main/resources/application-prod.properties` | Add `agent.base-url=https://agent.breynisson.org` |
| Modify | `src/main/java/com/example/agentsuite/service/AuthorizationService.java` | Add `"audio"` to admin tool groups |
| Modify | `src/main/java/com/example/agentsuite/controller/AiController.java` | Add `@Value` fields; add `"audio"` case to `buildToolInstances` |
| Modify | `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | Update `buildToolInstances` call sites; add audio group test |
| Modify | `.gitignore` | Add `tmp_audio_files/` |
| Modify | `frontend/src/App.tsx` | Custom `a` renderer in `<ReactMarkdown>` |

---

## Task 1: Branch, config, .gitignore

**Files:**
- Modify: `.gitignore`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-prod.properties`

- [ ] **Step 1: Verify you are on `feature/audio_play`**

```bash
git branch --show-current
```
Expected output: `feature/audio_play`

- [ ] **Step 2: Add properties to `application.properties`**

Append to `src/main/resources/application.properties`:
```properties
# Audio serve tool
agent.base-url=http://localhost:8090
agent.audio.dir=./tmp_audio_files
```

- [ ] **Step 3: Add prod base URL to `application-prod.properties`**

Append to `src/main/resources/application-prod.properties`:
```properties
agent.base-url=https://agent.breynisson.org
```

- [ ] **Step 4: Add `tmp_audio_files/` to `.gitignore`**

Append to `.gitignore`:
```
# Temporary rendered audio files
tmp_audio_files/
```

- [ ] **Step 5: Create the empty tmp dir so REAPER has somewhere to render**

```bash
mkdir tmp_audio_files
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.properties \
        src/main/resources/application-prod.properties \
        .gitignore
git commit -m "feat: add audio serve config and gitignore tmp_audio_files"
```

---

## Task 2: `AudioTools` (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/tools/AudioToolsTest.java`
- Create: `src/main/java/com/example/agentsuite/tools/AudioTools.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/tools/AudioToolsTest.java`:

```java
package com.example.agentsuite.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AudioToolsTest {

    @TempDir
    Path audioDir;

    private AudioTools tools() {
        return new AudioTools("http://localhost:8090", audioDir);
    }

    @Test
    void serveAudioFile_validWav_returnsUrl() throws Exception {
        Path file = audioDir.resolve("mix.wav");
        Files.writeString(file, "fake-wav");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).isEqualTo("http://localhost:8090/audio/mix.wav");
    }

    @Test
    void serveAudioFile_validMp3_returnsUrl() throws Exception {
        Path file = audioDir.resolve("track.mp3");
        Files.writeString(file, "fake-mp3");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).isEqualTo("http://localhost:8090/audio/track.mp3");
    }

    @Test
    void serveAudioFile_extensionCaseInsensitive_returnsUrl() throws Exception {
        Path file = audioDir.resolve("bounce.WAV");
        Files.writeString(file, "fake-wav");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).isEqualTo("http://localhost:8090/audio/bounce.WAV");
    }

    @Test
    void serveAudioFile_wrongExtension_returnsError() throws Exception {
        Path file = audioDir.resolve("clip.ogg");
        Files.writeString(file, "fake-ogg");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).contains("Error").contains("wav").contains("mp3");
    }

    @Test
    void serveAudioFile_fileNotFound_returnsError() {
        Path missing = audioDir.resolve("missing.wav");
        String result = tools().serveAudioFile(missing.toString());
        assertThat(result).contains("Error").contains("not found");
    }

    @Test
    void serveAudioFile_pathTraversal_returnsError(@TempDir Path other) throws Exception {
        Path secret = other.resolve("secret.wav");
        Files.writeString(secret, "secret");
        String result = tools().serveAudioFile(secret.toString());
        assertThat(result).contains("Error").doesNotContain("secret");
    }

    @Test
    void serveAudioFile_dotDotTraversal_returnsError() {
        // attempt to escape via relative segment inside the audioDir path
        String traversal = audioDir.toString() + "/../other.wav";
        String result = tools().serveAudioFile(traversal);
        assertThat(result).contains("Error");
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -Dtest=AudioToolsTest -pl . 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — `AudioTools` does not exist yet.

- [ ] **Step 3: Implement `AudioTools`**

Create `src/main/java/com/example/agentsuite/tools/AudioTools.java`:

```java
package com.example.agentsuite.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class AudioTools {

    private static final Logger log = LoggerFactory.getLogger(AudioTools.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".wav", ".mp3");

    private final String baseUrl;
    private final Path audioDir;

    public AudioTools(String baseUrl, Path audioDir) {
        this.baseUrl = baseUrl;
        this.audioDir = audioDir.toAbsolutePath().normalize();
    }

    @Tool("Serve a rendered audio file (WAV or MP3) from the tmp_audio_files directory and return " +
          "a playable URL. The file must already exist at the given absolute path inside tmp_audio_files/. " +
          "Always render audio to the tmp_audio_files/ directory before calling this tool.")
    public String serveAudioFile(
            @P("Absolute path to the rendered audio file inside tmp_audio_files/") String absolutePath) {
        log.info("serveAudioFile {}", absolutePath);

        Path resolved = Path.of(absolutePath).toAbsolutePath().normalize();

        if (!resolved.startsWith(audioDir)) {
            return "Error: access to paths outside the audio directory is not allowed.";
        }

        String filename = resolved.getFileName().toString();
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.')).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return "Error: only .wav and .mp3 files are supported.";
        }

        if (!Files.isRegularFile(resolved)) {
            return "Error: file not found.";
        }

        return baseUrl + "/audio/" + filename;
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=AudioToolsTest -pl . 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`, 7 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/tools/AudioTools.java \
        src/test/java/com/example/agentsuite/tools/AudioToolsTest.java
git commit -m "feat: add AudioTools with path confinement and extension validation"
```

---

## Task 3: `AudioController` (TDD)

**Files:**
- Create: `src/test/java/com/example/agentsuite/controller/AudioControllerTest.java`
- Create: `src/main/java/com/example/agentsuite/controller/AudioController.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/example/agentsuite/controller/AudioControllerTest.java`.
Uses `standaloneSetup` (no Spring context) so `@TempDir` can be used directly as the audio dir.

```java
package com.example.agentsuite.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioControllerTest {

    @TempDir
    Path audioDir;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        AudioController controller = new AudioController(audioDir.toString());
        controller.init();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getAudio_validWav_returns200WithCorrectContentType() throws Exception {
        Files.writeString(audioDir.resolve("test.wav"), "RIFF fake wav data");

        mockMvc.perform(get("/audio/test.wav"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/wav"));
    }

    @Test
    void getAudio_validMp3_returns200WithCorrectContentType() throws Exception {
        Files.writeString(audioDir.resolve("track.mp3"), "fake mp3 data");

        mockMvc.perform(get("/audio/track.mp3"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"));
    }

    @Test
    void getAudio_missingFile_returns404() throws Exception {
        mockMvc.perform(get("/audio/missing.wav"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAudio_badExtension_returns400() throws Exception {
        Files.writeString(audioDir.resolve("script.ogg"), "ogg data");

        mockMvc.perform(get("/audio/script.ogg"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAudio_responseBodyMatchesFile() throws Exception {
        Files.writeString(audioDir.resolve("content.wav"), "audio-bytes");

        mockMvc.perform(get("/audio/content.wav"))
                .andExpect(status().isOk())
                .andExpect(content().string("audio-bytes"));
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./mvnw test -Dtest=AudioControllerTest -pl . 2>&1 | tail -10
```
Expected: `BUILD FAILURE` — `AudioController` does not exist yet.

- [ ] **Step 3: Implement `AudioController`**

Create `src/main/java/com/example/agentsuite/controller/AudioController.java`:

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
public class AudioController {

    private static final Logger log = LoggerFactory.getLogger(AudioController.class);
    private static final Map<String, MediaType> CONTENT_TYPES = Map.of(
            ".wav", MediaType.parseMediaType("audio/wav"),
            ".mp3", MediaType.parseMediaType("audio/mpeg")
    );

    private final Path audioDir;

    public AudioController(@Value("${agent.audio.dir}") String audioDirStr) {
        this.audioDir = Path.of(audioDirStr).toAbsolutePath().normalize();
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(audioDir);
        log.info("Audio serve directory: {}", audioDir);
    }

    @GetMapping("/audio/{filename}")
    public ResponseEntity<byte[]> serveAudio(@PathVariable String filename) {
        // Reject filenames that attempt sub-directory traversal
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

        Path file = audioDir.resolve(filename).normalize();
        if (!file.startsWith(audioDir)) {
            return ResponseEntity.notFound().build();
        }

        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] bytes = Files.readAllBytes(file);
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        } catch (IOException e) {
            log.error("Failed to read audio file {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./mvnw test -Dtest=AudioControllerTest -pl . 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`, 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AudioController.java \
        src/test/java/com/example/agentsuite/controller/AudioControllerTest.java
git commit -m "feat: add AudioController serving WAV/MP3 from tmp_audio_files"
```

---

## Task 4: Auth + `AiController` wiring

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/AuthorizationService.java`
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

The `buildToolInstances` static method currently has signature:
```java
static Object[] buildToolInstances(String tools, String rootDirectory, String braveApiKey, McpToolBridge mcpToolBridge)
```
We extend it with two new trailing parameters: `String baseUrl` and `Path audioDir`. Existing callers (tests) pass `null, null` for these and the `"audio"` case guards against null, matching the pattern of the `"mcp"` group.

- [ ] **Step 1: Update `AuthorizationService`**

In `src/main/java/com/example/agentsuite/service/AuthorizationService.java`, change line 28:

```java
// Before:
return isAdmin ? List.of("web", "md-writer", "mcp") : List.of("web");

// After:
return isAdmin ? List.of("web", "md-writer", "mcp", "audio") : List.of("web");
```

- [ ] **Step 2: Add `@Value` fields to `AiController`**

In `src/main/java/com/example/agentsuite/controller/AiController.java`:

Add two new fields after `mcpToolBridge`:
```java
private final String baseUrl;
private final Path audioDir;
```

Update the constructor to inject them (add after `McpToolBridge mcpToolBridge`):
```java
@Value("${agent.base-url}") String baseUrl,
@Value("${agent.audio.dir}") String audioDir
```

And assign in constructor body:
```java
this.baseUrl = baseUrl;
this.audioDir = Path.of(audioDir).toAbsolutePath().normalize();
```

Add `import java.nio.file.Path;` to the imports.

- [ ] **Step 3: Update `buildToolInstances` signature and add `"audio"` case**

Change the static method signature from:
```java
static Object[] buildToolInstances(String tools, String rootDirectory, String braveApiKey,
                                    McpToolBridge mcpToolBridge) {
```
to:
```java
static Object[] buildToolInstances(String tools, String rootDirectory, String braveApiKey,
                                    McpToolBridge mcpToolBridge, String baseUrl, Path audioDir) {
```

Add the `"audio"` case inside the `switch` block, after the `"mcp"` case:
```java
case "audio" -> {
    if (baseUrl != null && audioDir != null) instances.add(new AudioTools(baseUrl, audioDir));
}
```

Add `import com.example.agentsuite.tools.AudioTools;` to the imports.

- [ ] **Step 4: Update the call site in `chat()`**

Find the call to `buildToolInstances` inside the `chat` method and add the two new arguments:
```java
// Before:
Object[] toolArray = buildToolInstances(String.join(",", authorized), rootDirectory, braveApiKey, mcpToolBridge);

// After:
Object[] toolArray = buildToolInstances(String.join(",", authorized), rootDirectory, braveApiKey, mcpToolBridge, baseUrl, audioDir);
```

- [ ] **Step 5: Update `AiControllerTest` — fix broken call sites**

Every existing `AiController.buildToolInstances(...)` call in the test file passes 4 args and must be updated to pass 6. Open `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` and replace every occurrence of:
```java
AiController.buildToolInstances(
```
with a version that appends `, null, null` as the last two arguments. There are 10 such calls; example:
```java
// Before:
Object[] result = AiController.buildToolInstances("", tempDir.toString(), "", null);

// After:
Object[] result = AiController.buildToolInstances("", tempDir.toString(), "", null, null, null);
```
Apply this to all 10 calls.

- [ ] **Step 6: Add a new test for the `"audio"` group in `AiControllerTest`**

In `AiControllerTest`, add after the last `buildToolInstances_*` test:

```java
@Test
void buildToolInstances_audioGroup_withParams_returnsAudioTools(@TempDir Path audioDir) throws Exception {
    java.nio.file.Files.writeString(audioDir.resolve("x.wav"), "");
    Object[] result = AiController.buildToolInstances(
            "audio", "", "", null, "http://localhost:8090", audioDir);
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(com.example.agentsuite.tools.AudioTools.class);
}

@Test
void buildToolInstances_audioGroup_nullParams_returnsEmpty() {
    Object[] result = AiController.buildToolInstances("audio", "", "", null, null, null);
    assertThat(result).isEmpty();
}
```

Also add an `AuthorizationService` test. Open `src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java` and add:

```java
@Test
void grantedToolGroups_admin_includesAudio() {
    when(userRoleRepository.isAdmin(1L)).thenReturn(true);
    assertThat(authorizationService.grantedToolGroups(true)).contains("audio");
}

@Test
void grantedToolGroups_nonAdmin_doesNotIncludeAudio() {
    assertThat(authorizationService.grantedToolGroups(false)).doesNotContain("audio");
}
```

- [ ] **Step 7: Run all tests**

```bash
./mvnw test 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`, all tests passing.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AuthorizationService.java \
        src/main/java/com/example/agentsuite/controller/AiController.java \
        src/test/java/com/example/agentsuite/controller/AiControllerTest.java \
        src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java
git commit -m "feat: wire audio tool group into AiController and AuthorizationService"
```

---

## Task 5: Frontend — inline audio player

**Files:**
- Modify: `frontend/src/App.tsx`

The existing `<ReactMarkdown>` at line ~428 of `App.tsx` is:
```tsx
<ReactMarkdown remarkPlugins={[remarkGfm]}>
  {msg.content}
</ReactMarkdown>
```

- [ ] **Step 1: Add the custom `a` component renderer**

Replace that block with:
```tsx
<ReactMarkdown
  remarkPlugins={[remarkGfm]}
  components={{
    a: ({ href, children }) => {
      if (href && /\.(wav|mp3)$/i.test(href)) {
        return (
          <span className="block mt-1">
            <audio controls src={href} className="w-full" />
            <a
              href={href}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-gray-400 hover:underline"
            >
              {children}
            </a>
          </span>
        );
      }
      return (
        <a href={href} target="_blank" rel="noopener noreferrer">
          {children}
        </a>
      );
    },
  }}
>
  {msg.content}
</ReactMarkdown>
```

- [ ] **Step 2: Start the dev server and verify**

```bash
cd frontend && npm run dev
```

Open `http://localhost:5177` in a browser. In a chat with the REAPER root selected, ask the AI:
> "Reply with a markdown link to a .wav file, like this: [test audio](http://localhost:8090/audio/test.wav)"

Confirm the response renders an `<audio>` element with controls plus a small text link beneath it. Confirm a normal markdown link (e.g. `[Google](https://google.com)`) still renders as a plain `<a>` tag.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat: render audio links as inline players in ReactMarkdown"
```

---

## Task 6: Full build + final commit

- [ ] **Step 1: Run the full test suite**

```bash
./mvnw test 2>&1 | tail -15
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Update CLAUDE.md**

Add `AudioTools` and `AudioController` entries to the Architecture section of `CLAUDE.md`:

In the Key layers bullet list, add after the `MarkDownWriter` entry:
```
- `AudioTools` — exposes `serveAudioFile` as an AI-callable tool. Accepts an absolute path inside `tmp_audio_files/`, validates extension (`.wav`/`.mp3`) and path confinement, returns the fully-qualified public URL (`{agent.base-url}/audio/{filename}`). Registered as the `"audio"` tool group (admin-only).
- `AudioController` — `GET /audio/{filename}` endpoint that serves WAV/MP3 files from `tmp_audio_files/`. Path confinement and extension check on every request; streams file bytes with correct `Content-Type`.
```

In the API section, add:
```
GET /audio/{filename}
  Serves a WAV or MP3 file from tmp_audio_files/. No auth required (path-confined + extension-locked).
  Returns 200 audio/wav or audio/mpeg, 404 if not found, 400 for unsupported extension.
```

- [ ] **Step 3: Final commit**

```bash
git add CLAUDE.md
git commit -m "docs: document AudioTools and AudioController in CLAUDE.md"
```
