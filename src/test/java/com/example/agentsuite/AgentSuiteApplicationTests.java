package com.example.agentsuite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "google.api-key=test-key",
        "anthropic.api-key=test-key"
})
class AgentSuiteApplicationTests {

    @Test
    void contextLoads() {
    }
}
