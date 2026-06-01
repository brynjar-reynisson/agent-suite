package com.example.agentsuite.controller;

import java.util.List;

public record ConversationDetailDto(
        String externalId,
        String name,
        String createTime,
        String initialModel,
        String systemPrompt,
        String rootDirectory,
        List<MessageDto> messages
) {
    public record MessageDto(String role, String content, List<ToolCallDto> toolCalls) {}
    public record ToolCallDto(String name, String arguments) {}
}
