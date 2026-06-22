# Database Optimizations Design

**Date:** 2026-06-22
**Branch:** `feature/database_optimizations`
**Status:** Approved

## Goal

Fix two confirmed performance problems: missing FK indexes and an N+1 query in `getConversationSummaries` that loads all message content for every conversation just to extract two metadata values.

## Confirmed Problems

### N+1 in `getConversationSummaries`

`ConversationService.getConversationSummaries` (lines 80–104) does:
1. One query: `conversationRepository.findByUserId(userId)` — returns N conversations
2. N queries: `messageRepository.findByConversationId(conv.getConversationId())` — returns **all columns of all messages** per conversation

For each of those N full message fetches, it only uses two values: the first `model_change` message and the first `system_prompt` message. All other message rows (user text, LLM responses, tool calls) are fetched and immediately discarded. This is the same root cause as the user's concern about loading full conversation data for the summary list.

### Missing Indexes (Supabase advisor)

Three unindexed foreign keys confirmed by `get_advisors`:
- `conversation.user_id` (`fk_conversation_user`) — hit on every conversation list load
- `message.conversation_id` (`fk_message_conversation`) — hit on every message fetch
- `message.user_id` (`fk_message_user`) — FK constraint checks

## Design

### Task 1: Add missing FK indexes

New migration: `supabase/migrations/20260622000001_add_missing_fk_indexes.sql`

```sql
CREATE INDEX idx_conversation_user_id    ON conversation(user_id);
CREATE INDEX idx_message_conversation_id ON message(conversation_id);
CREATE INDEX idx_message_user_id         ON message(user_id);
```

Same three `CREATE INDEX` statements added to `src/test/resources/schema.sql` (before the final constraint block) so tests reflect the real schema.

### Task 2: Fix the N+1 in `getConversationSummaries`

**New method on `MessageRepository`:**

```java
public Map<Long, String[]> findFirstMetaByConversationIds(List<Long> conversationIds)
```

- Fetches only `conversation_id`, `type`, and `message` columns
- Filters: `conversation_id IN (:ids)` and `type IN ('model_change', 'system_prompt')`
- Orders by `message_time ASC, message_id ASC` (same ordering as existing queries)
- Returns `Map<Long, String[]>` where `arr[0]` = first `model_change` value (or `""`), `arr[1]` = first `system_prompt` value (or `""`)
- If `conversationIds` is empty, returns an empty map immediately (no query)
- Uses standard SQL `IN (...)` — compatible with H2 (tests) and PostgreSQL (prod)

**Updated `ConversationService.getConversationSummaries`:**

Replaces all N `findByConversationId` calls with a single call to `findFirstMetaByConversationIds`. Total queries: 2 (was N+1).

```java
public List<ConversationSummaryDto> getConversationSummaries(long userId) {
    List<ConversationRecord> convs = conversationRepository.findByUserId(userId);
    if (convs.isEmpty()) return List.of();
    List<Long> ids = convs.stream().map(ConversationRecord::getConversationId).toList();
    Map<Long, String[]> meta = messageRepository.findFirstMetaByConversationIds(ids);
    return convs.stream()
            .map(conv -> {
                String[] m = meta.getOrDefault(conv.getConversationId(), new String[]{"", ""});
                return new ConversationSummaryDto(
                        conv.getExternalId(),
                        conv.getConversationName(),
                        conv.getCustomName(),
                        conv.getCreateTime().toString(),
                        m[0],
                        m[1]
                );
            })
            .toList();
}
```

**Tests:**

New test method in the existing `ConversationServiceTest` (jOOQ integration test on H2):
- Inserts 2 conversations with different model/prompt values
- Asserts `getConversationSummaries` returns correct `initialModel` and `systemPrompt` for each

The existing `getConversationSummaries_customNameIsNullWhenNotSet` test continues to pass as a regression check.

## Files Changed

| File | Change |
|------|--------|
| `supabase/migrations/20260622000001_add_missing_fk_indexes.sql` | New — 3 CREATE INDEX statements |
| `src/test/resources/schema.sql` | Add matching CREATE INDEX statements |
| `src/main/java/com/example/agentsuite/jooq/repository/MessageRepository.java` | Add `findFirstMetaByConversationIds` |
| `src/main/java/com/example/agentsuite/jooq/service/ConversationService.java` | Rewrite `getConversationSummaries` to use new bulk method |
| `src/test/java/com/example/agentsuite/jooq/ConversationServiceTest.java` | Add 2-conversation meta test |

## Out of Scope

- Indexing `conversation.external_id` (only used for single-row lookups, PK-equivalent access pattern, low priority)
- Optimizing `getConversationDetail` (loads all messages intentionally — full message content is needed there)
- Any frontend changes
