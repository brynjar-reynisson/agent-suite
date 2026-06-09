#!/usr/bin/env bash
set -e

echo "Killing dev servers..."
npx kill-port 8090 5177 2>/dev/null || true

echo "Building jar..."
powershell.exe -NoProfile -Command "& '$(pwd -W)\mvnw.cmd' clean package -DskipTests"

echo "Restarting dev servers..."
powershell.exe -File "C:\Users\Lenovo\start-agent-suite-dev.ps1"
