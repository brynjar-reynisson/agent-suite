package com.example.agentsuite.service;

public interface ChatService {
    String chat(String systemPrompt, String userMessage, Object... tools);
}
