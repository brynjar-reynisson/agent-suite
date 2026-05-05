package com.example.agentsuite.service;

public sealed interface ChatEvent {

    record ToolCall(String name, String arguments) implements ChatEvent {
    }

    record Content(String text) implements ChatEvent {
    }

    record Error(String message) implements ChatEvent {
    }

    record Done() implements ChatEvent {
    }
}
