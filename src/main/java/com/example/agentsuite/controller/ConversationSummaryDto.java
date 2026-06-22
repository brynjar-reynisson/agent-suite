package com.example.agentsuite.controller;

public record ConversationSummaryDto(
        String externalId,
        String name,
        String customName,
        String createTime
) {}
