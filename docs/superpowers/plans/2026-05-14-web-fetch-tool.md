# WebFetch Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `webFetch(String url)` tool method to `WebTools` that fetches a URL and returns its content as plain text (HTML stripped via Jsoup, truncated to 20,000 characters).

**Architecture:** `webFetch` is added directly to the existing `WebTools` class alongside `webSearch`. HTML-to-text processing is extracted into a package-private static helper `processBody(String html)` so it can be unit-tested without HTTP. The existing static `HttpClient` is reused. A `jsoup` dependency is added to `pom.xml`.

**Tech Stack:** Java 21 `java.net.http.HttpClient` (existing), Jsoup 1.18.3 (new), JUnit 5 + AssertJ (existing test setup)

---

### Task 1: Write failing tests for `processBody`

**Files:**
- Modify: `src/test/java/com/example/agentsuite/tools/WebToolsTest.java`

- [ ] **Step 1: Add three test methods to `WebToolsTest`**

Open `src/test/java/com/example/agentsuite/tools/WebToolsTest.java` and add these three tests after the existing two tests:

```java
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
```

- [ ] **Step 2: Run the new tests to confirm they fail**

```
./mvnw test "-Dtest=WebToolsTest#processBody_emptyHtml_returnsNoContentFound+processBody_longContent_isTruncatedAt20000Chars+processBody_htmlWithTagsAndScripts_returnsPlainText"
```

Expected: FAIL — `WebTools.processBody` does not exist yet. You should see a compile error mentioning `cannot find symbol`.

---

### Task 2: Add jsoup dependency and implement `processBody` + `webFetch`

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/example/agentsuite/tools/WebTools.java`

- [ ] **Step 3: Add jsoup to `pom.xml`**

In `pom.xml`, add this dependency inside `<dependencies>`, after the `unix4j-command` dependency:

```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.3</version>
</dependency>
```

- [ ] **Step 4: Add `processBody` and `webFetch` to `WebTools.java`**

Add `import org.jsoup.Jsoup;` to the imports.

Add `processBody` as a package-private static method and `webFetch` as a public tool method. The complete updated `WebTools.java` should look like this:

```java
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
```

- [ ] **Step 5: Run the three new tests to confirm they pass**

```
./mvnw test "-Dtest=WebToolsTest#processBody_emptyHtml_returnsNoContentFound+processBody_longContent_isTruncatedAt20000Chars+processBody_htmlWithTagsAndScripts_returnsPlainText"
```

Expected: all 3 PASS.

- [ ] **Step 6: Run the full test suite**

```
./mvnw test
```

Expected: all 71 tests pass (68 existing + 3 new).

- [ ] **Step 7: Commit**

```
git add pom.xml
git add src/main/java/com/example/agentsuite/tools/WebTools.java
git add src/test/java/com/example/agentsuite/tools/WebToolsTest.java
git commit -m "feat: add webFetch tool to WebTools using Jsoup"
```
