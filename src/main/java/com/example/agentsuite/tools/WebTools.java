package com.example.agentsuite.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
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
    private static final HttpClient httpClient = HttpClient.newHttpClient();
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

    @Tool("Fetch the content of a web page and return its plain text")
    public String webFetch(@P("The URL to fetch") String url) {
        try {
            log.info("webFetch {}", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("webFetch status {}", response.statusCode());

            if (response.statusCode() != 200) {
                return "Fetch failed: HTTP " + response.statusCode();
            }

            return processBody(response.body());
        } catch (Exception e) {
            return "Fetch failed: " + e.getMessage();
        }
    }

    static String processBody(String html) {
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
