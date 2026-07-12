package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
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

    @Test
    void toUserFacingError_sequentialLimitException_returnsFriendlyMessage() {
        Exception e = new IllegalStateException(
                "Sequential tool executions limit (" +
                ChatService.MAX_SEQUENTIAL_TOOL_INVOCATIONS +
                ") reached");
        String result = AbstractLangChain4jChatService.toUserFacingError(e);
        assertThat(result).contains("Tool call limit reached");
        assertThat(result).contains(String.valueOf(ChatService.MAX_SEQUENTIAL_TOOL_INVOCATIONS));
        assertThat(result).contains("Send another message to continue");
    }

    @Test
    void toUserFacingError_otherException_returnsOriginalMessage() {
        Exception e = new RuntimeException("Connection refused");
        assertThat(AbstractLangChain4jChatService.toUserFacingError(e)).isEqualTo("Connection refused");
    }

    @Test
    void toUserFacingError_nullMessage_returnsEmptyString() {
        Exception e = new RuntimeException((String) null);
        assertThat(AbstractLangChain4jChatService.toUserFacingError(e)).isEqualTo("");
    }

    @Test
    void chatStream_capturesTokenUsage_onDoneEvent() {
        ChatModel mockModel = mock(ChatModel.class);
        AiMessage aiResponse = AiMessage.from("hello there");
        TokenUsage usage = new TokenUsage(100, 40, 140);
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).tokenUsage(usage).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new ArrayList<>();
        service.chatStream("", "hello", events::add);

        ChatEvent.Done done = (ChatEvent.Done) events.stream()
                .filter(e -> e instanceof ChatEvent.Done)
                .findFirst()
                .orElseThrow();
        assertThat(done.usage()).isNotNull();
        assertThat(done.usage().inputTokens()).isEqualTo(100);
        assertThat(done.usage().outputTokens()).isEqualTo(40);
        assertThat(done.usage().cacheReadTokens()).isNull();
        assertThat(done.usage().cacheWriteTokens()).isNull();
    }

    @Test
    void chatStream_anthropicCacheTokens_populateTurnUsage() {
        ChatModel mockModel = mock(ChatModel.class);
        AiMessage aiResponse = AiMessage.from("hello there");
        AnthropicTokenUsage usage = AnthropicTokenUsage.builder()
                .inputTokenCount(100)
                .outputTokenCount(40)
                .cacheReadInputTokens(30)
                .cacheCreationInputTokens(5)
                .build();
        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(aiResponse).tokenUsage(usage).build());

        TestChatService service = new TestChatService(mockModel);
        List<ChatEvent> events = new ArrayList<>();
        service.chatStream("", "hello", events::add);

        ChatEvent.Done done = (ChatEvent.Done) events.stream()
                .filter(e -> e instanceof ChatEvent.Done)
                .findFirst()
                .orElseThrow();
        assertThat(done.usage().cacheReadTokens()).isEqualTo(30);
        assertThat(done.usage().cacheWriteTokens()).isEqualTo(5);
    }

    @Test
    void toTurnUsage_nullInput_returnsNull() {
        assertThat(AbstractLangChain4jChatService.toTurnUsage(null)).isNull();
    }

    @Test
    void toTurnUsage_nullTokenCounts_defaultToZero() {
        TokenUsage usage = new TokenUsage(null, null, null);
        var turnUsage = AbstractLangChain4jChatService.toTurnUsage(usage);
        assertThat(turnUsage.inputTokens()).isEqualTo(0);
        assertThat(turnUsage.outputTokens()).isEqualTo(0);
    }
}
