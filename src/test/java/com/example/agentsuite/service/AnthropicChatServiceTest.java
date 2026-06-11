package com.example.agentsuite.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnthropicChatServiceTest {

    @Mock
    private ChatModel mockModel;

    private AnthropicChatService service;

    @Captor
    private ArgumentCaptor<ChatRequest> captor;

    @BeforeEach
    void setUp() {
        service = new AnthropicChatService(mockModel);
    }

    @Test
    void chat_returnsTextResponse() {
        ChatResponse textResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("Hello!"))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(textResponse);

        com.example.agentsuite.service.ChatResponse result = service.chat("", "Hi");

        assertThat(result.content()).isEqualTo("Hello!");
    }

    @Test
    void chat_withSystemPrompt_includesSystemMessageFirst() {
        ChatResponse textResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("OK"))
                .build();
        when(mockModel.chat(captor.capture())).thenReturn(textResponse);

        service.chat("You are helpful", "Hi");

        ChatRequest req = captor.getValue();
        assertThat(req.messages()).isNotEmpty();
        assertThat(req.messages().get(0)).isInstanceOf(SystemMessage.class);
    }

    @Test
    void chat_emptySystemPrompt_excludesSystemMessage() {
        ChatResponse textResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("OK"))
                .build();
        when(mockModel.chat(captor.capture())).thenReturn(textResponse);

        service.chat("", "Hi");

        ChatRequest req = captor.getValue();
        assertThat(req.messages()).noneMatch(m -> m instanceof SystemMessage);
    }

    @Test
    void chat_withToolCall_executesToolAndLoops() {
        ToolExecutionRequest toolReq = ToolExecutionRequest.builder()
                .id("call_1")
                .name("greet")
                .arguments("{\"name\":\"World\"}")
                .build();

        ChatResponse toolCallResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(List.of(toolReq)))
                .build();
        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from("I said: Hello, World!"))
                .build();

        when(mockModel.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse)
                .thenReturn(finalResponse);

        com.example.agentsuite.service.ChatResponse result = service.chat("", "Greet the world", new EchoTool());

        assertThat(result.content()).isEqualTo("I said: Hello, World!");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("greet");
        assertThat(result.toolCalls().get(0).arguments()).isEqualTo("{\"name\":\"World\"}");
    }

    @Test
    void chat_nullTextResponse_returnsEmptyString() {
        ChatResponse emptyResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .build();
        when(mockModel.chat(any(ChatRequest.class))).thenReturn(emptyResponse);

        com.example.agentsuite.service.ChatResponse result = service.chat("", "Hi");

        assertThat(result.content()).isEqualTo("");
    }

    static class EchoTool {
        @Tool("Greet someone by name")
        public String greet(@P("the name to greet") String name) {
            return "Hello, " + name + "!";
        }
    }
}
