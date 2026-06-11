package com.example.agentsuite.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class DeepSeekChatService extends AbstractLangChain4jChatService {

    private final String apiKey;
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;

    @Autowired
    public DeepSeekChatService(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.temperature}") double temperature,
            @Value("${langchain4j.open-ai.chat-model.max-tokens}") int maxTokens) {
        super(buildModel(apiKey, baseUrl, modelName, maxTokens));
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    private DeepSeekChatService(ChatModel model, String apiKey, String baseUrl,
                                 double temperature, int maxTokens) {
        super(model);
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public DeepSeekChatService withModel(String newModelName) {
        return new DeepSeekChatService(
            buildModel(apiKey, baseUrl, newModelName, maxTokens),
            apiKey, baseUrl, temperature, maxTokens
        );
    }

    private static ChatModel buildModel(String apiKey, String baseUrl,
                                         String modelName, int maxTokens) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }
}
