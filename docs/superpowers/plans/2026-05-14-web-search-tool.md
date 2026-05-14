# WebSearch Tool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `WebTools.webSearch()` using the Brave Search API and register it as the `"web-search"` tool group in `AiController`.

**Architecture:** `WebTools` becomes a proper POJO with a `String apiKey` constructor parameter. It uses `java.net.http.HttpClient` (Java 21 stdlib) to call `https://api.search.brave.com/res/v1/web/search` and `ObjectMapper` (Jackson, already on classpath) to parse the JSON response into a formatted result string. `AiController.buildToolInstances` gets a `"web-search"` switch case that constructs `new WebTools(System.getenv("BRAVE_SEARCH_API_KEY"))`.

**Tech Stack:** Java 21 `java.net.http.HttpClient`, Jackson `ObjectMapper` (via `spring-boot-starter-web`), LangChain4j `@Tool`/`@P` annotations, JUnit 5 + AssertJ (existing test setup)

---

### Task 1: Add failing test for `"web-search"` in `buildToolInstances`

**Files:**
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Add the import and test method to `AiControllerTest`**

Add this import at the top of `AiControllerTest.java` (with the other tool imports):
```java
import com.example.agentsuite.tools.WebTools;
```

Add this test method inside the class (after the `buildToolInstances_mdWriterAndUnknown_onlyMarkDownWriterAdded` test):
```java
@Test
void buildToolInstances_webSearchGroup_returnsWebToolsInstance() {
    Object[] result = AiController.buildToolInstances("web-search", "");
    assertThat(result).hasSize(1);
    assertThat(result[0]).isInstanceOf(WebTools.class);
}
```

Note: `web-search` does not require `rootDirectory`, so an empty string is passed — the tool should still be instantiated.

- [ ] **Step 2: Run the test to confirm it fails**

```
./mvnw test -Dtest=AiControllerTest#buildToolInstances_webSearchGroup_returnsWebToolsInstance
```

Expected: FAIL — `buildToolInstances` has no `"web-search"` case so it returns an empty array, and `WebTools` still has no constructor that takes a `String`.

---

### Task 2: Implement `WebTools` with constructor and `webSearch` body

**Files:**
- Modify: `src/main/java/com/example/agentsuite/tools/WebTools.java`

- [ ] **Step 3: Replace the entire contents of `WebTools.java` with the implementation**

```java
package com.example.agentsuite.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class WebTools {

    private static final String BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final int RESULT_COUNT = 5;

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebTools(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Tool("Search the internet/www for current information on the query")
    public String webSearch(@P("The query string for the search") String query) {
        if (apiKey.isBlank()) {
            return "Web search is currently disabled. Please enable it by providing the necessary API key and search engine ID.";
        }
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BRAVE_API_URL + "?q=" + encodedQuery + "&count=" + RESULT_COUNT))
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

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
}
```

---

### Task 3: Register `"web-search"` in `buildToolInstances`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`

- [ ] **Step 4: Add import for `WebTools` and add the `"web-search"` switch case**

Add import at the top of `AiController.java` with the other tool imports:
```java
import com.example.agentsuite.tools.WebTools;
```

In `buildToolInstances`, add a new case after the `"md-writer"` case:
```java
case "web-search" -> instances.add(new WebTools(System.getenv("BRAVE_SEARCH_API_KEY")));
```

The full `switch` block should look like:
```java
switch (g) {
    case "unix" -> {
        if (!rootDirectory.isEmpty()) instances.add(new UnixTools(rootDirectory));
    }
    case "md-writer" -> {
        if (!rootDirectory.isEmpty()) instances.add(new MarkDownWriter(rootDirectory));
    }
    case "web-search" -> instances.add(new WebTools(System.getenv("BRAVE_SEARCH_API_KEY")));
}
```

- [ ] **Step 5: Run all tests to confirm everything passes**

```
./mvnw test
```

Expected: All tests pass, including `buildToolInstances_webSearchGroup_returnsWebToolsInstance`.

- [ ] **Step 6: Commit**

```
git add src/main/java/com/example/agentsuite/tools/WebTools.java
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: implement webSearch tool using Brave Search API"
```
