package com.example.agentsuite.service;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class AnthropicChatService extends AbstractLangChain4jChatService {

    public AnthropicChatService(String apiKey, String modelName) {
        this(buildModel(apiKey, modelName));
    }

    private static ChatLanguageModel buildModel(String apiKey, String modelName) {
        var builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(8192);
        // Extended-thinking models (opus-4+) do not accept a temperature parameter
        if (!modelName.contains("opus-4")) {
            builder.temperature(0.1);
        }
        return builder.build();
    }

    AnthropicChatService(ChatLanguageModel model) {
        super(model);
    }
}
