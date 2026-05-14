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

    @Test
    void processBody_emptyHtml_returnsNoContentFound() {
        assertThat(WebTools.processBody("")).isEqualTo("No content found.");
    }

    @Test
    void processBody_longContent_isTruncatedAt20000Chars() {
        String input = "<p>" + "x".repeat(25000) + "</p>";
        String result = WebTools.processBody(input);
        assertThat(result).endsWith("\n[truncated]");
        assertThat(result).hasSize(20000 + "\n[truncated]".length());
    }

    @Test
    void processBody_htmlWithTagsAndScripts_returnsPlainText() {
        String html = "<html><body><script>alert(1)</script><p>Hello world</p></body></html>";
        assertThat(WebTools.processBody(html)).isEqualTo("Hello world");
    }

    @Test
    void processBody_nullHtml_returnsNoContentFound() {
        assertThat(WebTools.processBody(null)).isEqualTo("No content found.");
    }

    @Test
    void webFetch_fileScheme_returnsFetchFailed() {
        WebTools tools = new WebTools("");
        assertThat(tools.webFetch("file:///etc/passwd")).startsWith("Fetch failed:");
    }

    @Test
    void webFetch_loopbackIp_returnsFetchFailed() {
        WebTools tools = new WebTools("");
        assertThat(tools.webFetch("http://127.0.0.1/")).startsWith("Fetch failed:");
    }

    @Test
    void webFetch_privateIp_returnsFetchFailed() {
        WebTools tools = new WebTools("");
        assertThat(tools.webFetch("http://192.168.1.1/")).startsWith("Fetch failed:");
    }

    @Test
    void webFetch_localhostUrl_returnsFetchFailed() {
        WebTools tools = new WebTools("");
        assertThat(tools.webFetch("http://localhost/")).startsWith("Fetch failed:");
    }
}
