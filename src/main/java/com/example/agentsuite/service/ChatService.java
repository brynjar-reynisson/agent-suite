package com.example.agentsuite.service;

import java.util.List;
import java.util.function.Consumer;

public interface ChatService {
    ChatResponse chat(String systemPrompt, String userMessage, Object... tools);

    void chatStream(String systemPrompt, String userMessage, Consumer<ChatEvent> emitter, Object... tools);

    void chatStreamWithHistory(List<HistoryMessage> history, String userMessage,
                               Consumer<ChatEvent> emitter, Object... tools);
}
