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
        if (!this.audioDir.toFile().exists() || !this.audioDir.toFile().isDirectory()) {
            throw new IllegalArgumentException(
                    "Audio directory does not exist or is not a directory: " + this.audioDir);
        }
    }

    @Tool("Serve a rendered audio file (WAV or MP3) from the tmp_audio_files directory. " +
          "The file must already exist at the given absolute path inside tmp_audio_files/. " +
          "Always render audio to the tmp_audio_files/ directory before calling this tool. " +
          "Returns a markdown link — include it verbatim in your response to show an inline audio player.")
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

        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return "[" + filename + "](" + baseUrl + "/audio/" + encoded + ")";
    }
}
