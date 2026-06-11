package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractLangChain4jChatServiceTest {

    static class TestChatService extends AbstractLangChain4jChatService {
        TestChatService(ChatModel model) { super(model); }
    }

    @Test
    void chatStream_dynamicToolProvider_registeredWithoutException() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("test_tool")
                .description("A test tool")
                .build();
        ToolExecutor executor = (req, memId) -> "tool result";
        DynamicToolProvider dynamicProvider = () -> Map.of(spec, executor);

        ChatModel mockModel = mock(ChatModel.class);
        AiMessage aiResponse = AiMessage.from("done");
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new ArrayList<>();
        service.chatStream("", "hello", events::add, dynamicProvider);

        assertThat(events).anyMatch(e -> e instanceof ChatEvent.Done);
    }
}
