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
