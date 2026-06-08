#!/usr/bin/env bash
set -e

echo "Killing frontend dev (port 5177)..."
powershell.exe -NoProfile -Command "
  Get-NetTCPConnection -LocalPort 5177 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess |
  ForEach-Object { Stop-Process -Id \$_ -Force -ErrorAction SilentlyContinue }
" 2>/dev/null || true

echo "Killing frontend prod (port 5176)..."
powershell.exe -NoProfile -Command "
  Get-NetTCPConnection -LocalPort 5176 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess |
  ForEach-Object { Stop-Process -Id \$_ -Force -ErrorAction SilentlyContinue }
" 2>/dev/null || true

echo "Killing backend dev (port 8090)..."
powershell.exe -NoProfile -Command "
  Get-NetTCPConnection -LocalPort 8090 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess |
  ForEach-Object { Stop-Process -Id \$_ -Force -ErrorAction SilentlyContinue }
" 2>/dev/null || true

echo "Killing backend prod (port 8091)..."
powershell.exe -NoProfile -Command "
  Get-NetTCPConnection -LocalPort 8091 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess |
  ForEach-Object { Stop-Process -Id \$_ -Force -ErrorAction SilentlyContinue }
" 2>/dev/null || true

echo "Building jar..."
powershell.exe -NoProfile -Command "& '$(pwd -W)\mvnw.cmd' clean package -DskipTests"
