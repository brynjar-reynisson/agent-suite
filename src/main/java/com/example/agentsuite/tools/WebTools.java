package com.example.agentsuite.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);
    private static final String BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int RESULT_COUNT = 5;
    private static final int MAX_FETCH_CHARS = 20_000;
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String apiKey;

    public WebTools(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    @Tool("Search the internet/www for current information on the query")
    public String webSearch(@P("The query string for the search") String query) {
        if (apiKey.isBlank()) {
            return "Web search is currently disabled. Please set the BRAVE_SEARCH_API_KEY environment variable.";
        }
        try {
            log.info("webSearch {}", query);
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BRAVE_API_URL + "?q=" + encodedQuery + "&count=" + RESULT_COUNT))
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("webSearch status {}", response.statusCode());

            if (response.statusCode() != 200) {
                return "Search failed: HTTP " + response.statusCode();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("web").path("results");

            if (!results.isArray() || results.isEmpty()) {
                return "No results found.";
            }

            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonNode result : results) {
                sb.append(i++).append(". ").append(result.path("title").asText("(no title)")).append("\n");
                sb.append("   ").append(result.path("url").asText("")).append("\n");
                String description = result.path("description").asText("");
                if (!description.isBlank()) {
                    sb.append("   ").append(description).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString().trim();
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool("Fetch and return the plain-text content of a specific URL, e.g. to read a documentation page, article, or file linked in search results")
    public String webFetch(@P("The URL to fetch") String url) {
        try {
            validateUrl(url);
            log.info("webFetch {}", url.replace("\n", "\\n").replace("\r", "\\r"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            log.info("webFetch status {}", response.statusCode());

            if (response.statusCode() != 200) {
                response.body().close();
                return "Fetch failed: HTTP " + response.statusCode();
            }

            byte[] bytes = response.body().readNBytes(MAX_FETCH_CHARS * 4);
            response.body().close();
            return processBody(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "Fetch failed: " + e.getMessage();
        }
    }

    private static void validateUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("URL scheme must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host");
        }
        try {
            // Validate every resolved address (not just the first) to defeat multi-record DNS tricks.
            // Caveat: this does not fully prevent DNS rebinding — HttpClient re-resolves on connect — but
            // the check runs immediately before the request and follows no redirects.
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isDisallowedAddress(addr)) {
                    throw new IllegalArgumentException("URL resolves to a private or internal address");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve host: " + host);
        }
    }

    static boolean isDisallowedAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()      // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isLinkLocalAddress()  // 169.254.0.0/16 (incl. cloud metadata), fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16, fec0::/10
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] b = addr.getAddress();
        if (addr instanceof Inet4Address) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            if (first == 0) return true;                          // 0.0.0.0/8 "this network"
            if (first == 100 && second >= 64 && second <= 127) return true; // 100.64.0.0/10 CGNAT
        } else if (addr instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) return true;               // fc00::/7 unique local addresses
        }
        return false;
    }

    static String processBody(String html) {
        if (html == null) {
            return "No content found.";
        }
        String text = Jsoup.parse(html).body().text();
        if (text.isBlank()) {
            return "No content found.";
        }
        if (text.length() > MAX_FETCH_CHARS) {
            return text.substring(0, MAX_FETCH_CHARS) + "\n[truncated]";
        }
        return text;
    }
}
