package com.example.agentsuite.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ModelRegistry {

    private final Map<String, ChatService> registry;

    public ModelRegistry(DeepSeekService deepSeekService,
                         @Value("${anthropic.api-key}") String anthropicApiKey,
                         @Value("${google.api-key}") String googleApiKey) {
        registry = new HashMap<>();
        registry.put("deepseek-v4-pro", deepSeekService);
        if (!anthropicApiKey.isBlank()) {
            registry.put("sonnet-4.6", new AnthropicChatService(anthropicApiKey, "claude-sonnet-4-6"));
            registry.put("opus-4.7", new AnthropicChatService(anthropicApiKey, "claude-opus-4-7"));
            registry.put("haiku-4.5", new AnthropicChatService(anthropicApiKey, "claude-haiku-4-5-20251001"));
        }
        if (!googleApiKey.isBlank()) {
            registry.put("gemini-2.5-pro", new GoogleChatService(googleApiKey, "gemini-2.5-pro"));
            registry.put("gemini-2.5-flash", new GoogleChatService(googleApiKey, "gemini-2.5-flash"));
        }
    }

    public ChatService get(String model) {
        return registry.get(model);
    }
}
