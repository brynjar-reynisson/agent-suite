package com.example.agentsuite.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
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
        mockMvc.perform(get("/audio/script.ogg"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAudio_dotDotTraversal_returns404() {
        // Directly call controller to bypass MockMvc URL normalization
        AudioController controller = new AudioController(audioDir.toString());
        ResponseEntity<?> response = controller.serveAudio("../secret.wav", new HttpHeaders());
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getAudio_responseBodyMatchesFile() throws Exception {
        Files.writeString(audioDir.resolve("content.wav"), "audio-bytes");

        mockMvc.perform(get("/audio/content.wav"))
                .andExpect(status().isOk())
                .andExpect(content().string("audio-bytes"));
    }
}
