package com.example.agentsuite.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;

public class GoogleChatService extends AbstractLangChain4jChatService {

    public GoogleChatService(String apiKey, String modelName) {
        this(GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxOutputTokens(8192)
                .build());
    }

    GoogleChatService(ChatLanguageModel model) {
        super(model);
    }
}
