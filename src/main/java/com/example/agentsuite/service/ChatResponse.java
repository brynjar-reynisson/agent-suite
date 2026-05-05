package com.example.agentsuite.service;

import java.util.List;

public record ChatResponse(List<ToolCall> toolCalls, String content) {

    public record ToolCall(String name, String arguments) {
    }

    public static ChatResponse of(String content) {
        return new ChatResponse(List.of(), content);
    }
}
