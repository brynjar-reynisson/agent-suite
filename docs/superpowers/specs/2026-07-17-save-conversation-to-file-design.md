# Save Conversation to File — Design

## Goal

Mirror each conversation to a human-readable `.md` file on disk, kept in sync as the
conversation grows and as it's renamed. This is the first step; a backsweep to backfill
existing prod conversations into files is a separate, later piece of work and is out of
scope here.

## Data model

Add a nullable `md_file_name` column to `conversation`:

- Migration: `supabase/migrations/20260717000000_add_md_file_name_to_conversation.sql`
- Regenerate jOOQ sources after the migration is applied locally.

This column is the single source of truth for "which file on disk represents this
conversation." It is resolved once at creation time and updated on rename. Storing it
avoids re-deriving/guessing the filename from a directory listing on every write, and
keeps rename handling deterministic (find *this* conversation's current file, not
recompute from scratch).

## File naming & lifecycle

- **Directory:** `conversations/` at the project root. Gitignored, same treatment as
  `backups/` (both hold user conversation data).
- **Name pattern:** `<user>_<name>-<env>.md`
  - `<user>` — email local-part (the part before `@`) from `suite_user.email`.
    `guest` for the guest user (`user_id = 1`, no email).
  - `<name>` — `conversation.custom_name` if set, else `conversation.conversation_name`
    (the auto-generated name from the first message). Sanitized for filesystem-safe
    characters (strip/replace characters invalid on Windows: `\ / : * ? " < > |`),
    truncated to a safe length.
  - `<env>` — `dev` or `prod`, read from `spring.profiles.active`.
- **Creation** — in `ConversationService.createConversation`, after the DB row is
  inserted:
  1. Resolve the filename as above.
  2. If a file with that name already exists in `conversations/` for a *different*
     conversation, auto-suffix: `" (2)"`, `" (3)"`, etc., until unique.
  3. Write the file with a header block (conversation name, user email, external id,
     created time, environment).
  4. Store the resolved filename in `conversation.md_file_name`.
- **Rename** — in `ConversationService.renameConversation`, after `custom_name` is
  updated:
  1. Recompute the filename from the new display name, same sanitization/collision
     rules (excluding the conversation's own current file from the collision check).
  2. Rename the file on disk (`Files.move`) from the old `md_file_name` to the new one.
  3. Update `conversation.md_file_name`.
- **Skip condition** — file mirroring is skipped entirely when the active Spring
  profile is neither `dev` nor `prod` (covers test runs and local no-profile runs). DB
  persistence is unaffected either way; only the file side effect is skipped. This
  keeps `conversations/` from being littered with test artifacts.

## Content format

Every call to `ConversationService.addMessage` appends one block to the conversation's
file. This mirrors the raw `message` table 1:1 — every type is appended
(`user`, `assistant`, `tool_call`, `tool_result`, `model_change`, `system_prompt`,
`compact`, `clear`), not a curated/filtered view.

Block format:

```
### <type> — <ISO-8601 timestamp>
<message text>
```

`tool_call` and `tool_result` bodies are fenced as JSON (they're stored as JSON
already):

```
### tool_call — 2026-07-17T14:32:12Z
```json
<message text>
```
```

## Integration points

- **New `ConversationFileService`**, constructed with the `conversations/` base
  directory and the resolved environment label:
  - `Optional<String> createFile(ConversationRecord conv)` — returns the resolved
    filename, or empty if file writing is disabled for this profile.
  - `void appendMessage(ConversationRecord conv, String type, String message)`
  - `Optional<String> renameFile(ConversationRecord conv, String newDisplayName)` —
    returns the new resolved filename, or empty if disabled.
- `ConversationService` depends on `ConversationFileService` and calls it from
  `createConversation`, `addMessage`, and `renameConversation`.
- `ConversationRepository` gets `updateMdFileName(long conversationId, String fileName)`.
- The active-profile check (`dev`/`prod`) is resolved once (constructor-injected
  `Environment` or a `@Value("${spring.profiles.active:}")` string) and cached as a
  boolean "file writing enabled" flag on `ConversationFileService`.

## Error handling

File I/O failures are logged and swallowed — they never surface as a chat-facing error
and never block or roll back DB persistence. This matches the existing pattern in
`ChatOrchestrationService.persistTurnResult`, which already treats its own DB failures
as best-effort/logged rather than fatal. The database remains the authoritative store;
the `.md` file is a best-effort mirror.

## Testing

- Unit tests for filename sanitization and collision resolution (same name twice for
  the same user → `" (2)"` suffix; different users with the same conversation name →
  no collision).
- `ConversationService`/`ConversationFileService` test coverage for:
  - File created with header on first message.
  - Subsequent messages appended in order.
  - Rename updates both the on-disk filename and `md_file_name`.
  - No file is created/written when the active profile isn't `dev` or `prod`.

## Out of scope

- Backfilling `.md` files for conversations that already exist in prod (the
  "backsweep" mentioned by the user) — planned as a follow-up once this write path is
  verified working.
