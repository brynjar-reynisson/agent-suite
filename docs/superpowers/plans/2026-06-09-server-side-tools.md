# Server-Side Tool Authorization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move tool-set computation from frontend-driven to backend-authoritative — the backend computes which tools a user is entitled to from their role and request context; the frontend's `tools` param is an opt-out hint only, not a grant.

**Architecture:** New `AuthorizationService.grantedToolGroups(isAdmin)` returns the role-based tool groups a user may use (`web` always; `md-writer` if admin). `AiController.chat()` builds the authoritative set from this plus `unix` (if `rootDirectory` set), then intersects with the frontend's `tools` param if provided. `/ai/config/user` is expanded to include `grantedToolGroups` so the frontend can drive the `ToolStrip` display from the real server-side state. `handleSend` in the frontend sends `availableTools - disabledTools` instead of a PROMPT_BANK-derived string.

**Tech Stack:** Spring Boot 3.5 / Java 21 / React 19 / TypeScript / Tailwind CSS 4

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/example/agentsuite/service/AuthorizationService.java` | Add `grantedToolGroups(boolean isAdmin): List<String>` |
| `src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java` | Add two tests for `grantedToolGroups` |
| `src/main/java/com/example/agentsuite/controller/AiController.java` | Replace `filterToolGroups` with server-side computation; add `grantedToolGroups` to `/ai/config/user` response; add `LinkedHashSet` import; remove unused `Collectors` import |
| `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` | Update `setUpAuth` to stub `grantedToolGroups`; remove three obsolete filter tests; add five new behavioural tests; add `/ai/config/user` test |
| `frontend/src/api.ts` | Update `getUserConfig` return type to include `grantedToolGroups: string[]` |
| `frontend/src/App.tsx` | Add `grantedToolGroups` state; derive `availableTools` from it; wire `disabledTools` into `handleSend`; reset `grantedToolGroups` on sign-out |

---

### Task 1: `AuthorizationService.grantedToolGroups`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/service/AuthorizationService.java`
- Modify: `src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java`

- [ ] **Step 1: Write the two failing tests**

In `AuthorizationServiceTest.java`, add after the existing `canUseToolGroup_nullGroup_throwsNullPointerException` test:

```java
@Test
void grantedToolGroups_nonAdmin_returnsWebOnly() {
    assertThat(authorizationService.grantedToolGroups(false)).containsExactly("web");
}

@Test
void grantedToolGroups_admin_returnsWebAndMdWriter() {
    assertThat(authorizationService.grantedToolGroups(true)).containsExactly("web", "md-writer");
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```
.\mvnw.cmd test -Dtest=AuthorizationServiceTest
```

Expected: BUILD FAILURE — `grantedToolGroups` method does not exist yet.

- [ ] **Step 3: Implement `grantedToolGroups` in `AuthorizationService.java`**

Add this method after `canUseToolGroup`:

```java
public List<String> grantedToolGroups(boolean isAdmin) {
    List<String> groups = new ArrayList<>();
    groups.add("web");
    if (isAdmin) groups.add("md-writer");
    return groups;
}
```

Also add the missing import at the top of the file (it likely already has `List` via `java.util.List` but double-check):

```java
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 4: Run the tests to verify they pass**

```
.\mvnw.cmd test -Dtest=AuthorizationServiceTest
```

Expected: `Tests run: 9, Failures: 0, Errors: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/service/AuthorizationService.java
git add src/test/java/com/example/agentsuite/service/AuthorizationServiceTest.java
git commit -m "feat: add AuthorizationService.grantedToolGroups for server-side tool set"
```

---

### Task 2: Server-side tool computation in `AiController.chat()`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Update `setUpAuth` and remove three obsolete tests**

In `AiControllerTest.java`:

Replace the `@BeforeEach setUpAuth` method:

```java
@BeforeEach
void setUpAuth() {
    when(authorizationService.grantedToolGroups(false)).thenReturn(List.of("web"));
    when(authorizationService.grantedToolGroups(true)).thenReturn(List.of("web", "md-writer"));
}
```

Delete these three test methods entirely (they test the old `filterToolGroups` path which is being removed):
- `chat_toolGroupFilteredByAuth_filteredGroupNotPassedToOrchestration`
- `chat_allToolGroupsDenied_orchestrationReceivesEmptyToolArray`
- `chat_adminUser_canUseToolGroupReceivesIsAdminTrue`

- [ ] **Step 2: Add five new behavioural tests**

Add these tests to `AiControllerTest.java` (the `makeAdminJwt` helper already exists):

```java
@Test
void chat_guestUser_noToolsParam_onlyWebToolPassedToOrchestration() throws Exception {
    doAnswer(inv -> {
        @SuppressWarnings("unchecked")
        Consumer<ChatEvent> consumer = inv.getArgument(6);
        consumer.accept(new ChatEvent.Done());
        return null;
    }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(),
            any(Consumer.class), any());

    MvcResult mvcResult = mockMvc.perform(get("/ai/chat"))
            .andExpect(request().asyncStarted()).andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    verify(orchestrationService).chatStream(
            isNull(), anyLong(), any(), any(), any(), any(), any(Consumer.class),
            argThat(arr -> arr instanceof Object[] t && t.length == 1 && t[0] instanceof WebTools));
}

@Test
void chat_guestUserWithRootDirectory_webAndUnixPassedToOrchestration() throws Exception {
    doAnswer(inv -> {
        @SuppressWarnings("unchecked")
        Consumer<ChatEvent> consumer = inv.getArgument(6);
        consumer.accept(new ChatEvent.Done());
        return null;
    }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(),
            any(Consumer.class), any());

    MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                    .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
            .andExpect(request().asyncStarted()).andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    verify(orchestrationService).chatStream(
            isNull(), anyLong(), any(), any(), any(), any(), any(Consumer.class),
            argThat(arr -> arr instanceof Object[] t && t.length == 2
                    && t[0] instanceof WebTools && t[1] instanceof UnixTools));
}

@Test
void chat_guestUserRequestsMdWriter_mdWriterStrippedServerSide() throws Exception {
    doAnswer(inv -> {
        @SuppressWarnings("unchecked")
        Consumer<ChatEvent> consumer = inv.getArgument(6);
        consumer.accept(new ChatEvent.Done());
        return null;
    }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(),
            any(Consumer.class), any());

    MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                    .param("tools", "web,md-writer"))
            .andExpect(request().asyncStarted()).andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    verify(orchestrationService).chatStream(
            isNull(), anyLong(), any(), any(), any(), any(), any(Consumer.class),
            argThat(arr -> arr instanceof Object[] t && t.length == 1 && t[0] instanceof WebTools));
}

@Test
void chat_adminUserWithRootDirectory_allThreeToolsPassedToOrchestration() throws Exception {
    when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
    when(authorizationService.isAdmin(42L)).thenReturn(true);

    doAnswer(inv -> {
        @SuppressWarnings("unchecked")
        Consumer<ChatEvent> consumer = inv.getArgument(6);
        consumer.accept(new ChatEvent.Done());
        return null;
    }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(),
            any(Consumer.class), any());

    MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                    .header("Authorization", "Bearer " + makeAdminJwt("admin-sub", "admin@test.com"))
                    .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
            .andExpect(request().asyncStarted()).andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    verify(orchestrationService).chatStream(
            isNull(), anyLong(), any(), any(), any(), any(), any(Consumer.class),
            argThat(arr -> arr instanceof Object[] t && t.length == 3
                    && t[0] instanceof WebTools
                    && t[1] instanceof MarkDownWriter
                    && t[2] instanceof UnixTools));
}

@Test
void chat_adminOptOutMdWriter_onlyWebAndUnixPassedToOrchestration() throws Exception {
    when(suiteUserService.findOrCreate("admin-sub", "admin@test.com")).thenReturn(42L);
    when(authorizationService.isAdmin(42L)).thenReturn(true);

    doAnswer(inv -> {
        @SuppressWarnings("unchecked")
        Consumer<ChatEvent> consumer = inv.getArgument(6);
        consumer.accept(new ChatEvent.Done());
        return null;
    }).when(orchestrationService).chatStream(isNull(), anyLong(), any(), any(), any(), any(),
            any(Consumer.class), any());

    MvcResult mvcResult = mockMvc.perform(get("/ai/chat")
                    .header("Authorization", "Bearer " + makeAdminJwt("admin-sub", "admin@test.com"))
                    .param("tools", "web,unix")
                    .param("rootDirectory", "C:/Users/Lenovo/IdeaProjects/agent-suite"))
            .andExpect(request().asyncStarted()).andReturn();

    mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

    verify(orchestrationService).chatStream(
            isNull(), anyLong(), any(), any(), any(), any(), any(Consumer.class),
            argThat(arr -> arr instanceof Object[] t && t.length == 2
                    && t[0] instanceof WebTools && t[1] instanceof UnixTools));
}
```

- [ ] **Step 3: Run the tests to verify the new ones fail**

```
.\mvnw.cmd test -Dtest=AiControllerTest
```

Expected: BUILD FAILURE on the 5 new tests. Existing tests may also fail because `setUpAuth` no longer stubs `canUseToolGroup`.

- [ ] **Step 4: Rewrite tool computation in `AiController.java`**

Add this import near the other `java.util` imports:
```java
import java.util.LinkedHashSet;
```

Remove the `import java.util.stream.Collectors;` line (it was only used by `filterToolGroups`).

In the `chat()` method, replace these three lines:
```java
boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
String filteredTools = filterToolGroups(tools, isAdmin);
Object[] toolArray = buildToolInstances(filteredTools, rootDirectory, braveApiKey);
```

With:
```java
boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
Set<String> authorized = new LinkedHashSet<>(authorizationService.grantedToolGroups(isAdmin));
if (!rootDirectory.isEmpty()) authorized.add("unix");
if (!tools.isBlank()) {
    Set<String> requested = Arrays.stream(tools.split(","))
            .map(String::trim)
            .filter(g -> !g.isEmpty())
            .collect(java.util.stream.Collectors.toSet());
    authorized.retainAll(requested);
}
Object[] toolArray = buildToolInstances(String.join(",", authorized), rootDirectory, braveApiKey);
```

(Using the fully-qualified `java.util.stream.Collectors.toSet()` inline avoids the import change being a separate concern. Or simply keep the `Collectors` import — either is fine.)

Then delete the entire `filterToolGroups` private method (lines ~218–225 in the current file).

- [ ] **Step 5: Run the full test suite to verify all tests pass**

```
.\mvnw.cmd test -Dtest=AiControllerTest
```

Expected: `Tests run: N, Failures: 0, Errors: 0` — BUILD SUCCESS. Count should be previous count minus 3 (removed) plus 5 (added) = net +2.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: compute tool set server-side from role and context, frontend tools param is opt-out only"
```

---

### Task 3: Expand `/ai/config/user` to include `grantedToolGroups`

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Write the failing test**

In `AiControllerTest.java`, add:

```java
@Test
void userConfig_guestUser_returnsIsAdminFalseWithWebTool() throws Exception {
    mockMvc.perform(get("/ai/config/user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isAdmin").value(false))
            .andExpect(jsonPath("$.grantedToolGroups").isArray())
            .andExpect(jsonPath("$.grantedToolGroups[0]").value("web"))
            .andExpect(jsonPath("$.grantedToolGroups.length()").value(1));
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
.\mvnw.cmd test -Dtest=AiControllerTest#userConfig_guestUser_returnsIsAdminFalseWithWebTool
```

Expected: FAIL — response JSON has `isAdmin` but no `grantedToolGroups` field yet.

- [ ] **Step 3: Update `getUserConfig` in `AiController.java`**

Replace the existing `getUserConfig` method:

```java
@GetMapping("/ai/config/user")
public Map<String, Object> getUserConfig(HttpServletRequest request) {
    boolean isAdmin = Boolean.TRUE.equals(request.getAttribute(UserResolverFilter.ATTR_IS_ADMIN));
    return Map.of(
            "isAdmin", isAdmin,
            "grantedToolGroups", authorizationService.grantedToolGroups(isAdmin)
    );
}
```

- [ ] **Step 4: Run the full test suite**

```
.\mvnw.cmd test
```

Expected: `Tests run: 137+, Failures: 0` — BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: include grantedToolGroups in /ai/config/user response"
```

---

### Task 4: Frontend — consume `grantedToolGroups` from API

**Files:**
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Update `getUserConfig` return type in `api.ts`**

Find the `getUserConfig` function and update its return type:

```ts
export const getUserConfig = async (token?: string | null): Promise<{ isAdmin: boolean; grantedToolGroups: string[] }> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/user`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) throw new Error('Failed to fetch user config');
  return response.json();
};
```

- [ ] **Step 2: Add `grantedToolGroups` state to `App.tsx`**

Find the `const [isAdmin, setIsAdmin] = useState(false);` line and add the new state directly after it:

```tsx
const [isAdmin, setIsAdmin] = useState(false);
const [grantedToolGroups, setGrantedToolGroups] = useState<string[]>([]);
```

- [ ] **Step 3: Set `grantedToolGroups` from the API response, and reset conversation state on sign-out**

Find the `useEffect` that handles sign-out and user config fetching. It currently looks like:

```tsx
useEffect(() => {
  if (!user) {
    conversationId.current = crypto.randomUUID();
    lastSentModel.current = null;
    lastSentPrompt.current = null;
    setMessages([]);
    setModel('deepseek-v4-pro');
    setPrompt('');
    setRootDirectory('');
    setIsAdmin(false);
    return;
  }
  const fetchUserConfig = async () => {
    try {
      const token = await getAccessToken();
      const config = await getUserConfig(token);
      setIsAdmin(config.isAdmin);
    } catch {
      setIsAdmin(false);
    }
  };
  fetchUserConfig();
}, [user]);
```

Replace with:

```tsx
useEffect(() => {
  if (!user) {
    conversationId.current = crypto.randomUUID();
    lastSentModel.current = null;
    lastSentPrompt.current = null;
    setMessages([]);
    setModel('deepseek-v4-pro');
    setPrompt('');
    setRootDirectory('');
  }
  // Always fetch config — guests also receive grantedToolGroups (web is always available)
  const fetchUserConfig = async () => {
    try {
      const token = await getAccessToken();
      const config = await getUserConfig(token);
      setIsAdmin(config.isAdmin);
      setGrantedToolGroups(config.grantedToolGroups);
    } catch {
      setIsAdmin(false);
      setGrantedToolGroups([]);
    }
  };
  fetchUserConfig();
}, [user]);
```

- [ ] **Step 4: Update the `availableTools` memo to use `grantedToolGroups`**

Find the `availableTools` useMemo. It currently reads from `PROMPT_BANK`:

```tsx
const availableTools = useMemo(() => {
  const matched = PROMPT_BANK.find(p => p.name === prompt);
  const toolSet = new Set(matched?.tools ?? []);
  if (rootDirectory) toolSet.add('unix');
  toolSet.add('web');
  return [...toolSet];
}, [prompt, rootDirectory]);
```

Replace with:

```tsx
const availableTools = useMemo(() => {
  const toolSet = new Set(grantedToolGroups);
  if (rootDirectory) toolSet.add('unix');
  return [...toolSet];
}, [grantedToolGroups, rootDirectory]);
```

- [ ] **Step 5: Update `handleSend` to derive tools from `availableTools` and `disabledTools`**

In `handleSend`, find the block that computes `resolvedTools`:

```tsx
const matched = PROMPT_BANK.find(p => p.name === prompt);
const resolvedPrompt = matched?.text ?? prompt;
const toolSet = new Set(matched?.tools ?? []);
if (rootDirectory) toolSet.add('unix');
toolSet.add('web');
const resolvedTools = [...toolSet].join(',');
```

Replace with:

```tsx
const matched = PROMPT_BANK.find(p => p.name === prompt);
const resolvedPrompt = matched?.text ?? prompt;
const enabledTools = availableTools.filter(t => !disabledTools.has(t)).join(',');
```

Then in the `chatStream` call, change `tools: resolvedTools` to `tools: enabledTools`.

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd frontend && npx tsc -b --noEmit
```

Expected: no output (zero errors).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api.ts frontend/src/App.tsx
git commit -m "feat: drive ToolStrip and chat tools from server-granted tool groups"
```
