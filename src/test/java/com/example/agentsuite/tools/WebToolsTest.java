package com.example.agentsuite.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolsTest {

    @Test
    void webSearch_blankApiKey_returnsDisabledMessage() {
        WebTools tools = new WebTools("");
        String result = tools.webSearch("test query");
        assertThat(result).contains("BRAVE_SEARCH_API_KEY");
    }

    @Test
    void webSearch_nullApiKey_returnsDisabledMessage() {
        WebTools tools = new WebTools(null);
        String result = tools.webSearch("test query");
        assertThat(result).contains("BRAVE_SEARCH_API_KEY");
    }
}
