# Prod Database Backup & Restore — Design

**Date:** 2026-06-10
**Status:** Approved

## Goal

Scriptable backup and restore of the prod database (supabase.co project `grgspbzqzjblsoxmmojy`), as paired `.cmd`/`.sh` files at the repo root. Backup saves the current state to a timestamped file; restore resets the app data and loads a selected backup (newest by default).

## Scope decisions

- **Data scope: public app data only.** Schema is reproducible from `supabase/migrations/`; backups capture only row data of the public-schema tables (`suite_user`, `conversation`, `message`, `user_role`) plus sequence states. Supabase-managed `auth.users` is not backed up — auth accounts survive an app-data reset and `suite_user` re-links by `uuid`.
- **Reset method: truncate.** Restore truncates the four public tables (`RESTART IDENTITY CASCADE`) rather than running `supabase db reset --linked`, which would wipe Supabase-managed schemas (auth sessions/users) on prod.
- **Packaging: two script pairs.** `backup-prod-db.{cmd,sh}` and `restore-prod-db.{cmd,sh}`. Restore always resets-then-loads; there is no standalone reset script.
- **Env vars: loaded from `.env.production`** via the globally installed `dotenv` CLI (dotenv-cli). The file is gitignored and will be populated with `SUPABASE_PROD_DB_HOST`, `SUPABASE_PROD_DB_USERNAME`, `SUPABASE_PROD_DB_PASSWORD`.

## Tooling

- **Backup:** `npx supabase db dump --linked --data-only --use-copy --schema public`. The project is already linked; the CLI runs a version-matched `pg_dump` in Docker and emits Supabase-aware data dumps: the header disables FK trigger enforcement via `session_replication_role = replica`, so table load order cannot break the restore, and sequence `setval`s are included. The explicit `--schema public` is required — without it the CLI also dumps `auth` schema data, which collides with existing `auth.users` rows on restore (discovered during live testing).
- **Restore:** `psql` from a `postgres:17` Docker container (no local PostgreSQL client tools exist on this machine; Docker Desktop is required and already used by local Supabase). Password is passed via `PGPASSWORD` into the container environment, never on the command line.

Alternatives considered: hand-rolled Docker `pg_dump` for backup (rejected: data-only dumps don't guarantee FK-safe ordering and `--disable-triggers` needs superuser, which Supabase doesn't grant); Supabase platform backups/PITR (rejected: not file-granular or scriptable as requested).

## Files

| File | Purpose |
|---|---|
| `backup-prod-db.cmd` / `backup-prod-db.sh` | Dump prod public-schema data to `backups/prod-data-<yyyyMMdd-HHmmss>.sql` |
| `restore-prod-db.cmd` / `restore-prod-db.sh` | Truncate app tables and load a backup file (newest by default) |
| `backups/` | Backup storage, added to `.gitignore` (contains user conversation data) |

## Env loading pattern

Each script re-invokes itself once through dotenv-cli so all vars come from `.env.production` without a second wrapper file:

```sh
# .sh
if [ -z "$_DOTENV_WRAPPED" ]; then
  _DOTENV_WRAPPED=1 exec dotenv -e .env.production -- "$0" "$@"
fi
```

```bat
:: .cmd
if "%_DOTENV_WRAPPED%"=="" (
  set _DOTENV_WRAPPED=1
  dotenv -e .env.production -- cmd /c "%~f0" %*
  exit /b %errorlevel%
)
```

## Backup script behavior

1. `cd` to repo root; create `backups/` if missing.
2. Run `npx supabase db dump --linked --data-only --use-copy --schema public -f backups/prod-data-<yyyyMMdd-HHmmss>.sql`.
3. Fail (non-zero exit, clear message) if the dump command fails or the output file is missing/empty.
4. Print the resulting file path and size.

## Restore script behavior

1. Optional argument: path to a backup file. If omitted, pick the newest `backups/prod-data-*.sql` by name (timestamps sort lexicographically). Error out if none exist.
2. Validate the backup file exists and is non-empty, and that `SUPABASE_PROD_DB_HOST`, `SUPABASE_PROD_DB_USERNAME`, `SUPABASE_PROD_DB_PASSWORD` are set (loaded from `.env.production`).
3. Print which file will be restored and require typing `yes` to proceed; `-y`/`--yes` skips the prompt.
4. Run `docker run --rm -i -e PGPASSWORD postgres:17 psql -h <host> -U <user> -d postgres --single-transaction ...`, feeding on stdin:
   - `TRUNCATE suite_user, conversation, message, user_role RESTART IDENTITY CASCADE;`
   - the backup file contents.

   Single transaction: a mid-restore failure leaves prod data untouched rather than half-wiped. `RESTART IDENTITY` resets `suite_user_id_seq`; the dump's `setval` restores it, so post-restore user IDs cannot collide.
5. Print row counts per table afterward as a sanity check.

## Error handling

Every step exits non-zero with a clear message on failure. Restore never truncates unless the backup file exists, is non-empty, and (unless `-y`) the user confirmed. `psql` runs with `ON_ERROR_STOP` so any SQL error aborts the transaction.

## Testing

Run a real cycle against prod: take a backup, restore from it, verify per-table row counts match before/after. Restoring a just-taken backup is data-equivalent to a no-op, so this is a safe live test. Test both the default (newest) selection and an explicit file argument, plus the confirmation prompt and `-y` path.
