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

{
  echo "TRUNCATE public.suite_user, public.conversation, public.message, public.user_role RESTART IDENTITY CASCADE;"
  cat "$file"
} | PGPASSWORD="$SUPABASE_PROD_DB_PASSWORD" docker run --rm -i -e PGPASSWORD postgres:17 \
      psql -h "$SUPABASE_PROD_DB_HOST" -U "$SUPABASE_PROD_DB_USERNAME" -d postgres \
           --single-transaction -v ON_ERROR_STOP=1 -q

echo "Restore complete. Row counts:"
PGPASSWORD="$SUPABASE_PROD_DB_PASSWORD" docker run --rm -e PGPASSWORD postgres:17 \
  psql -h "$SUPABASE_PROD_DB_HOST" -U "$SUPABASE_PROD_DB_USERNAME" -d postgres -t -A -c \
  "SELECT 'suite_user:   ' || count(*) FROM suite_user
   UNION ALL SELECT 'conversation: ' || count(*) FROM conversation
   UNION ALL SELECT 'message:      ' || count(*) FROM message
   UNION ALL SELECT 'user_role:    ' || count(*) FROM user_role;"
