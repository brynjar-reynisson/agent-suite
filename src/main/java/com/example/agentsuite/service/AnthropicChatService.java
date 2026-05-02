package com.example.agentsuite.service;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class AnthropicChatService extends AbstractLangChain4jChatService {

    public AnthropicChatService(String apiKey, String modelName) {
        this(AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(8192)
                .build());
    }

    AnthropicChatService(ChatLanguageModel model) {
        super(model);
    }
}
