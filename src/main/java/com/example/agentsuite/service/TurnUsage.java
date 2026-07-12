package com.example.agentsuite.service;

public record TurnUsage(int inputTokens, int outputTokens,
                         Integer cacheReadTokens, Integer cacheWriteTokens) {
}
