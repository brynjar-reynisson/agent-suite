package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnthropicChatServiceTest {

    @Mock
    private ChatLanguageModel mockModel;

    private AnthropicChatService service;

    @BeforeEach
    void setUp() {
        service = new AnthropicChatService(mockModel);
    }

    @Test
    void chat_returnsTextResponse() {
        when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Hello!")));

        String result = service.chat("", "Hi");

        assertThat(result).isEqualTo("Hello!");
    }

    @Test
    void chat_withSystemPrompt_includesSystemMessageFirst() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("You are helpful", "Hi");

        List<ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_emptySystemPrompt_excludesSystemMessage() {
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("", "Hi");

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_withToolCall_executesToolAndLoops() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call_1")
                .name("greet")
                .arguments("{\"name\":\"World\"}")
                .build();

        when(mockModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(List.of(req))))
                .thenReturn(Response.from(AiMessage.from("I said: Hello, World!")));

        String result = service.chat("", "Greet the world", new EchoTool());

        assertThat(result).isEqualTo("I said: Hello, World!");
        verify(mockModel, times(2)).generate(anyList(), anyList());
    }

    static class EchoTool {
        @Tool("Greet someone by name")
        public String greet(@P("the name to greet") String name) {
            return "Hello, " + name + "!";
        }
    }
}
