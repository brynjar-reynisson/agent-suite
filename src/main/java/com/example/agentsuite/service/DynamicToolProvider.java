package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.Map;

public interface DynamicToolProvider {
    Map<ToolSpecification, ToolExecutor> toolEntries();
}
