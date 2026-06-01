package com.example.agentsuite.service;

public sealed interface HistoryMessage
        permits HistoryMessage.SystemPrompt, HistoryMessage.User, HistoryMessage.Assistant,
                HistoryMessage.ToolCall, HistoryMessage.ToolResult {

    record SystemPrompt(String content) implements HistoryMessage {}
    record User(String content) implements HistoryMessage {}
    record Assistant(String content) implements HistoryMessage {}
    // callsJson: JSON array [{"name":"...","arguments":"..."}]
    record ToolCall(String callsJson) implements HistoryMessage {}
    // resultsJson: JSON array [{"name":"...","result":"..."}]
    record ToolResult(String resultsJson) implements HistoryMessage {}
}
