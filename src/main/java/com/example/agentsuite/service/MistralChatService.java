package com.example.agentsuite.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;

public class MistralChatService extends AbstractLangChain4jChatService {

    public MistralChatService(String apiKey, String modelName) {
        this(buildModel(apiKey, modelName));
    }

    private static ChatModel buildModel(String apiKey, String modelName) {
        return MistralAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(8192)
                .build();
    }

    MistralChatService(ChatModel model) {
        super(model);
    }
}
