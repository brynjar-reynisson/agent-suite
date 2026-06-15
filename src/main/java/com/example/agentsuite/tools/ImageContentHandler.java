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
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to save screenshot {}", filename, e);
            return "Error: failed to save screenshot: " + e.getMessage();
        }

        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "![screenshot](" + baseUrl + "/images/" + encoded + ")";
    }
}
