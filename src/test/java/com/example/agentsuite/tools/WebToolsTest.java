package com.example.agentsuite.tools;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolsTest {

    private static boolean disallowed(String ip) throws Exception {
        // Literal IPs are parsed without a DNS lookup, so these checks are offline and deterministic.
        return WebTools.isDisallowedAddress(InetAddress.getByName(ip));
    }

    @Test
    void isDisallowedAddress_loopbackAndPrivateAndLinkLocal_blocked() throws Exception {
        assertThat(disallowed("127.0.0.1")).isTrue();
        assertThat(disallowed("10.0.0.1")).isTrue();
        assertThat(disallowed("192.168.1.1")).isTrue();
        assertThat(disallowed("172.16.0.1")).isTrue();
        assertThat(disallowed("169.254.169.254")).isTrue(); // cloud metadata endpoint
        assertThat(disallowed("::1")).isTrue();
    }

    @Test
    void isDisallowedAddress_cgnatRange_blocked() throws Exception {
        // 100.64.0.0/10 (carrier-grade NAT) is not loopback/link-local/site-local.
        assertThat(disallowed("100.64.0.1")).isTrue();
        assertThat(disallowed("100.127.255.255")).isTrue();
    }

    @Test
    void isDisallowedAddress_anyLocalAndZeroNetwork_blocked() throws Exception {
        assertThat(disallowed("0.0.0.0")).isTrue();
        assertThat(disallowed("0.1.2.3")).isTrue();
    }

    @Test
    void isDisallowedAddress_ipv6UniqueLocal_blocked() throws Exception {
        // fc00::/7 unique local addresses are the IPv6 equivalent of private ranges.
        assertThat(disallowed("fc00::1")).isTrue();
        assertThat(disallowed("fd12:3456::1")).isTrue();
    }

    @Test
    void isDisallowedAddress_publicAddresses_allowed() throws Exception {
        assertThat(disallowed("8.8.8.8")).isFalse();
        assertThat(disallowed("1.1.1.1")).isFalse();
        assertThat(disallowed("101.0.0.1")).isFalse(); // just outside CGNAT
    }

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
