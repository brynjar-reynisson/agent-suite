package com.example.agentsuite.service;

import java.util.List;

public sealed interface ChatEvent {

    record ToolBatch(List<ToolExecution> executions) implements ChatEvent {
        public ToolBatch {
            executions = List.copyOf(executions);
        }
        public record ToolExecution(String name, String arguments, String result) {}
    }

    record Content(String text) implements ChatEvent {}

    record Error(String message) implements ChatEvent {}

    record Done(TurnUsage usage) implements ChatEvent {
        public Done() {
            this(null);
        }
    }
}
