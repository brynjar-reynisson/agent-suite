# Prod DB Backup & Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Paired `.cmd`/`.sh` scripts at the repo root that back up prod app data to timestamped files and reset-and-restore prod from a selected backup (newest by default).

**Architecture:** Backup uses `npx supabase db dump --linked --data-only` (project already linked to `grgspbzqzjblsoxmmojy`; the CLI runs a version-matched `pg_dump` in Docker and emits FK-safe data dumps). Restore pipes a `TRUNCATE ... RESTART IDENTITY CASCADE` preamble plus the backup file into `psql` from a `postgres:17` Docker container in a single transaction. Both scripts self-rewrap through the globally installed `dotenv` CLI to load `SUPABASE_PROD_DB_*` vars from the gitignored `.env.production`.

**Tech Stack:** Windows cmd, POSIX sh, dotenv-cli (global), Supabase CLI via npx, Docker (`postgres:17` image).

**Spec:** `docs/superpowers/specs/2026-06-10-prod-db-backup-restore-design.md`

**Note on testing:** These are ops scripts against the live prod database — there is no unit-test harness. Each task verifies by running the script for real. Restoring a just-taken backup is data-equivalent to a no-op, so the live restore test is safe. The `.sh` variants are tested from Git Bash on this machine.

---

### Task 1: Gitignore the backups directory

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add `backups/` to `.gitignore`**

In `.gitignore`, after the `# Production release artifacts` block:

```
# Production release artifacts
release/

# Prod database backups (contain user conversation data)
backups/
```

- [ ] **Step 2: Verify git ignores it**

Run: `mkdir -p backups && touch backups/probe.txt && git -C . status --porcelain backups/ && rm backups/probe.txt`
Expected: no output from `git status --porcelain` (directory ignored).

- [ ] **Step 3: Commit**

```bash
git add .gitignore
git commit -m "chore: gitignore backups/ directory for prod DB dumps"
```

---

### Task 2: Backup scripts

**Files:**
- Create: `backup-prod-db.sh`
- Create: `backup-prod-db.cmd`

- [ ] **Step 1: Write `backup-prod-db.sh`**

```sh
#!/bin/sh
set -eu
cd "$(dirname "$0")"

# Re-exec once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if [ -z "${_DOTENV_WRAPPED:-}" ]; then
  _DOTENV_WRAPPED=1 exec dotenv -e .env.production -- "$0" "$@"
fi

mkdir -p backups
ts=$(date +%Y%m%d-%H%M%S)
out="backups/prod-data-$ts.sql"

echo "Dumping prod data to $out ..."
if ! npx --yes supabase db dump --linked --data-only --use-copy --schema public -f "$out"; then
  echo "ERROR: supabase db dump failed" >&2
  rm -f "$out"
  exit 1
fi

if [ ! -s "$out" ]; then
  echo "ERROR: dump produced a missing or empty file: $out" >&2
  rm -f "$out"
  exit 1
fi

echo "Backup written: $out ($(wc -c < "$out") bytes)"
```

- [ ] **Step 2: Write `backup-prod-db.cmd`**

```bat
@echo off
cd /d %~dp0

rem Re-invoke once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if "%_DOTENV_WRAPPED%"=="" (
    set _DOTENV_WRAPPED=1
    call dotenv -e .env.production -- cmd /c "%~f0" %*
    exit /b %errorlevel%
)

if not exist backups mkdir backups

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set TS=%%i
set OUT=backups\prod-data-%TS%.sql

echo Dumping prod data to %OUT% ...
call npx --yes supabase db dump --linked --data-only --use-copy --schema public -f "%OUT%"
if errorlevel 1 (
    echo ERROR: supabase db dump failed
    if exist "%OUT%" del /q "%OUT%"
    exit /b 1
)

if not exist "%OUT%" (
    echo ERROR: dump produced no file: %OUT%
    exit /b 1
)
for %%F in ("%OUT%") do (
    if %%~zF==0 (
        echo ERROR: dump produced empty file: %OUT%
        del /q "%OUT%"
        exit /b 1
    )
    echo Backup written: %OUT% ^(%%~zF bytes^)
)
```

- [ ] **Step 3: Run the cmd variant for real**

Run: `cmd /c backup-prod-db.cmd`
Expected: exit 0, output ends with `Backup written: backups\prod-data-<timestamp>.sql (<N> bytes)` where N > 0. Inspect the file head: it should contain `SET session_replication_role = replica;` (or equivalent Supabase dump header) and `COPY` statements for `suite_user`, `conversation`, `message`, `user_role` (empty tables produce a COPY block with no rows — that is fine).

- [ ] **Step 4: Run the sh variant for real (Git Bash)**

Run: `bash backup-prod-db.sh`
Expected: exit 0, a second `backups/prod-data-*.sql` file with the same structure.

- [ ] **Step 5: Commit**

```bash
git add backup-prod-db.sh backup-prod-db.cmd
git commit -m "feat: add prod DB backup scripts (cmd + sh)"
```

---

### Task 3: Restore scripts

**Files:**
- Create: `restore-prod-db.sh`
- Create: `restore-prod-db.cmd`

- [ ] **Step 1: Write `restore-prod-db.sh`**

```sh
#!/bin/sh
set -eu
cd "$(dirname "$0")"

# Re-exec once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if [ -z "${_DOTENV_WRAPPED:-}" ]; then
  _DOTENV_WRAPPED=1 exec dotenv -e .env.production -- "$0" "$@"
fi

usage() {
  echo "Usage: $0 [-y|--yes] [backup-file.sql]" >&2
  echo "Default backup file: newest backups/prod-data-*.sql" >&2
}

yes=0
file=""
for arg in "$@"; do
  case "$arg" in
    -y|--yes) yes=1 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "ERROR: unknown option: $arg" >&2; usage; exit 1 ;;
    *) file="$arg" ;;
  esac
done

if [ -z "$file" ]; then
  file=$(ls backups/prod-data-*.sql 2>/dev/null | sort | tail -n 1 || true)
  if [ -z "$file" ]; then
    echo "ERROR: no backups found in backups/ (run backup-prod-db first)" >&2
    exit 1
  fi
fi

if [ ! -s "$file" ]; then
  echo "ERROR: backup file missing or empty: $file" >&2
  exit 1
fi

: "${SUPABASE_PROD_DB_HOST:?ERROR: SUPABASE_PROD_DB_HOST not set - populate .env.production}"
: "${SUPABASE_PROD_DB_USERNAME:?ERROR: SUPABASE_PROD_DB_USERNAME not set - populate .env.production}"
: "${SUPABASE_PROD_DB_PASSWORD:?ERROR: SUPABASE_PROD_DB_PASSWORD not set - populate .env.production}"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running (needed for psql)" >&2
  exit 1
fi

echo "About to WIPE prod app data (suite_user, conversation, message, user_role)"
echo "and restore from: $file"
if [ "$yes" -ne 1 ]; then
  printf "Type 'yes' to continue: "
  read -r answer
  if [ "$answer" != "yes" ]; then
    echo "Aborted."
    exit 1
  fi
fi

export PGPASSWORD="$SUPABASE_PROD_DB_PASSWORD"

{
  echo "TRUNCATE suite_user, conversation, message, user_role RESTART IDENTITY CASCADE;"
  cat "$file"
} | docker run --rm -i -e PGPASSWORD postgres:17 \
      psql -h "$SUPABASE_PROD_DB_HOST" -U "$SUPABASE_PROD_DB_USERNAME" -d postgres \
           --single-transaction -v ON_ERROR_STOP=1 -q

echo "Restore complete. Row counts:"
docker run --rm -e PGPASSWORD postgres:17 \
  psql -h "$SUPABASE_PROD_DB_HOST" -U "$SUPABASE_PROD_DB_USERNAME" -d postgres -t -A -c \
  "SELECT 'suite_user:   ' || count(*) FROM suite_user
   UNION ALL SELECT 'conversation: ' || count(*) FROM conversation
   UNION ALL SELECT 'message:      ' || count(*) FROM message
   UNION ALL SELECT 'user_role:    ' || count(*) FROM user_role;"
```

- [ ] **Step 2: Write `restore-prod-db.cmd`**

```bat
@echo off
cd /d %~dp0

rem Re-invoke once through dotenv-cli so SUPABASE_PROD_* vars come from .env.production
if "%_DOTENV_WRAPPED%"=="" (
    set _DOTENV_WRAPPED=1
    call dotenv -e .env.production -- cmd /c "%~f0" %*
    exit /b %errorlevel%
)

set YES=0
set FILE=

:parse
if "%~1"=="" goto parsed
if /i "%~1"=="-y" (
    set YES=1
) else if /i "%~1"=="--yes" (
    set YES=1
) else (
    set FILE=%~1
)
shift
goto parse
:parsed

if "%FILE%"=="" (
    for /f "delims=" %%f in ('dir /b /o:n backups\prod-data-*.sql 2^>nul') do set FILE=backups\%%f
)
if "%FILE%"=="" (
    echo ERROR: no backups found in backups\ - run backup-prod-db first
    exit /b 1
)

if not exist "%FILE%" (
    echo ERROR: backup file not found: %FILE%
    exit /b 1
)
for %%F in ("%FILE%") do if %%~zF==0 (
    echo ERROR: backup file is empty: %FILE%
    exit /b 1
)

if "%SUPABASE_PROD_DB_HOST%"=="" (
    echo ERROR: SUPABASE_PROD_DB_HOST not set - populate .env.production
    exit /b 1
)
if "%SUPABASE_PROD_DB_USERNAME%"=="" (
    echo ERROR: SUPABASE_PROD_DB_USERNAME not set - populate .env.production
    exit /b 1
)
if "%SUPABASE_PROD_DB_PASSWORD%"=="" (
    echo ERROR: SUPABASE_PROD_DB_PASSWORD not set - populate .env.production
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not running - needed for psql
    exit /b 1
)

echo About to WIPE prod app data (suite_user, conversation, message, user_role)
echo and restore from: %FILE%
if "%YES%"=="1" goto confirmed
set /p ANSWER=Type 'yes' to continue: 
if /i not "%ANSWER%"=="yes" (
    echo Aborted.
    exit /b 1
)
:confirmed

set PGPASSWORD=%SUPABASE_PROD_DB_PASSWORD%

(
    echo TRUNCATE suite_user, conversation, message, user_role RESTART IDENTITY CASCADE;
    type "%FILE%"
) | docker run --rm -i -e PGPASSWORD postgres:17 psql -h %SUPABASE_PROD_DB_HOST% -U %SUPABASE_PROD_DB_USERNAME% -d postgres --single-transaction -v ON_ERROR_STOP=1 -q
if errorlevel 1 (
    echo ERROR: restore failed - transaction rolled back, prod data unchanged
    exit /b 1
)

echo Restore complete. Row counts:
docker run --rm -e PGPASSWORD postgres:17 psql -h %SUPABASE_PROD_DB_HOST% -U %SUPABASE_PROD_DB_USERNAME% -d postgres -t -A -c "SELECT 'suite_user:   ' || count(*) FROM suite_user UNION ALL SELECT 'conversation: ' || count(*) FROM conversation UNION ALL SELECT 'message:      ' || count(*) FROM message UNION ALL SELECT 'user_role:    ' || count(*) FROM user_role;"
```

**Implementation note for the `.cmd` confirmation prompt:** the `goto confirmed` structure is deliberate — reading `%ANSWER%` inside an `if (...)` block would expand at parse time (before `set /p` runs) and always see the stale value. Do not "simplify" it back into a block.

- [ ] **Step 3: Capture pre-restore row counts**

Run (Git Bash, with `.env.production` populated):

```bash
dotenv -e .env.production -- bash -c 'PGPASSWORD="$SUPABASE_PROD_DB_PASSWORD" docker run --rm -e PGPASSWORD postgres:17 psql -h "$SUPABASE_PROD_DB_HOST" -U "$SUPABASE_PROD_DB_USERNAME" -d postgres -t -A -c "SELECT '\''suite_user:'\''||count(*) FROM suite_user UNION ALL SELECT '\''conversation:'\''||count(*) FROM conversation UNION ALL SELECT '\''message:'\''||count(*) FROM message UNION ALL SELECT '\''user_role:'\''||count(*) FROM user_role;"'
```

Record the four counts.

- [ ] **Step 4: Live restore test — cmd variant, explicit file**

Run: `cmd /c restore-prod-db.cmd -y backups\prod-data-<newest-timestamp>.sql` (use the file from Task 2)
Expected: exit 0, `Restore complete.`, row counts identical to Step 3.

- [ ] **Step 5: Live restore test — sh variant, default newest selection**

Run: `bash restore-prod-db.sh -y`
Expected: it announces the newest backup file, exit 0, row counts identical to Step 3.

- [ ] **Step 6: Confirmation prompt test**

Run: `bash -c 'echo no | bash restore-prod-db.sh'`
Expected: prints `Aborted.`, exit 1, no data change.

- [ ] **Step 7: Commit**

```bash
git add restore-prod-db.sh restore-prod-db.cmd
git commit -m "feat: add prod DB restore scripts (cmd + sh)"
```

---

### Task 4: Document in CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (after the "Database Migrations" section)

- [ ] **Step 1: Add backup/restore section to CLAUDE.md**

Insert after the Database Migrations code block:

```markdown
## Prod Database Backup & Restore

```bash
# Dump prod app data (public schema) to backups/prod-data-<timestamp>.sql
./backup-prod-db.sh      # or backup-prod-db.cmd on Windows

# Truncate app tables and restore from newest backup (or pass a file; -y skips confirmation)
./restore-prod-db.sh [-y] [backups/prod-data-<timestamp>.sql]   # or restore-prod-db.cmd
```

Both scripts load `SUPABASE_PROD_DB_HOST/USERNAME/PASSWORD` from the gitignored `.env.production` via the global `dotenv` CLI. Backups are data-only (schema comes from migrations) and exclude Supabase-managed `auth` tables. Restore runs in a single transaction via Dockerized `psql` — a failure rolls back, leaving prod data unchanged. `backups/` is gitignored (contains user conversation data).
```

(Adjust the nested code fence as needed — use indentation or `~~~` for the outer fence if nesting conflicts.)

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document prod DB backup/restore scripts in CLAUDE.md"
```

---

## Verification checklist (end-to-end)

- [ ] `backups/` ignored by git
- [ ] `backup-prod-db.cmd` and `.sh` both produce non-empty timestamped dumps
- [ ] `restore-prod-db.cmd` with explicit file restores and row counts match pre-restore
- [ ] `restore-prod-db.sh` defaults to newest backup and row counts match
- [ ] Prompt aborts on anything but `yes`; `-y` skips it
- [ ] Missing env vars and missing backups produce clear errors and non-zero exit
