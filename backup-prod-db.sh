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
if ! npx --yes supabase db dump --linked --data-only --use-copy -f "$out"; then
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
