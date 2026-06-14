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
        assertThat(result).isEqualTo("[mix.wav](http://localhost:8090/audio/mix.wav)");
    }

    @Test
    void serveAudioFile_validMp3_returnsUrl() throws Exception {
        Path file = audioDir.resolve("track.mp3");
        Files.writeString(file, "fake-mp3");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).isEqualTo("[track.mp3](http://localhost:8090/audio/track.mp3)");
    }

    @Test
    void serveAudioFile_extensionCaseInsensitive_returnsUrl() throws Exception {
        Path file = audioDir.resolve("bounce.WAV");
        Files.writeString(file, "fake-wav");
        String result = tools().serveAudioFile(file.toString());
        assertThat(result).isEqualTo("[bounce.WAV](http://localhost:8090/audio/bounce.WAV)");
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
        String traversal = audioDir.toString() + "/../other.wav";
        String result = tools().serveAudioFile(traversal);
        assertThat(result).contains("Error");
    }
}
