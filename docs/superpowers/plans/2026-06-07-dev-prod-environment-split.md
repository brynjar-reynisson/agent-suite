# Dev/Prod Environment Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the app into two simultaneously-running environments — dev (local Supabase, port 5177/8090) and prod (supabase.co, port 5176/8091) — both auto-starting on Windows login, with OAuth login working externally only on prod.

**Architecture:** Vite `--mode` flag selects `.env` vs `.env.production` and configures port/proxy. Spring Boot `--spring.profiles.active` flag loads `application-dev.properties` or `application-prod.properties`. Two PowerShell startup scripts launch both environments on login via Windows Startup folder shortcuts.

**Tech Stack:** Vite 8 (mode-based config), Spring Boot 3.5 (profiles), PowerShell (startup scripts), Windows Startup folder (.lnk shortcuts via WScript.Shell)

---

## File Map

| Action | File |
|---|---|
| Modify | `frontend/.env` |
| Create | `frontend/.env.production` |
| Modify | `frontend/vite.config.ts` |
| Modify | `frontend/package.json` |
| Modify | `src/main/resources/application.properties` |
| Create | `src/main/resources/application-dev.properties` |
| Create | `src/main/resources/application-prod.properties` |
| Modify | `src/main/java/com/example/agentsuite/config/WebConfig.java` |
| Modify | `build.sh` |
| Modify | `build.cmd` |
| Create | `C:\Users\Lenovo\start-agent-suite-dev.ps1` |
| Create | `C:\Users\Lenovo\start-agent-suite-prod.ps1` |
| Create | Windows Startup shortcuts (via PowerShell script) |

---

## Task 1: Frontend env files + package.json

**Files:**
- Modify: `frontend/.env`
- Create: `frontend/.env.production`
- Modify: `frontend/package.json`

- [ ] **Step 1: Update `.env` redirect URL to port 5177**

Replace the `VITE_AUTH_REDIRECT_URL` line. Full file content:

```
VITE_SUPABASE_URL=http://127.0.0.1:54321
VITE_SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZS1kZW1vIiwicm9sZSI6ImFub24iLCJleHAiOjE5ODM4MTI5OTZ9.CRXP1A7WOeoJeXxjNni43kdQwgnWNReilDMblYTn_I0
VITE_AUTH_REDIRECT_URL=http://localhost:5177
```

- [ ] **Step 2: Create `frontend/.env.production` (local only — gitignored, never commit)**

This file is listed in `.gitignore`. Create it locally but do not stage or commit it.

Replace `<ref>` with your actual supabase.co project reference (e.g. `abcdefghijklmnop`) and `<prod-anon-key>` with the anon key from Project Settings → API:

```
VITE_SUPABASE_URL=https://<ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<prod-anon-key>
VITE_AUTH_REDIRECT_URL=https://agent.breynisson.org
```

- [ ] **Step 3: Add `prod` script to `frontend/package.json`**

Update the `scripts` section:

```json
"scripts": {
  "dev": "vite --mode development",
  "prod": "vite --mode production",
  "build": "tsc -b && vite build",
  "lint": "eslint .",
  "preview": "vite preview"
},
```

- [ ] **Step 4: Commit (do NOT include `.env.production` — it is gitignored)**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add frontend/.env frontend/package.json
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "feat: add prod npm script and update dev redirect URL to port 5177"
```

---

## Task 2: Update vite.config.ts for mode-based config

**Files:**
- Modify: `frontend/vite.config.ts`

- [ ] **Step 1: Rewrite `vite.config.ts` to switch on mode**

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  const isProd = mode === 'production'
  return {
    plugins: [react()],
    server: {
      port: isProd ? 5176 : 5177,
      allowedHosts: [isProd ? 'agent.breynisson.org' : 'dev.agent.breynisson.org'],
      proxy: {
        '/ai': {
          target: isProd ? 'http://localhost:8091' : 'http://localhost:8090',
          changeOrigin: true,
        },
      },
    },
  }
})
```

- [ ] **Step 2: Verify dev mode starts on port 5177**

```bash
cd C:\Users\Lenovo\IdeaProjects\agent-suite\frontend
npm run dev
```

Expected output includes: `Local: http://localhost:5177/`  
Stop with Ctrl+C.

- [ ] **Step 3: Verify prod mode starts on port 5176**

```bash
npm run prod
```

Expected output includes: `Local: http://localhost:5176/`  
Stop with Ctrl+C.

- [ ] **Step 4: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add frontend/vite.config.ts
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "feat: configure vite for dev/prod mode split (5177/5176)"
```

---

## Task 3: Split Spring Boot config into profiles

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/resources/application-dev.properties`
- Create: `src/main/resources/application-prod.properties`

- [ ] **Step 1: Create `application-dev.properties`**

```properties
server.port=8090
spring.datasource.url=jdbc:postgresql://127.0.0.1:54322/postgres
spring.datasource.username=postgres
spring.datasource.password=${SUPABASE_DB_PASSWORD:postgres}
supabase.jwt-secret=${SUPABASE_JWT_SECRET}
supabase.url=http://127.0.0.1:54321
```

- [ ] **Step 2: Create `application-prod.properties`**

Replace `<supabase-host>` with the DB host from Project Settings → Database → Connection parameters (e.g. `db.<ref>.supabase.co`) and `<ref>` with your project reference:

```properties
server.port=8091
spring.datasource.url=jdbc:postgresql://<supabase-host>:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=${SUPABASE_PROD_DB_PASSWORD}
supabase.jwt-secret=${SUPABASE_PROD_JWT_SECRET}
supabase.url=https://<ref>.supabase.co
```

- [ ] **Step 3: Remove profile-specific props from `application.properties`**

Replace the full file with (removes `server.port`, `spring.datasource.*`, `supabase.*`):

```properties
spring.application.name=agent-suite

langchain4j.open-ai.chat-model.api-key=${DEEPSEEK_API_KEY}
langchain4j.open-ai.chat-model.base-url=https://api.deepseek.com/v1
langchain4j.open-ai.chat-model.model-name=deepseek-v4-pro
langchain4j.open-ai.chat-model.temperature=0.1
langchain4j.open-ai.chat-model.max-tokens=8192
langchain4j.open-ai.chat-model.log-requests=true
langchain4j.open-ai.chat-model.log-responses=true

logging.level.dev.langchain4j=DEBUG
logging.file.name=./logs/agent-suite.log

google.api-key=${GOOGLE_API_KEY:}
anthropic.api-key=${ANTHROPIC_API_KEY:}
mistral.api-key=${MISTRAL_AI_API_KEY:}
brave.api-key=${BRAVE_SEARCH_API_KEY:}
```

Note: `brave.api-key` now has an empty default (`:`) so startup doesn't fail if the env var is absent — web search will simply error at runtime, not at boot.

- [ ] **Step 4: Set `SUPABASE_PROD_DB_PASSWORD` and `SUPABASE_PROD_JWT_SECRET` as Windows user environment variables**

Open PowerShell as the current user (not admin) and run:

```powershell
[System.Environment]::SetEnvironmentVariable("SUPABASE_PROD_DB_PASSWORD", "<your-prod-db-password>", "User")
[System.Environment]::SetEnvironmentVariable("SUPABASE_PROD_JWT_SECRET", "<your-prod-jwt-secret>", "User")
```

These persist across reboots for the current Windows user. The startup scripts inherit them automatically.

- [ ] **Step 5: Run tests to confirm nothing broke**

```bash
cd C:\Users\Lenovo\IdeaProjects\agent-suite
./mvnw test
```

Expected: BUILD SUCCESS. Tests use `src/test/resources/application.properties` which has its own H2 datasource and supabase config — they don't depend on the main profile files.

- [ ] **Step 6: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add src/main/resources/application.properties src/main/resources/application-dev.properties src/main/resources/application-prod.properties
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "feat: split Spring Boot config into dev/prod profiles"
```

---

## Task 4: Update CORS for dev frontend origin

**Files:**
- Modify: `src/main/java/com/example/agentsuite/config/WebConfig.java`

- [ ] **Step 1: Add dev origins to `WebConfig.java`**

```java
package com.example.agentsuite.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:5176", "http://127.0.0.1:5176", "https://agent.breynisson.org",
                        "http://localhost:5177", "http://127.0.0.1:5177", "https://dev.agent.breynisson.org"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./mvnw test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add src/main/java/com/example/agentsuite/config/WebConfig.java
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "feat: add CORS origins for dev environment (5177, dev.agent.breynisson.org)"
```

---

## Task 5: Update build.sh and build.cmd to kill all four ports

**Files:**
- Modify: `build.sh`
- Modify: `build.cmd`

- [ ] **Step 1: Update `build.sh`**

```bash
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
```

- [ ] **Step 2: Update `build.cmd`**

```batch
@echo off

echo Killing frontend dev (port 5177)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5177 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Killing frontend prod (port 5176)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5176 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Killing backend dev (port 8090)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8090 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Killing backend prod (port 8091)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8091 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Building jar...
call mvnw.cmd clean package -DskipTests
```

- [ ] **Step 3: Commit**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add build.sh build.cmd
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "feat: update build scripts to kill all four ports (5176, 5177, 8090, 8091)"
```

---

## Task 6: Create startup PowerShell scripts

**Files:**
- Create: `C:\Users\Lenovo\start-agent-suite-dev.ps1`
- Create: `C:\Users\Lenovo\start-agent-suite-prod.ps1`

These scripts live outside the repo and are not committed to git.

- [ ] **Step 1: Create `C:\Users\Lenovo\start-agent-suite-dev.ps1`**

```powershell
$projectDir = "C:\Users\Lenovo\IdeaProjects\agent-suite"
$jar = Get-ChildItem "$projectDir\target\agent-suite-*.jar" | Select-Object -First 1 -ExpandProperty FullName
$logDir = "C:\Users\Lenovo\logs"
New-Item -ItemType Directory -Force $logDir | Out-Null

# Start backend (dev profile, port 8090)
Start-Process -FilePath "java" `
    -ArgumentList @("-jar", $jar, "--spring.profiles.active=dev") `
    -WorkingDirectory $projectDir `
    -RedirectStandardOutput "$logDir\backend-dev.log" `
    -RedirectStandardError "$logDir\backend-dev-err.log" `
    -WindowStyle Hidden

# Wait for backend to be ready before starting frontend
Start-Sleep -Seconds 15

# Start frontend (dev mode, port 5177)
Start-Process -FilePath "cmd.exe" `
    -ArgumentList @("/c", "npm run dev") `
    -WorkingDirectory "$projectDir\frontend" `
    -RedirectStandardOutput "$logDir\frontend-dev.log" `
    -RedirectStandardError "$logDir\frontend-dev-err.log" `
    -WindowStyle Hidden
```

- [ ] **Step 2: Create `C:\Users\Lenovo\start-agent-suite-prod.ps1`**

```powershell
$projectDir = "C:\Users\Lenovo\IdeaProjects\agent-suite"
$jar = Get-ChildItem "$projectDir\target\agent-suite-*.jar" | Select-Object -First 1 -ExpandProperty FullName
$logDir = "C:\Users\Lenovo\logs"
New-Item -ItemType Directory -Force $logDir | Out-Null

# Start backend (prod profile, port 8091)
Start-Process -FilePath "java" `
    -ArgumentList @("-jar", $jar, "--spring.profiles.active=prod") `
    -WorkingDirectory $projectDir `
    -RedirectStandardOutput "$logDir\backend-prod.log" `
    -RedirectStandardError "$logDir\backend-prod-err.log" `
    -WindowStyle Hidden

# Wait for backend to be ready before starting frontend
Start-Sleep -Seconds 15

# Start frontend (prod mode, port 5176)
Start-Process -FilePath "cmd.exe" `
    -ArgumentList @("/c", "npm run prod") `
    -WorkingDirectory "$projectDir\frontend" `
    -RedirectStandardOutput "$logDir\frontend-prod.log" `
    -RedirectStandardError "$logDir\frontend-prod-err.log" `
    -WindowStyle Hidden
```

- [ ] **Step 3: Test dev startup script manually**

Open PowerShell and run:

```powershell
& "C:\Users\Lenovo\start-agent-suite-dev.ps1"
```

Wait ~30 seconds, then verify:
- `http://localhost:5177` loads the app in a browser
- `http://localhost:8090/ai/config/directories` returns JSON

Check logs at `C:\Users\Lenovo\logs\backend-dev.log` if it doesn't start.

- [ ] **Step 4: Test prod startup script manually**

```powershell
& "C:\Users\Lenovo\start-agent-suite-prod.ps1"
```

Wait ~30 seconds, then verify:
- `http://localhost:5176` loads the app
- `http://localhost:8091/ai/config/directories` returns JSON
- OAuth login at `https://agent.breynisson.org` completes successfully

---

## Task 7: Create Windows Startup shortcuts

These shortcuts launch both scripts silently on Windows login.

- [ ] **Step 1: Run this PowerShell script to create both shortcuts**

Open a PowerShell terminal and paste:

```powershell
$startupDir = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup"
$shell = New-Object -ComObject WScript.Shell

# Dev shortcut
$devShortcut = $shell.CreateShortcut("$startupDir\Start Agent Suite Dev.lnk")
$devShortcut.TargetPath = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$devShortcut.Arguments = '-ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Users\Lenovo\start-agent-suite-dev.ps1"'
$devShortcut.WorkingDirectory = "C:\Users\Lenovo"
$devShortcut.WindowStyle = 7
$devShortcut.Save()

# Prod shortcut
$prodShortcut = $shell.CreateShortcut("$startupDir\Start Agent Suite Prod.lnk")
$prodShortcut.TargetPath = "C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"
$prodShortcut.Arguments = '-ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Users\Lenovo\start-agent-suite-prod.ps1"'
$prodShortcut.WorkingDirectory = "C:\Users\Lenovo"
$prodShortcut.WindowStyle = 7
$prodShortcut.Save()

Write-Host "Shortcuts created in $startupDir"
```

- [ ] **Step 2: Verify shortcuts exist**

```powershell
Get-ChildItem "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\Startup\" | Select-Object Name
```

Expected output includes:
```
Start Agent Suite Dev.lnk
Start Agent Suite Prod.lnk
```

- [ ] **Step 3: Test end-to-end by simulating a login restart**

Kill all four processes:

```powershell
Get-NetTCPConnection -LocalPort 5176,5177,8090,8091 -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess | Sort-Object -Unique |
    ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
```

Then manually trigger both startup scripts:

```powershell
& "C:\Users\Lenovo\start-agent-suite-dev.ps1"
& "C:\Users\Lenovo\start-agent-suite-prod.ps1"
```

Wait ~30 seconds. Verify all four ports are listening:

```powershell
@(5176, 5177, 8090, 8091) | ForEach-Object {
    $conn = Get-NetTCPConnection -LocalPort $_ -ErrorAction SilentlyContinue
    "$_ : $(if ($conn) { 'LISTENING' } else { 'NOT LISTENING' })"
}
```

Expected:
```
5176 : LISTENING
5177 : LISTENING
8090 : LISTENING
8091 : LISTENING
```

- [ ] **Step 4: Update CLAUDE.md Build & Run section**

Replace the Build & Run section in `CLAUDE.md` to reflect the two environments:

```markdown
## Build & Run

```bash
# Build (kills all processes, rebuilds JAR)
./build.sh        # or build.cmd on Windows

# Run dev environment (local Supabase, port 5177 frontend / 8090 backend)
./mvnw spring-boot:run --spring.profiles.active=dev
cd frontend && npm run dev    # port 5177, proxies to 8090

# Run prod environment (supabase.co, port 5176 frontend / 8091 backend)
./mvnw spring-boot:run --spring.profiles.active=prod
cd frontend && npm run prod   # port 5176, proxies to 8091

# Test
./mvnw test

# Single test class
./mvnw test -Dtest=AgentSuiteApplicationTests
```​

Dev server: `http://localhost:5177` (dev) / `http://localhost:5176` (prod).  
Backend: `http://localhost:8090` (dev) / `http://localhost:8091` (prod).  
Requires `DEEPSEEK_API_KEY`. `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `MISTRAL_AI_API_KEY`, and `BRAVE_SEARCH_API_KEY` are optional.  
Prod also requires `SUPABASE_PROD_DB_PASSWORD` and `SUPABASE_PROD_JWT_SECRET` as Windows user environment variables.  
Auto-start on login via `C:\Users\Lenovo\start-agent-suite-dev.ps1` and `start-agent-suite-prod.ps1`.
```

- [ ] **Step 5: Commit CLAUDE.md**

```bash
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" add CLAUDE.md
git -C "C:\Users\Lenovo\IdeaProjects\agent-suite" commit -m "docs: update CLAUDE.md with dev/prod environment split"
```

---

## Supabase Dashboard Checklist (manual, not scripted)

These must be done in the supabase.co dashboard before prod OAuth works:

- [ ] Authentication → URL Configuration → Site URL: `https://agent.breynisson.org`
- [ ] Authentication → URL Configuration → Redirect URLs: add `https://agent.breynisson.org`
- [ ] Google Cloud Console → OAuth 2.0 Client → Authorised redirect URIs: add `https://<ref>.supabase.co/auth/v1/callback`
