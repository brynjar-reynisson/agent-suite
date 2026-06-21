package com.example.agentsuite.controller;

import com.example.agentsuite.config.RootDirectories;
import com.example.agentsuite.jooq.service.SuiteUserService;
import com.example.agentsuite.service.AuthorizationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
class FileControllerTest {

    @TempDir
    static Path tempDir;
    static String root;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    SuiteUserService suiteUserService;

    @MockBean
    AuthorizationService authorizationService;

    @BeforeAll
    static void addTempToAllowed() {
        root = tempDir.toString().replace("\\", "/");
        RootDirectories.ALLOWED.add(root);
    }

    @AfterAll
    static void removeTempFromAllowed() {
        RootDirectories.ALLOWED.remove(root);
    }

    @BeforeEach
    void setUpAuth() {
        lenient().when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
        lenient().when(authorizationService.isAdmin(42L)).thenReturn(true);
    }

    private static final String ADMIN_BEARER = "Bearer " + makeAdminJwt();

    private static String makeAdminJwt() {
        return Jwts.builder()
                .setSubject("admin-sub")
                .claim("email", "admin@test.com")
                .setIssuer("http://127.0.0.1:54321/auth/v1")
                .setAudience("authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(
                        Keys.hmacShaKeyFor(
                                "test-secret-padded-to-at-least-32-characters"
                                        .getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void readFile_adminCanReadExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "hello world");

        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "hello.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isOk())
                .andExpect(content().string("hello world"));
    }

    @Test
    void readFile_nonAdminGetsForbidden() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .param("path", "hello.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isForbidden());
    }

    @Test
    void readFile_fileNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "nonexistent.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isNotFound());
    }

    @Test
    void readFile_dotDotTraversalRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "../outside.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_unixAbsolutePathRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "/etc/passwd")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_windowsAbsolutePathRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "C:\\Windows\\System32\\evil.txt")
                        .param("rootDirectory", root))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readFile_invalidRootDirectoryRejected() throws Exception {
        mockMvc.perform(get("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "file.txt")
                        .param("rootDirectory", "/not/allowed"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeFile_adminCanWriteFile() throws Exception {
        Path file = tempDir.resolve("output.txt");

        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "output.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("new content"))
                .andExpect(status().isNoContent());

        assertThat(Files.readString(file)).isEqualTo("new content");
    }

    @Test
    void writeFile_nonAdminGetsForbidden() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .param("path", "output.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeFile_dotDotTraversalRejected() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "../outside.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void writeFile_windowsAbsolutePathRejected() throws Exception {
        mockMvc.perform(put("/ai/files")
                        .header("Authorization", ADMIN_BEARER)
                        .param("path", "C:\\Windows\\System32\\evil.txt")
                        .param("rootDirectory", root)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("content"))
                .andExpect(status().isBadRequest());
    }
}
