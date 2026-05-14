# WebFetch Tool Design

**Date:** 2026-05-14
**Branch:** feature/tool-selection-per-agent

## Summary

Add a `webFetch(@P String url)` tool method to the existing `WebTools` class. It fetches a URL via HTTP, strips HTML to plain text using Jsoup, truncates to 20,000 characters, and returns the result. No API key required.

## Architecture

`webFetch` is a new `@Tool` method on `WebTools` — no new files. It reuses the existing static `HttpClient` and follows the same patterns as `webSearch` (10-second timeout, SLF4J logging, exception catch-all). A `jsoup` dependency is added to `pom.xml`.

## Data Flow

1. AI calls `webFetch(url)`
2. `WebTools` sends `GET <url>` with a 10-second timeout; `HttpClient` follows redirects by default
3. Non-200 response → return `"Fetch failed: HTTP <status>"`
4. Parse body: `Jsoup.parse(body).body().text()` — strips tags, scripts, styles; collapses whitespace
5. Blank result → return `"No content found."`
6. Truncate to 20,000 characters; append `"\n[truncated]"` if truncation occurred
7. Any exception → return `"Fetch failed: <message>"`

## Logging

Log the URL at `INFO` before the request and the response status at `INFO` after, matching `webSearch` convention.

## Dependencies

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>1.18.3</version>
</dependency>
```

## Testing

Extract a package-private static helper `static String processBody(String html)` from `webFetch` that handles the Jsoup parsing, blank check, and truncation. This makes the content-processing logic unit-testable without HTTP.

Add to `WebToolsTest`:
- `processBody_emptyHtml_returnsNoContentFound` — `WebTools.processBody("")` returns `"No content found."`
- `processBody_longContent_isTruncatedAt20000Chars` — `WebTools.processBody("x".repeat(25000))` returns a string of length 20,000 + `"\n[truncated]"`.length() and ends with `"[truncated]"`
- `processBody_htmlWithTagsAndScripts_returnsPlainText` — `WebTools.processBody("<html><body><script>alert(1)</script><p>Hello world</p></body></html>")` returns `"Hello world"`

HTTP paths are not unit-tested (consistent with `webSearch` coverage).
