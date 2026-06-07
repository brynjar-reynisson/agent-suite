---
title: Authenticated User Propagation to Backend
date: 2026-06-07
status: approved
---

# Authenticated User Propagation to Backend

## Summary

Propagate the Supabase-authenticated user's identity to the Spring Boot backend so that conversations are scoped per user. A `UserResolverFilter` verifies the Supabase JWT on every request, upserts a `SUITE_USER` row on first login, and stores the resolved `userId` as a request attribute. All service calls use this `userId` instead of the hardcoded guest constant. When no token is present or the token is invalid, the guest user (ID 1) is used — identical behaviour to today.

The Spring Boot backend is untouched by Spring Security. Authentication is handled by a single lightweight filter.

## Architecture

```
Frontend                  Backend
--------                  -------
getAccessToken()
  → supabase.getSession()
  → access_token (JWT)

fetch /ai/chat            UserResolverFilter
  Authorization: Bearer   → verify HS256 signature
  <token>                 → extract sub + email
                          → upsert SUITE_USER
                          → setAttribute("currentUserId", userId)
                          ↓
                          AiController
                          → read currentUserId
                          → pass to ChatOrchestrationService
                          → pass to ConversationService
```

No token or invalid token → `currentUserId = 1L` (guest), request proceeds normally.

## Database Changes

### New migration

```sql
ALTER TABLE suite_user ADD COLUMN IF NOT EXISTS email TEXT;
ALTER TABLE suite_user ADD CONSTRAINT suite_user_uuid_unique UNIQUE (uuid);
```

The `uuid` column stores `"Guest"` for the guest user and the Supabase `sub` UUID (e.g. `764efce5-0c44-443b-a445-b5b3c81606cd`) for authenticated users. The `email` column is nullable; the guest row remains NULL.

### H2 test schema

Add the same two changes to `src/test/resources/schema.sql`:
```sql
ALTER TABLE "suite_user" ADD COLUMN IF NOT EXISTS "email" TEXT;
ALTER TABLE "suite_user" ADD CONSTRAINT "suite_user_uuid_unique" UNIQUE ("uuid");
```

## New Backend Files

### `pom.xml`

Add JJWT for HS256 JWT verification:
```xml
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.11.5</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.11.5</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.11.5</version>
  <scope>runtime</scope>
</dependency>
```

### `application.properties`

```properties
supabase.jwt-secret=${SUPABASE_JWT_SECRET}
```

### `restart.sh`

Add alongside existing exports:
```bash
export SUPABASE_JWT_SECRET="super-secret-jwt-token-with-at-least-32-characters-long"
```

(Local Supabase default. Production value comes from Supabase Dashboard → Settings → API → JWT Secret.)

### `SuiteUserService.java`

New Spring `@Service`. Single responsibility: find or create a `SUITE_USER` row by Supabase UUID.

```java
@Service
public class SuiteUserService {

    private final SuiteUserRepository suiteUserRepository;

    public SuiteUserService(SuiteUserRepository suiteUserRepository) {
        this.suiteUserRepository = suiteUserRepository;
    }

    @Transactional
    public long findOrCreate(String supabaseUuid, String email) {
        return suiteUserRepository.findByUuid(supabaseUuid)
                .map(r -> r.getUserId())
                .orElseGet(() -> suiteUserRepository.insert(supabaseUuid, email));
    }
}
```

### `SuiteUserRepository` additions

Add `insert(String uuid, String email)` method:
```java
public long insert(String uuid, String email) {
    return dsl.insertInto(SUITE_USER)
            .set(SUITE_USER.UUID, uuid)
            .set(SUITE_USER.EMAIL, email)
            .returning(SUITE_USER.USER_ID)
            .fetchSingle()
            .getUserId();
}
```

`SuiteUserService.findOrCreate()` performs the SELECT before calling `insert`, so no `ON CONFLICT` guard is needed here. The UNIQUE constraint on `uuid` remains as the database-level safety net against genuine races (which are practically impossible in a single-user local app).

### `UserResolverFilter.java`

`@Component` extending `OncePerRequestFilter`. Runs on every request.

**Behaviour:**
1. Read `Authorization` header. If absent or not `Bearer `, set `currentUserId = 1L` (guest) and continue.
2. Parse and verify HS256 signature using the configured `supabase.jwt-secret`. Check expiry.
3. On success: extract `sub` (Supabase UUID) and `email` claims. Call `suiteUserService.findOrCreate(sub, email)`. Set `request.setAttribute("currentUserId", userId)`.
4. On `JwtException` (invalid signature, expired, malformed): log a warning at WARN level, set `currentUserId = 1L`, continue. Never return 401 — invalid tokens fall back to guest.

```java
@Component
public class UserResolverFilter extends OncePerRequestFilter {

    static final String ATTR_USER_ID = "currentUserId";
    private static final long GUEST_USER_ID = 1L;

    private final SuiteUserService suiteUserService;
    private final String jwtSecret;

    public UserResolverFilter(SuiteUserService suiteUserService,
                               @Value("${supabase.jwt-secret}") String jwtSecret) {
        this.suiteUserService = suiteUserService;
        this.jwtSecret = jwtSecret;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        request.setAttribute(ATTR_USER_ID, resolveUserId(request));
        chain.doFilter(request, response);
    }

    private long resolveUserId(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return GUEST_USER_ID;
        String token = header.substring(7);
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            String sub = claims.getSubject();
            String email = claims.get("email", String.class);
            return suiteUserService.findOrCreate(sub, email);
        } catch (JwtException e) {
            log.warn("Invalid JWT, falling back to guest: {}", e.getMessage());
            return GUEST_USER_ID;
        }
    }
}
```

## Modified Backend Files

### `AiController.java`

- Add `HttpServletRequest` parameter to `chat()`, `getConversations()`, and `getConversationDetail()`.
- Read `long userId = (Long) request.getAttribute(UserResolverFilter.ATTR_USER_ID)` at the top of each handler.
- Pass `userId` to `orchestrationService.chatStream(...)` and `conversationService.getConversationSummaries(userId)` and `conversationService.getConversationDetail(externalId, userId)`.

### `ChatOrchestrationService.java`

- `chatStream()` gains `long userId` parameter.
- Remove `private static final long GUEST_USER_ID = 1L`.
- All calls to `conversationService.addMessage(convId, GUEST_USER_ID, ...)` and `createConversation(GUEST_USER_ID, ...)` use the passed `userId` instead.
- `resolveConversation()` gains `long userId` parameter; threads it through all `addMessage` and `createConversation` calls.
- `persistTurnResult()` gains `long userId` parameter; uses it in `addMessage` calls.

### `ConversationService.java`

- `getConversationSummaries(long userId)` — remove hardcoded `GUEST_USER_ID`; use `findByUserId(userId)`.
- `getConversationDetail(String externalId, long userId)` — after fetching the conversation record, check `conv.getUserId().equals(userId)`. If not, throw `NoSuchElementException("Conversation not found: " + externalId)` (controller already maps this to HTTP 404; no information about ownership is leaked).

## New Frontend Files / Modified Frontend Files

### `package.json` (via npm install)

```bash
npm install @microsoft/fetch-event-source
```

### `auth.ts`

Add exported helper at module level (after `supabase` client creation):

```ts
export async function getAccessToken(): Promise<string | null> {
  const { data: { session } } = await supabase.auth.getSession();
  return session?.access_token ?? null;
}
```

### `api.ts`

All exported functions gain an optional `token?: string | null` parameter. When provided, add `Authorization: Bearer <token>` to request headers.

`chatStream` replaces `EventSource` with `fetchEventSource` (POST, headers supported):

```ts
import { fetchEventSource } from '@microsoft/fetch-event-source';

export const chatStream = async (
  params: ChatRequest,
  callbacks: StreamCallbacks,
  token?: string | null,
): Promise<void> => {
  const controller = new AbortController();
  await fetchEventSource(`${API_BASE_URL}/ai/chat`, {
    method: 'POST',
    signal: controller.signal,
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: new URLSearchParams({
      message: params.message,
      prompt: params.prompt ?? '',
      rootDirectory: params.rootDirectory ?? '',
      model: params.model ?? 'deepseek-v4-pro',
      ...(params.tools ? { tools: params.tools } : {}),
      ...(params.conversationId ? { conversationId: params.conversationId } : {}),
    }),
    onmessage(ev) {
      if (ev.event === 'tool_call') callbacks.onToolCall(JSON.parse(ev.data));
      if (ev.event === 'content') callbacks.onContent(ev.data);
      if (ev.event === 'done') controller.abort();
    },
    onerror(err) {
      if (err instanceof DOMException && err.name === 'AbortError') return; // normal close via 'done'
      throw err;
    },
  });
};

export const getConversations = async (token?: string | null): Promise<ConversationSummary[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/conversations`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error('Failed to fetch conversations');
  return response.json();
};

export const getConversationDetail = async (
  externalId: string,
  token?: string | null,
): Promise<ConversationDetail> => {
  const response = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(externalId)}`,
    { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );
  if (!response.ok) throw new Error('Conversation not found');
  return response.json();
};
```

`getDirectories` and `execTool` do not carry user context — no change needed.

### `App.tsx`

- Import `getAccessToken` from `./auth`.
- Before calling `chatStream`, `getConversations`, or `getConversationDetail`, call `const token = await getAccessToken()` and pass it through.

## Out of Scope

- Role-based access control
- Admin endpoints
- Session invalidation / token refresh on the backend
- Associating tool calls or root directories with users
- Any UI changes (avatar already shows the logged-in user)
