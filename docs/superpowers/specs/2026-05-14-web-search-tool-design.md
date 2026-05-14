# WebSearch Tool Design

**Date:** 2026-05-14
**Branch:** feature/tool-selection-per-agent

## Summary

Implement the `webSearch` method in `WebTools` using the Brave Search API via plain Java HTTP (`java.net.http.HttpClient`). Register `WebTools` as a `"web-search"` tool group in `AiController.buildToolInstances`.

## Architecture

`WebTools` is a plain POJO (no Spring injection), consistent with `UnixTools` and `MarkDownWriter`. It holds:
- A `String apiKey` read from `BRAVE_SEARCH_API_KEY` at construction time
- A `java.net.http.HttpClient` instance created once per `WebTools` instance

`AiController.buildToolInstances` gains a `"web-search"` case that constructs `new WebTools(System.getenv("BRAVE_SEARCH_API_KEY"))`.

## Data Flow

1. AI calls `webSearch(query)`
2. `WebTools` sends `GET https://api.search.brave.com/res/v1/web/search?q=<encoded-query>&count=5` with header `X-Subscription-Token: <apiKey>`
3. Brave returns JSON: `{ "web": { "results": [ { "title", "url", "description" }, ... ] } }`
4. Method formats and returns top 5 results:
   ```
   1. Title One
      https://example.com
      Snippet text...

   2. Title Two
      ...
   ```

## Error Handling

- API key blank/null → return existing disabled message (no HTTP call made)
- HTTP status != 200 → return `"Search failed: HTTP <status>"`
- JSON parse error or unexpected structure → return `"Search failed: <exception message>"`

## Registration

`AiController.buildToolInstances` switch gains:
```java
case "web-search" -> instances.add(new WebTools(System.getenv("BRAVE_SEARCH_API_KEY")));
```

No `rootDirectory` dependency — `WebTools` is stateless w.r.t. the filesystem.

## Testing

`AiControllerTest.buildToolInstances` gets a new test case verifying that `"web-search"` in the tools param produces a `WebTools` instance. No unit tests for HTTP/parsing logic (consistent with existing tool test coverage).

## Dependencies

No new Maven dependencies required. Jackson (`ObjectMapper`) is already on the classpath via `spring-boot-starter-web`.

## Environment Variables

| Variable | Required | Purpose |
|---|---|---|
| `BRAVE_SEARCH_API_KEY` | Yes (to enable) | Brave Web Search API subscription token |
