package com.example.agentsuite;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "langchain4j.open-ai.chat-model.api-key=test-key",
        "google.api-key=test-key",
        "anthropic.api-key=test-key",
        "brave.api-key=test-key",
        "supabase.jwt-secret=test-secret-padded-to-at-least-32-characters"
})
class AgentSuiteApplicationTests {

    @Test
    void contextLoads() {
    }
}
