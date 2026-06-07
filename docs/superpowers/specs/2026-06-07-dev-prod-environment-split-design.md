# Dev/Prod Environment Split Design

**Date:** 2026-06-07  
**Goal:** Separate dev (local Supabase) and prod (supabase.co) environments so OAuth login works from external networks in production, while both environments run simultaneously on the same machine and auto-start on login.

---

## Environment Summary

| | Dev | Prod |
|---|---|---|
| Frontend port | 5177 | 5176 |
| Backend port | 8090 | 8091 |
| Supabase | local (127.0.0.1:54321) | supabase.co |
| External URL | dev.agent.breynisson.org | agent.breynisson.org |
| OAuth (external) | no | yes |
| DB | local (127.0.0.1:54322) | supabase.co Postgres |

---

## 1. Frontend Configuration

### `.env` (dev, existing)
```
VITE_SUPABASE_URL=http://127.0.0.1:54321
VITE_SUPABASE_ANON_KEY=<local default anon key>
VITE_AUTH_REDIRECT_URL=http://localhost:5177
```

### `.env.production` (new)
```
VITE_SUPABASE_URL=https://<ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<prod anon key>
VITE_AUTH_REDIRECT_URL=https://agent.breynisson.org
```

### `vite.config.ts`
Updated to accept `mode` and configure conditionally:
- `server.port`: 5177 (dev) / 5176 (prod)
- `server.proxy['/ai'].target`: `http://localhost:8090` (dev) / `http://localhost:8091` (prod)
- `server.allowedHosts`: `dev.agent.breynisson.org` (dev) / `agent.breynisson.org` (prod)

### `package.json` scripts
```json
"dev":  "vite --mode development",
"prod": "vite --mode production"
```

---

## 2. Backend Configuration

### `application.properties` (shared, existing)
Keeps: LLM API keys, logging config, LangChain4j settings.  
Removes: `server.port`, `spring.datasource.*`, `supabase.*` (moved to profiles).

### `application-dev.properties` (new)
```properties
server.port=8090
spring.datasource.url=jdbc:postgresql://127.0.0.1:54322/postgres
spring.datasource.username=postgres
spring.datasource.password=${SUPABASE_DB_PASSWORD:postgres}
supabase.jwt-secret=${SUPABASE_JWT_SECRET}
supabase.url=http://127.0.0.1:54321
```

### `application-prod.properties` (new)
```properties
server.port=8091
spring.datasource.url=jdbc:postgresql://<supabase-host>:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=${SUPABASE_PROD_DB_PASSWORD}
supabase.jwt-secret=${SUPABASE_PROD_JWT_SECRET}
supabase.url=https://<ref>.supabase.co
```

---

## 3. CORS

`WebConfig.java` allowed origins updated to include:
- `http://localhost:5177`
- `http://127.0.0.1:5177`
- `https://dev.agent.breynisson.org`

Existing entries (`localhost:5176`, `127.0.0.1:5176`, `https://agent.breynisson.org`) stay unchanged.

---

## 4. Startup Scripts

### `C:\Users\Lenovo\start-agent-suite-dev.ps1`
- Launches backend JAR with `--spring.profiles.active=dev`
- Launches `npm run dev` (port 5177, proxy to 8090)
- Both as background processes with output to log files

### `C:\Users\Lenovo\start-agent-suite-prod.ps1`
- Launches backend JAR with `--spring.profiles.active=prod`
- Launches `npm run prod` (port 5176, proxy to 8091)
- Both as background processes with output to log files

Backend uses a pre-built JAR (`target/agent-suite-*.jar`). Rebuild manually with `build.sh` when code changes.

### Windows Startup shortcuts (`C:\Users\Lenovo\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\`)
- `Start Agent Suite Dev.lnk` → `powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Users\Lenovo\start-agent-suite-dev.ps1"`
- `Start Agent Suite Prod.lnk` → `powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File "C:\Users\Lenovo\start-agent-suite-prod.ps1"`

---

## 5. Cloudflared

Already running tunnel. Config updated to route:
- `agent.breynisson.org` → `http://localhost:5176` (existing)
- `dev.agent.breynisson.org` → `http://localhost:5177` (added)

---

## 6. Supabase OAuth (supabase.co project)

In the supabase.co dashboard → Authentication → URL Configuration:
- Site URL: `https://agent.breynisson.org`
- Redirect URLs: add `https://agent.breynisson.org`

In Google Cloud Console → OAuth 2.0 credentials:
- Authorised redirect URIs: add `https://<ref>.supabase.co/auth/v1/callback`

Local dev Supabase does not need external OAuth redirect URLs configured.

---

## 7. Build & Deploy Flow

```
# When code changes:
./build.sh              # kills old processes, builds new JAR

# On login (automatic):
start-agent-suite-dev.ps1   # starts dev env (5177 / 8090)
start-agent-suite-prod.ps1  # starts prod env (5176 / 8091)
```

No Docker, no nginx, no Windows services.
