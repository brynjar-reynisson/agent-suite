# Database

## Migrations

Migrations live in `supabase/migrations/`. Local dev migrations are applied automatically by the local Supabase instance.

```bash
# Apply all pending migrations to prod (supabase.co)
npx supabase db push

# First-time setup (one-off, persists credentials):
npx supabase login                                          # opens browser
npx supabase link --project-ref grgspbzqzjblsoxmmojy       # prompts for DB password
```

## Prod Backup & Restore

```bash
# Dump prod app data (public schema only) to backups/prod-data-<timestamp>.sql
./backup-prod-db.sh      # or backup-prod-db.cmd on Windows

# Truncate app tables and restore from newest backup (or pass a file; -y skips confirmation)
./restore-prod-db.sh [-y] [backups/prod-data-<timestamp>.sql]   # or restore-prod-db.cmd
```

Both scripts load `SUPABASE_PROD_DB_HOST/USERNAME/PASSWORD` from the gitignored `.env.production` via the global `dotenv` CLI (dotenv-cli).

- Backups are data-only (schema comes from migrations), scoped to `public` schema — Supabase-managed `auth` tables excluded.
- Restore runs in a single transaction via Dockerized `psql` (`postgres:17`) — a failure rolls back, leaving prod unchanged.
- `backups/` is gitignored (contains user conversation data).

## Message Types

Stored in the `message` table:

| Type | Sent to LLM? | Content |
|---|---|---|
| `system_prompt` | Yes | System prompt text |
| `user` | Yes | User message |
| `assistant` | Yes | LLM text response |
| `tool_call` | Yes | JSON `[{"name":"...","arguments":"..."}]` per iteration |
| `tool_result` | Yes | JSON `[{"name":"...","result":"..."}]` per iteration |
| `model_change` | No | Model alias string |
| `compact` | No | LLM-generated summary of conversation history up to this point |

## Conversation Markdown Mirror

Each conversation is mirrored to a `.md` file under `conversations/` (gitignored — contains
user conversation data, like `backups/`). `ConversationFileService` owns this:

- **Created** on the conversation's first message, alongside the DB row.
- **Appended to** on every subsequent message, one block per row written to the `message`
  table — except `tool_result`, which is deliberately omitted: tool output can contain
  sensitive data (file contents, credentials, command output) and isn't safe to mirror
  to a plain file. `tool_call` (the tool name + arguments) is still written.
- **Renamed on disk** when the conversation is renamed (`ConversationService.renameConversation`).
- **Disabled** unless the active Spring profile is `dev` or `prod` — no files are written during
  tests or no-profile runs.

Filename: `<user>_<name>-<dev|prod>.md`, where `<user>` is the email local-part (`guest` for the
guest user), and `<name>` is `custom_name` if set else `conversation_name`, sanitized and
truncated to 80 characters. Colliding names get a ` (2)`, ` (3)`, ... suffix. The resolved
filename is stored on `conversation.md_file_name` so renames and appends target the right file.

File I/O failures are logged and swallowed — they never block or fail the underlying DB write.
