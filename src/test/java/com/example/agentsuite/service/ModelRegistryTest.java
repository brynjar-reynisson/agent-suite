package com.example.agentsuite.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelRegistryTest {

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        DeepSeekService deepSeekService = mock(DeepSeekService.class);
        org.mockito.Mockito.when(deepSeekService.withModel(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(mock(DeepSeekService.class));
        registry = new ModelRegistry(deepSeekService, "test-anthropic-key", "test-google-key");
    }

    @Test
    void get_deepseekAlias_returnsDeepSeekService() {
        assertThat(registry.get("deepseek-v4-pro")).isInstanceOf(DeepSeekService.class);
        assertThat(registry.get("deepseek-v4-flash")).isInstanceOf(DeepSeekService.class);
    }

    @Test
    void get_anthropicAliases_returnAnthropicService() {
        assertThat(registry.get("sonnet-4.6")).isInstanceOf(AnthropicChatService.class);
        assertThat(registry.get("opus-4.7")).isInstanceOf(AnthropicChatService.class);
        assertThat(registry.get("opus-4.8")).isInstanceOf(AnthropicChatService.class);
        assertThat(registry.get("haiku-4.5")).isInstanceOf(AnthropicChatService.class);
    }

    @Test
    void get_googleAliases_returnGoogleService() {
        assertThat(registry.get("gemini-2.5-flash")).isInstanceOf(GoogleChatService.class);
    }

    @Test
    void get_unknownAlias_returnsNull() {
        assertThat(registry.get("gpt-4o")).isNull();
        assertThat(registry.get("")).isNull();
    }
}
