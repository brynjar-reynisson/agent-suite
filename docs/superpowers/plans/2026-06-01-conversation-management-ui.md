# Conversation Management UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `+` button to start a new conversation and a `☰` button to open a slide-in history panel that lists and loads past conversations, restoring their model and system prompt.

**Architecture:** Two new `GET /ai/conversations` and `GET /ai/conversations/{externalId}` endpoints on `AiController` (backed by new `ConversationService` methods) serve conversation list and reconstructed message history. The frontend adds a `ConversationPanel` component and wires the two buttons into the existing header, with state changes handled in `App.tsx`.

**Tech Stack:** Spring Boot 3.5 / Java 21 / JOOQ / Jackson (backend); React 19 / TypeScript / Tailwind CSS (frontend)

---

## File Map

**New:**
- `src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java`
- `src/main/java/com/example/agentsuite/controller/ConversationDetailDto.java`
- `frontend/src/ConversationPanel.tsx`

**Modified:**
- `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java` — add `getConversationSummaries()` and `getConversationDetail(externalId)`, add `GUEST_USER_ID` constant and `ObjectMapper`
- `src/main/java/com/example/agentsuite/controller/AiController.java` — inject `ConversationService`, add two new `@GetMapping` methods
- `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java` — four new tests for the two new service methods
- `src/test/java/com/example/agentsuite/controller/AiControllerTest.java` — add `@MockBean ConversationService`, four new endpoint tests
- `frontend/src/api.ts` — export `Message` interface, add `ConversationSummary`, `ConversationDetail`, `getConversations()`, `getConversationDetail()`
- `frontend/src/App.tsx` — import `Message` from `api.ts`, add `isPanelOpen` state, `startNewConversation`, `loadConversation`, `+`/`☰` header buttons, render `ConversationPanel`

---

## Task 1: Backend DTOs

**Files:**
- Create: `src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java`
- Create: `src/main/java/com/example/agentsuite/controller/ConversationDetailDto.java`

- [ ] **Step 1: Create ConversationSummaryDto**

```java
package com.example.agentsuite.controller;

public record ConversationSummaryDto(
        String externalId,
        String name,
        String createTime,
        String lastModel,
        String systemPrompt
) {}
```

- [ ] **Step 2: Create ConversationDetailDto**

```java
package com.example.agentsuite.controller;

import java.util.List;

public record ConversationDetailDto(
        String externalId,
        String name,
        String createTime,
        String lastModel,
        String systemPrompt,
        List<MessageDto> messages
) {
    public record MessageDto(String role, String content, List<ToolCallDto> toolCalls) {}
    public record ToolCallDto(String name, String arguments) {}
}
```

- [ ] **Step 3: Verify compilation**

```bash
./mvnw.cmd compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/ConversationSummaryDto.java
git add src/main/java/com/example/agentsuite/controller/ConversationDetailDto.java
git commit -m "feat: add ConversationSummaryDto and ConversationDetailDto response records"
```

---

## Task 2: ConversationService — New Methods + Tests

**Files:**
- Modify: `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java`
- Modify: `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add these four test methods to `ConversationServiceTest.java` after the existing `someoneConversationIsIsolated` test. Also add the following imports at the top of the file:

```java
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.controller.ConversationDetailDto;
import java.util.NoSuchElementException;
```

Test methods to add:

```java
@Test
void getConversationSummaries_returnsGuestConversations() {
    List<ConversationSummaryDto> summaries = service.getConversationSummaries();
    assertThat(summaries).isNotEmpty();
    assertThat(summaries.get(0).name()).isEqualTo("Guest Chat");
    assertThat(summaries.get(0).lastModel()).isEqualTo("deepseek-v4-pro");
}

@Test
void getConversationDetail_throwsForUnknownExternalId() {
    assertThrows(NoSuchElementException.class,
            () -> service.getConversationDetail("no-such-uuid"));
}

@Test
void getConversationDetail_reconstructsUserAndAssistantMessages() {
    String extId = UUID.randomUUID().toString();
    long convId = service.createConversation(guestId, "Test conv", null, extId);
    service.addMessage(convId, guestId, "model_change", "deepseek-v4-pro");
    service.addMessage(convId, guestId, "system_prompt", "Be helpful.");
    service.addMessage(convId, guestId, "user", "Hello!");
    service.addMessage(convId, guestId, "assistant", "Hi there!");

    ConversationDetailDto detail = service.getConversationDetail(extId);

    assertThat(detail.externalId()).isEqualTo(extId);
    assertThat(detail.lastModel()).isEqualTo("deepseek-v4-pro");
    assertThat(detail.systemPrompt()).isEqualTo("Be helpful.");
    assertThat(detail.messages()).hasSize(2);
    assertThat(detail.messages().get(0).role()).isEqualTo("user");
    assertThat(detail.messages().get(0).content()).isEqualTo("Hello!");
    assertThat(detail.messages().get(1).role()).isEqualTo("ai");
    assertThat(detail.messages().get(1).content()).isEqualTo("Hi there!");
    assertThat(detail.messages().get(1).toolCalls()).isEmpty();
}

@Test
void getConversationDetail_groupsToolCallsWithFollowingAssistantMessage() {
    String extId = UUID.randomUUID().toString();
    long convId = service.createConversation(guestId, "Tool conv", null, extId);
    service.addMessage(convId, guestId, "model_change", "deepseek-v4-pro");
    service.addMessage(convId, guestId, "system_prompt", "");
    service.addMessage(convId, guestId, "user", "List files");
    service.addMessage(convId, guestId, "tool_call",
            "[{\"name\":\"ls\",\"arguments\":\"{}\"}]");
    service.addMessage(convId, guestId, "tool_result",
            "[{\"name\":\"ls\",\"result\":\"file.txt\"}]");
    service.addMessage(convId, guestId, "assistant", "Here are the files.");

    ConversationDetailDto detail = service.getConversationDetail(extId);

    assertThat(detail.messages()).hasSize(2);
    ConversationDetailDto.MessageDto aiMsg = detail.messages().get(1);
    assertThat(aiMsg.role()).isEqualTo("ai");
    assertThat(aiMsg.content()).isEqualTo("Here are the files.");
    assertThat(aiMsg.toolCalls()).hasSize(1);
    assertThat(aiMsg.toolCalls().get(0).name()).isEqualTo("ls");
    assertThat(aiMsg.toolCalls().get(0).arguments()).isEqualTo("{}");
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw.cmd test -Dtest=ConversationServiceTest
```

Expected: compile error (methods not yet defined on ConversationService).

- [ ] **Step 3: Implement the new methods in ConversationService**

Replace the entire contents of `ConversationService.java`:

```java
package com.example.agentsuite.jooq.service;

import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.generated.tables.records.ConversationRecord;
import com.example.agentsuite.jooq.generated.tables.records.MessageRecord;
import com.example.agentsuite.jooq.repository.ConversationRepository;
import com.example.agentsuite.jooq.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class ConversationService {

    private static final long GUEST_USER_ID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public ConversationService(ConversationRepository conversationRepository,
                                MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public long createConversation(long userId, String name, String rootDirectory, String externalId) {
        return conversationRepository.insert(userId, name, rootDirectory, externalId);
    }

    @Transactional
    public void addMessage(long conversationId, long userId, String type, String message) {
        messageRepository.insert(conversationId, userId, type, message);
    }

    @Transactional(readOnly = true)
    public List<MessageRecord> getMessages(long conversationId) {
        return messageRepository.findByConversationId(conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationRecord getConversation(long conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }

    @Transactional(readOnly = true)
    public Optional<ConversationRecord> findByExternalId(String externalId) {
        return conversationRepository.findByExternalId(externalId);
    }

    @Transactional(readOnly = true)
    public Optional<String> findLastModelChange(long conversationId) {
        return messageRepository.findLastModelChange(conversationId);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryDto> getConversationSummaries() {
        return conversationRepository.findByUserId(GUEST_USER_ID).stream()
                .map(conv -> {
                    List<MessageRecord> msgs = messageRepository.findByConversationId(conv.getConversationId());
                    String lastModel = msgs.stream()
                            .filter(m -> "model_change".equals(m.getType()))
                            .findFirst()
                            .map(MessageRecord::getMessage)
                            .orElse("");
                    String systemPrompt = msgs.stream()
                            .filter(m -> "system_prompt".equals(m.getType()))
                            .findFirst()
                            .map(MessageRecord::getMessage)
                            .orElse("");
                    return new ConversationSummaryDto(
                            conv.getExternalId(),
                            conv.getConversationName(),
                            conv.getCreateTime().toString(),
                            lastModel,
                            systemPrompt
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationDetailDto getConversationDetail(String externalId) {
        ConversationRecord conv = conversationRepository.findByExternalId(externalId)
                .orElseThrow(() -> new NoSuchElementException("Conversation not found: " + externalId));

        List<MessageRecord> records = messageRepository.findByConversationId(conv.getConversationId());

        String lastModel = "";
        String systemPrompt = "";
        List<ConversationDetailDto.MessageDto> messages = new ArrayList<>();
        List<ConversationDetailDto.ToolCallDto> toolCallBuffer = new ArrayList<>();

        for (MessageRecord r : records) {
            switch (r.getType()) {
                case "model_change"  -> { if (lastModel.isEmpty()) lastModel = r.getMessage(); }
                case "system_prompt" -> { if (systemPrompt.isEmpty()) systemPrompt = r.getMessage(); }
                case "user" -> {
                    toolCallBuffer.clear();
                    messages.add(new ConversationDetailDto.MessageDto("user", r.getMessage(), List.of()));
                }
                case "tool_call"  -> toolCallBuffer.addAll(parseToolCalls(r.getMessage()));
                case "tool_result" -> {} // not shown in UI
                case "assistant" -> {
                    messages.add(new ConversationDetailDto.MessageDto(
                            "ai", r.getMessage(), List.copyOf(toolCallBuffer)));
                    toolCallBuffer.clear();
                }
                default -> {} // ignore unknown types
            }
        }

        return new ConversationDetailDto(
                conv.getExternalId(),
                conv.getConversationName(),
                conv.getCreateTime().toString(),
                lastModel,
                systemPrompt,
                messages
        );
    }

    private List<ConversationDetailDto.ToolCallDto> parseToolCalls(String callsJson) {
        try {
            JsonNode arr = MAPPER.readTree(callsJson);
            List<ConversationDetailDto.ToolCallDto> result = new ArrayList<>();
            for (JsonNode item : arr) {
                result.add(new ConversationDetailDto.ToolCallDto(
                        item.get("name").asText(),
                        item.get("arguments").asText()
                ));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw.cmd test -Dtest=ConversationServiceTest
```

Expected: all tests pass (existing 8 + new 4 = 12 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/jooq/service/ConversationService.java
git add src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java
git commit -m "feat: add getConversationSummaries and getConversationDetail to ConversationService"
```

---

## Task 3: AiController — New Endpoints + Tests

**Files:**
- Modify: `src/main/java/com/example/agentsuite/controller/AiController.java`
- Modify: `src/test/java/com/example/agentsuite/controller/AiControllerTest.java`

- [ ] **Step 1: Write failing tests**

Add these imports to `AiControllerTest.java`:

```java
import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.service.ConversationService;
import org.mockito.Mockito;
import java.util.List;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
```

Add this field to `AiControllerTest` after the existing `@MockBean` fields:

```java
@MockBean
private ConversationService conversationService;
```

Add these four test methods to the class:

```java
@Test
void conversations_returnsSummaryList() throws Exception {
    when(conversationService.getConversationSummaries()).thenReturn(List.of(
            new ConversationSummaryDto("ext-abc", "Hello world", "2026-06-01T10:00:00Z",
                    "deepseek-v4-pro", "")
    ));

    mockMvc.perform(get("/ai/conversations"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].externalId").value("ext-abc"))
            .andExpect(jsonPath("$[0].name").value("Hello world"))
            .andExpect(jsonPath("$[0].lastModel").value("deepseek-v4-pro"));
}

@Test
void conversations_emptyList_returnsEmptyArray() throws Exception {
    when(conversationService.getConversationSummaries()).thenReturn(List.of());

    mockMvc.perform(get("/ai/conversations"))
            .andExpect(status().isOk())
            .andExpect(content().string("[]"));
}

@Test
void conversationDetail_knownId_returnsMessages() throws Exception {
    when(conversationService.getConversationDetail("ext-abc")).thenReturn(
            new ConversationDetailDto("ext-abc", "Hello", "2026-06-01T10:00:00Z",
                    "deepseek-v4-pro", "",
                    List.of(new ConversationDetailDto.MessageDto("user", "Hi there", List.of())))
    );

    mockMvc.perform(get("/ai/conversations/ext-abc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.externalId").value("ext-abc"))
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andExpect(jsonPath("$.messages[0].content").value("Hi there"));
}

@Test
void conversationDetail_unknownId_returns404() throws Exception {
    when(conversationService.getConversationDetail("unknown"))
            .thenThrow(new NoSuchElementException("not found"));

    mockMvc.perform(get("/ai/conversations/unknown"))
            .andExpect(status().isNotFound());
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./mvnw.cmd test -Dtest=AiControllerTest
```

Expected: compile error (endpoints don't exist yet).

- [ ] **Step 3: Add ConversationService to AiController**

In `AiController.java`, add these imports:

```java
import com.example.agentsuite.controller.ConversationDetailDto;
import com.example.agentsuite.controller.ConversationSummaryDto;
import com.example.agentsuite.jooq.service.ConversationService;
import org.springframework.http.ResponseEntity;
import java.util.NoSuchElementException;
```

Add the field after `braveApiKey`:

```java
private final ConversationService conversationService;
```

Update the constructor to inject `ConversationService` (add it as the third parameter before `braveApiKey`):

```java
public AiController(ChatOrchestrationService orchestrationService,
                    ModelRegistry modelRegistry,
                    ConversationService conversationService,
                    @Value("${brave.api-key}") String braveApiKey) {
    this.orchestrationService = orchestrationService;
    this.modelRegistry = modelRegistry;
    this.conversationService = conversationService;
    this.braveApiKey = braveApiKey;
}
```

Add the two new endpoint methods after `getAllowedDirectories()` and before `chat()`:

```java
@GetMapping("/ai/conversations")
public List<ConversationSummaryDto> getConversations() {
    return conversationService.getConversationSummaries();
}

@GetMapping("/ai/conversations/{externalId}")
public ResponseEntity<ConversationDetailDto> getConversationDetail(
        @PathVariable String externalId) {
    try {
        return ResponseEntity.ok(conversationService.getConversationDetail(externalId));
    } catch (NoSuchElementException e) {
        return ResponseEntity.notFound().build();
    }
}
```

Also add `import java.util.List;` if not already present (check existing imports in the file).

- [ ] **Step 4: Run tests**

```bash
./mvnw.cmd test
```

Expected: all tests pass (102 existing + 4 new = 106 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentsuite/controller/AiController.java
git add src/test/java/com/example/agentsuite/controller/AiControllerTest.java
git commit -m "feat: add GET /ai/conversations and GET /ai/conversations/{externalId} endpoints"
```

---

## Task 4: Frontend API Types and Functions

**Files:**
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/App.tsx` (import change only)

- [ ] **Step 1: Export Message from api.ts and add new types/functions**

Replace the entire contents of `frontend/src/api.ts`:

```typescript
const API_BASE_URL = ''; // Use relative URL to support proxying

export interface ToolCall {
  name: string;
  arguments: string;
}

export interface Message {
  role: 'user' | 'ai';
  content: string;
  toolCalls?: ToolCall[];
}

export interface ConversationSummary {
  externalId: string;
  name: string;
  createTime: string;
  lastModel: string;
  systemPrompt: string;
}

export interface ConversationDetail extends ConversationSummary {
  messages: Message[];
}

export interface ChatRequest {
  message: string;
  prompt?: string;
  rootDirectory?: string;
  model?: string;
  tools?: string;
  conversationId?: string;
}

export interface StreamCallbacks {
  onToolCall: (tc: ToolCall) => void;
  onContent: (text: string) => void;
}

export const chatStream = (params: ChatRequest, callbacks: StreamCallbacks): Promise<void> => {
  const urlParams = new URLSearchParams({
    message: params.message,
    prompt: params.prompt || '',
    rootDirectory: params.rootDirectory || '',
    model: params.model || 'deepseek-v4-pro',
    ...(params.tools ? { tools: params.tools } : {}),
    ...(params.conversationId ? { conversationId: params.conversationId } : {}),
  });
  const url = `${API_BASE_URL}/ai/chat?${urlParams.toString()}`;

  return new Promise((resolve, reject) => {
    const source = new EventSource(url);
    let resolved = false;

    const finish = () => {
      if (!resolved) {
        resolved = true;
        source.close();
        resolve();
      }
    };

    source.addEventListener('tool_call', (e) => {
      const data = JSON.parse(e.data);
      callbacks.onToolCall(data);
    });

    source.addEventListener('content', (e) => {
      callbacks.onContent(e.data);
    });

    source.addEventListener('done', () => {
      finish();
    });

    source.addEventListener('error', (e) => {
      if (resolved) return;
      const data = (e as MessageEvent).data;
      if (data) {
        reject(new Error(data));
      } else {
        reject(new Error('Connection error'));
      }
      source.close();
    });
  });
};

export const getDirectories = async (): Promise<string[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/config/directories`);
  return response.json();
};

export const execTool = async (command: string, rootDirectory: string): Promise<string> => {
  const urlParams = new URLSearchParams({ command, rootDirectory });
  const response = await fetch(`${API_BASE_URL}/ai/tools?${urlParams.toString()}`);
  return response.text();
};

export const getConversations = async (): Promise<ConversationSummary[]> => {
  const response = await fetch(`${API_BASE_URL}/ai/conversations`);
  if (!response.ok) throw new Error('Failed to fetch conversations');
  return response.json();
};

export const getConversationDetail = async (externalId: string): Promise<ConversationDetail> => {
  const response = await fetch(
    `${API_BASE_URL}/ai/conversations/${encodeURIComponent(externalId)}`
  );
  if (!response.ok) throw new Error('Conversation not found');
  return response.json();
};
```

- [ ] **Step 2: Update App.tsx import line**

In `frontend/src/App.tsx`, find the existing import line:

```typescript
import { chatStream, execTool, getDirectories, type ToolCall } from './api';
```

Replace it with:

```typescript
import {
  chatStream, execTool, getDirectories, getConversationDetail,
  type ToolCall, type Message, type ConversationSummary,
} from './api';
```

- [ ] **Step 3: Remove the Message interface from App.tsx**

In `frontend/src/App.tsx`, find and remove this interface (it is now exported from `api.ts`):

```typescript
interface Message {
  role: 'user' | 'ai';
  content: string;
  toolCalls?: ToolCall[];
}
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd frontend && npm run build
```

Expected: BUILD success with no TypeScript errors. Revert to `frontend/` directory after.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api.ts frontend/src/App.tsx
git commit -m "feat: export Message type from api.ts; add getConversations and getConversationDetail"
```

---

## Task 5: ConversationPanel Component

**Files:**
- Create: `frontend/src/ConversationPanel.tsx`

- [ ] **Step 1: Create ConversationPanel.tsx**

```typescript
import { useState, useEffect } from 'react';
import { getConversations, type ConversationSummary } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (conv: ConversationSummary) => Promise<void>;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
}

export function ConversationPanel({ isOpen, onClose, onSelect }: Props) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectError, setSelectError] = useState<string | null>(null);
  const [selecting, setSelecting] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setListLoading(true);
    setListError(null);
    setSelectError(null);
    getConversations()
      .then(setConversations)
      .catch(() => setListError('Failed to load conversations'))
      .finally(() => setListLoading(false));
  }, [isOpen]);

  useEffect(() => {
    if (!isOpen) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKey);
    return () => document.removeEventListener('keydown', handleKey);
  }, [isOpen, onClose]);

  const handleSelect = async (conv: ConversationSummary) => {
    setSelecting(conv.externalId);
    setSelectError(null);
    try {
      await onSelect(conv);
    } catch {
      setSelectError('Failed to load conversation');
    } finally {
      setSelecting(null);
    }
  };

  if (!isOpen) return null;

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-10" onClick={onClose} />
      {/* Panel */}
      <div className="fixed top-0 right-0 h-full w-72 bg-white shadow-xl z-20 flex flex-col border-l border-gray-200">
        <div className="p-4 border-b flex justify-between items-center shrink-0">
          <h2 className="font-semibold text-gray-800 text-sm">Past Conversations</h2>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-lg leading-none"
            aria-label="Close"
          >
            ✕
          </button>
        </div>
        {selectError && (
          <div className="px-4 py-2 bg-red-50 text-red-600 text-xs border-b shrink-0">
            {selectError}
          </div>
        )}
        <div className="flex-1 overflow-y-auto">
          {listLoading && (
            <p className="p-4 text-sm text-gray-400">Loading...</p>
          )}
          {listError && (
            <p className="p-4 text-sm text-red-500">{listError}</p>
          )}
          {!listLoading && !listError && conversations.length === 0 && (
            <p className="p-4 text-sm text-gray-400">No conversations yet.</p>
          )}
          {conversations.map((conv) => (
            <button
              key={conv.externalId}
              onClick={() => handleSelect(conv)}
              disabled={selecting !== null}
              className="w-full text-left px-4 py-3 border-b hover:bg-gray-50 transition-colors disabled:opacity-50"
            >
              <div className="flex justify-between items-baseline gap-2">
                <span className="font-medium text-gray-800 text-sm truncate">
                  {selecting === conv.externalId ? 'Loading...' : conv.name}
                </span>
                <span className="text-xs text-gray-400 shrink-0">
                  {formatDate(conv.createTime)}
                </span>
              </div>
            </button>
          ))}
        </div>
      </div>
    </>
  );
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npm run build
```

Expected: BUILD success.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/ConversationPanel.tsx
git commit -m "feat: add ConversationPanel slide-in component"
```

---

## Task 6: App.tsx — Header Buttons and Conversation Loading

**Files:**
- Modify: `frontend/src/App.tsx`

- [ ] **Step 1: Import ConversationPanel**

In `frontend/src/App.tsx`, add this import after the existing imports:

```typescript
import { ConversationPanel } from './ConversationPanel';
```

- [ ] **Step 2: Add isPanelOpen state**

In `App.tsx`, after the `const conversationId = useRef...` line, add:

```typescript
const [isPanelOpen, setIsPanelOpen] = useState(false);
```

- [ ] **Step 3: Add startNewConversation and loadConversation handlers**

After the `useEffect` blocks (before `handleSend`), add:

```typescript
const startNewConversation = () => {
  conversationId.current = crypto.randomUUID();
  setMessages([]);
  setModel('deepseek-v4-pro');
  setPrompt('');
};

const loadConversation = async (conv: ConversationSummary): Promise<void> => {
  const detail = await getConversationDetail(conv.externalId);
  conversationId.current = detail.externalId;
  setMessages(detail.messages);
  setModel(detail.lastModel);
  setPrompt(detail.systemPrompt);
  setIsPanelOpen(false);
};
```

- [ ] **Step 4: Update the header to add + and ☰ buttons**

Find the existing header `<div className="flex gap-4 items-center">` block that wraps the model selector:

```tsx
<div className="flex gap-4 items-center">
  <select 
    value={model} 
    onChange={(e) => setModel(e.target.value)}
    className="border rounded px-2 py-1 text-sm bg-gray-50"
  >
    {MODELS.map((m) => (
      <option key={m} value={m}>{m}</option>
    ))}
  </select>
</div>
```

Replace it with:

```tsx
<div className="flex gap-2 items-center">
  <select
    value={model}
    onChange={(e) => setModel(e.target.value)}
    className="border rounded px-2 py-1 text-sm bg-gray-50"
  >
    {MODELS.map((m) => (
      <option key={m} value={m}>{m}</option>
    ))}
  </select>
  <button
    onClick={startNewConversation}
    disabled={loading}
    title="New conversation"
    className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 font-bold text-lg leading-none"
    aria-label="New conversation"
  >
    +
  </button>
  <button
    onClick={() => setIsPanelOpen(true)}
    disabled={loading}
    title="Past conversations"
    className="p-1.5 rounded hover:bg-gray-100 disabled:opacity-50 text-gray-600 text-base leading-none"
    aria-label="Past conversations"
  >
    ☰
  </button>
</div>
```

- [ ] **Step 5: Render ConversationPanel**

Find the closing `</div>` of the outermost `<div className="flex flex-col h-screen...">` element (the last line before `export default App`). Insert `<ConversationPanel>` just before `</div>`:

```tsx
      <ConversationPanel
        isOpen={isPanelOpen}
        onClose={() => setIsPanelOpen(false)}
        onSelect={loadConversation}
      />
    </div>
  );
}
```

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd frontend && npm run build
```

Expected: BUILD success with no TypeScript errors.

- [ ] **Step 7: Run backend tests**

```bash
./mvnw.cmd test
```

Expected: all 106 tests pass.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/App.tsx
git commit -m "feat: add + and history buttons; wire conversation load and new conversation"
```

---

## Final Verification

- [ ] Ensure backend and frontend are running (`bash restart.sh` from project root)
- [ ] Open `http://localhost:5176`
- [ ] Send a message — verify it persists (check that the `+` button now starts a fresh blank chat)
- [ ] Click `☰` — verify the history panel slides in from the right showing past conversations with names and dates
- [ ] Click a past conversation — verify messages load, model is restored in the selector, prompt is restored in the system prompt field
- [ ] Click the backdrop or press Esc — verify panel closes
- [ ] Click `+` during loading — verify it is disabled (greyed out)
