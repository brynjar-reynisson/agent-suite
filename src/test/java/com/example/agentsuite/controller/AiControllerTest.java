package com.example.agentsuite.controller;

import com.example.agentsuite.service.ChatService;
import com.example.agentsuite.service.ModelRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AiController.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ModelRegistry modelRegistry;

    @Test
    void chat_defaultModel_usesDeepSeekAndDefaultMessage() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);
        when(mockService.chat("", "Hello, how are you?")).thenReturn("Hi there!");

        mockMvc.perform(get("/ai/chat"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hi there!"));
    }

    @Test
    void chat_specifiedModel_routesToCorrectService() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("sonnet-4.6")).thenReturn(mockService);
        when(mockService.chat("", "Hello")).thenReturn("Claude here");

        mockMvc.perform(get("/ai/chat")
                        .param("model", "sonnet-4.6")
                        .param("message", "Hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Claude here"));
    }

    @Test
    void chat_unknownModel_returnsError() throws Exception {
        when(modelRegistry.get("gpt-4o")).thenReturn(null);

        mockMvc.perform(get("/ai/chat").param("model", "gpt-4o"))
                .andExpect(status().isOk())
                .andExpect(content().string("Error: Unknown model: gpt-4o"));
    }

    @Test
    void chat_disallowedDirectory_returnsError() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);

        mockMvc.perform(get("/ai/chat").param("rootDirectory", "/etc/passwd"))
                .andExpect(status().isOk())
                .andExpect(content().string("Error: Access to the specified root directory is not allowed."));
    }

    @Test
    void chat_withSystemPrompt_passesPromptToService() throws Exception {
        ChatService mockService = mock(ChatService.class);
        when(modelRegistry.get("deepseek-v4-pro")).thenReturn(mockService);
        when(mockService.chat("Be concise", "Hello")).thenReturn("OK");

        mockMvc.perform(get("/ai/chat")
                        .param("message", "Hello")
                        .param("prompt", "Be concise"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));
    }
}
