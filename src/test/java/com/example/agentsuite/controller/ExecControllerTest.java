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

import static org.mockito.Mockito.lenient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExecController.class)
class ExecControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SuiteUserService suiteUserService;
    @MockBean AuthorizationService authorizationService;

    @TempDir static Path tempDir;

    private static final String SECRET = "test-secret-padded-to-at-least-32-characters";
    private static final String ISSUER  = "http://127.0.0.1:54321/auth/v1";

    @BeforeAll
    static void setUp() {
        RootDirectories.ALLOWED.add(tempDir.toString());
    }

    @AfterAll
    static void tearDown() {
        RootDirectories.ALLOWED.remove(tempDir.toString());
    }

    @BeforeEach
    void setUpAuth() {
        lenient().when(suiteUserService.findOrCreate("admin-user", "admin@example.com")).thenReturn(42L);
        lenient().when(authorizationService.isAdmin(42L)).thenReturn(true);
        lenient().when(suiteUserService.findOrCreate("normal-user", "user@example.com")).thenReturn(99L);
        lenient().when(authorizationService.isAdmin(99L)).thenReturn(false);
    }

    private String makeAdminToken() {
        return Jwts.builder()
                .setSubject("admin-user")
                .claim("email", "admin@example.com")
                .setIssuer(ISSUER)
                .setAudience("authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }

    private String makeNonAdminToken() {
        return Jwts.builder()
                .setSubject("normal-user")
                .claim("email", "user@example.com")
                .setIssuer(ISSUER)
                .setAudience("authenticated")
                .setExpiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void admin_validRoot_startsAsyncStream() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeAdminToken()))
                .andExpect(request().asyncStarted());
    }

    @Test
    void nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeNonAdminToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void emptyRootDirectory_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", "")
                .header("Authorization", "Bearer " + makeAdminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRootDirectory_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("command", "echo hello")
                .param("rootDirectory", "/not/in/allowed")
                .header("Authorization", "Bearer " + makeAdminToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingCommand_returns400() throws Exception {
        mockMvc.perform(post("/ai/exec")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("rootDirectory", tempDir.toString())
                .header("Authorization", "Bearer " + makeAdminToken()))
                .andExpect(status().isBadRequest());
    }
}
