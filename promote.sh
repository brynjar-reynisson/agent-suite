#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

echo "Killing prod servers..."
npx kill-port 8091 5176 2>/dev/null || true

echo "Copying JAR to release/..."
mkdir -p release
rm -f release/agent-suite-*.jar
cp target/agent-suite-*.jar release/

echo "Building frontend for prod..."
cd frontend && npm run build:prod && cd ..

echo "Restarting prod servers..."
powershell.exe -File "C:\Users\Lenovo\start-agent-suite-prod.ps1"
