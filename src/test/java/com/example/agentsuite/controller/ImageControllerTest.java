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
