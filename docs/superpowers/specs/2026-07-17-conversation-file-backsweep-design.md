# Conversation File Backsweep — Design

## Goal

Backfill `.md` mirror files for conversations that already existed in the database
before the `save-conversation-to-file` feature shipped — conversations whose
`conversation.md_file_name` is still `NULL`. Run once against dev now (this design),
reviewed by hand, then run again against prod as a separate later pass using the same
tool.

## What it does

A one-off, manually-invoked JUnit test — `ConversationFileBacksweepRunner`, in package
`com.example.agentsuite.service` (so it can use `ConversationFileService`'s
package-private test constructor to force `enabled=true` regardless of active Spring
profile) — that:

1. Finds every `conversation` row where `md_file_name IS NULL`, ordered by
   `conversation_id`.
2. For each one, recreates its `.md` file from history and persists the resolved
   filename, reusing the exact same `ConversationFileService.createFile` /
   `appendMessage` methods the live application uses — so a backfilled file is
   structurally identical to one written in real time (same header, same block format,
   `tool_result` excluded).

## Trigger & connection

Run via:

```
mvnw test -Dtest=ConversationFileBacksweepRunner
```

The class is a JUnit 5 test but is named so Surefire's default include patterns
(`**/*Test.java`, `**/*Tests.java`, `**/Test*.java`) never match it — a plain `mvn test`
never runs it; only an explicit `-Dtest=ConversationFileBacksweepRunner` does.

Database connection comes from environment variables, with dev-friendly defaults so the
dev run needs no configuration at all:

| Env var | Default |
|---|---|
| `BACKSWEEP_DB_URL` | `jdbc:postgresql://127.0.0.1:54322/postgres` |
| `BACKSWEEP_DB_USERNAME` | `postgres` |
| `BACKSWEEP_DB_PASSWORD` | `postgres` |
| `BACKSWEEP_ENV_LABEL` | `dev` |

For the eventual prod run, only these env vars change (mirroring how
`backup-prod-db.sh` already reads `SUPABASE_PROD_DB_*`) — no code changes between runs.
These are injected into the Spring test context via `@DynamicPropertySource`, with
`@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)` so Spring
doesn't substitute an embedded database. The class does **not** use `@Transactional` —
every write commits for real, immediately, exactly as the live app would.

`ConversationFileService` is constructed directly with its test constructor
(`baseDir = Path.of("conversations")`, `envLabel` from `BACKSWEEP_ENV_LABEL`,
`enabled = true`), bypassing the profile-detection constructor entirely — this tool's
whole purpose is to write files regardless of which Spring profile happens to be active
in the test JVM.

## Per-conversation logic

For every `conversation` row with `md_file_name IS NULL`:

1. Resolve email via `SuiteUserRepository.findById(conv.getUserId())`.
2. Resolve display name: `conv.getCustomName()` if non-blank, else
   `conv.getConversationName()` — the same rule `ConversationService.renameConversation`
   already uses.
3. `conversationFileService.createFile(email, displayName, conv.getExternalId(),
   conv.getCreateTime())` — uses the conversation's real creation timestamp, not "now".
4. Fetch messages via `MessageRepository.findByConversationId(conv.getConversationId())`
   — already ordered by `message_time`/`message_id` and already excludes erased rows.
   Replay each one through `conversationFileService.appendMessage(fileName,
   msg.getType(), msg.getMessage(), msg.getMessageTime())` — real historical
   timestamps. `tool_result` messages are skipped automatically, since that exclusion
   already lives inside `ConversationFileService.appendMessage`.
5. `ConversationRepository.updateMdFileName(conv.getConversationId(), fileName)` once
   every message has been replayed.

## Idempotency

The driving query is `md_file_name IS NULL`, so a conversation that already succeeded
is never reprocessed on a rerun. A conversation that fails partway through simply stays
eligible and gets retried on the next run. In the rare case where `createFile` already
wrote a file to disk but a later step in the same conversation's processing failed
before `updateMdFileName` committed, a rerun's `createFile` call will produce a
different (suffixed) filename via its existing collision-avoidance logic, leaving the
earlier attempt's file orphaned on disk. This is accepted as-is — the tool is a
supervised, manually-run one-time operation, not a routine automated job, and an orphan
file is harmless and easy to spot/delete by hand.

## Output

Prints one line per conversation as it's processed (`external_id -> resolved filename
(N messages)`), then a summary: conversations found, succeeded, failed (with reasons).
The test method asserts zero failures at the end, so a bad run fails loudly with a
non-zero Maven exit code — but every conversation is still attempted and reported
regardless of earlier failures (no fail-fast mid-run).

## Explicitly out of scope

- No dry-run mode. Dev data is low-stakes and `backup-prod-db.sh`/`restore-prod-db.sh`
  already provide a safety net if something needs undoing; a dry-run would also need
  to fake file writes, adding complexity for a tool that's meant to be inspected by
  hand immediately after running.
- No changes to `ConversationService` or any production/live-request code path. This is
  a standalone tool built entirely from existing pieces (`ConversationFileService`,
  `ConversationRepository`, `MessageRepository`, `SuiteUserRepository`).
- No new permanent repository methods for the `md_file_name IS NULL` query — it's a
  one-off migration query, issued directly via the injected `DSLContext` inside the
  runner class rather than added to `ConversationRepository`.
- The prod run itself is a separate, later pass (explicitly requested by the user to
  happen only after reviewing the dev output) and is not part of this design or its
  implementation plan.
