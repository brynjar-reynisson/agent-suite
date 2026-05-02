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
import org.mockito.Captor;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleChatServiceTest {

    @Mock
    private ChatLanguageModel mockModel;

    @Captor
    private ArgumentCaptor<List<ChatMessage>> captor;

    private GoogleChatService service;

    @BeforeEach
    void setUp() {
        service = new GoogleChatService(mockModel);
    }

    @Test
    void chat_returnsTextResponse() {
        when(mockModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Gemini here!")));

        String result = service.chat("", "Hi");

        assertThat(result).isEqualTo("Gemini here!");
    }

    @Test
    void chat_withSystemPrompt_includesSystemMessageFirst() {
        when(mockModel.generate(captor.capture())).thenReturn(Response.from(AiMessage.from("OK")));

        service.chat("Be concise", "Hi");

        List<ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
    }

    @Test
    void chat_emptySystemPrompt_excludesSystemMessage() {
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
