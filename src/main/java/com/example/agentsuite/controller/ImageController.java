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
