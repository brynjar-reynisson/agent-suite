package com.example.agentsuite.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventTest {

    @Test
    void done_noArgConstructor_hasNullUsage() {
        ChatEvent.Done done = new ChatEvent.Done();
        assertThat(done.usage()).isNull();
    }

    @Test
    void done_withUsage_exposesUsage() {
        TurnUsage usage = new TurnUsage(120, 45, 30, 5);
        ChatEvent.Done done = new ChatEvent.Done(usage);
        assertThat(done.usage()).isEqualTo(usage);
        assertThat(done.usage().inputTokens()).isEqualTo(120);
        assertThat(done.usage().outputTokens()).isEqualTo(45);
        assertThat(done.usage().cacheReadTokens()).isEqualTo(30);
        assertThat(done.usage().cacheWriteTokens()).isEqualTo(5);
    }

    @Test
    void done_withNullCacheFields_allowsNulls() {
        TurnUsage usage = new TurnUsage(10, 5, null, null);
        assertThat(usage.cacheReadTokens()).isNull();
        assertThat(usage.cacheWriteTokens()).isNull();
    }
}
