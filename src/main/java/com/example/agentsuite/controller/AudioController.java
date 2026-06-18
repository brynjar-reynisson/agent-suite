package com.example.agentsuite.controller;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    public void init() throws IOException {
        Files.createDirectories(audioDir);
        log.info("Audio serve directory: {}", audioDir);
    }

    // Intentionally unauthenticated: access is path-confined to tmp_audio_files/ and
    // extension-locked to .wav/.mp3. Per-user auth can be added later if needed.
    @GetMapping("/audio/{filename}")
    public ResponseEntity<ResourceRegion> serveAudio(
            @PathVariable String filename,
            @RequestHeader HttpHeaders headers) {

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
        if (!file.startsWith(audioDir) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file.toFile());
        List<HttpRange> ranges = headers.getRange();

        try {
            long contentLength = resource.contentLength();
            ResourceRegion region;
            HttpStatus status;
            if (ranges.isEmpty()) {
                region = new ResourceRegion(resource, 0, contentLength);
                status = HttpStatus.OK;
            } else {
                region = ranges.get(0).toResourceRegion(resource);
                status = HttpStatus.PARTIAL_CONTENT;
            }
            return ResponseEntity.status(status)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(region);
        } catch (IOException e) {
            log.error("Failed to serve audio file {}", filename, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
