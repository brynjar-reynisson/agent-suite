# Build & Run

## Commands

```bash
# Build JAR, restart dev servers (kills dev ports only, leaves prod untouched)
./build.sh        # or build.cmd on Windows

# Promote to prod (freeze JAR to release/, build frontend dist/, restart prod servers)
./promote.sh      # or promote.cmd on Windows

# Test
./mvnw test

# Single test class
./mvnw test -Dtest=AgentSuiteApplicationTests
```

## Environments

| | Dev | Prod |
|---|---|---|
| Frontend | `http://localhost:5177` | `http://localhost:5176` → `https://agent.breynisson.org` |
| Backend | `http://localhost:8090` | `http://localhost:8091` |
| Supabase | local (no external OAuth) | supabase.co (OAuth enabled) |

**Prod artifacts:** JAR is frozen in `release/` (copied from `target/` by `promote.*`); frontend is a static build in `frontend/dist/` served by `vite preview`. Neither updates unless you explicitly promote.

## Environment Variables

**Required (dev):** `DEEPSEEK_API_KEY`, `SUPABASE_JWT_SECRET`  
No baked-in fallback — startup fails fast if unset. Set to the local Supabase JWT secret (the dev startup script provides it).

**Required (prod):** `DEEPSEEK_API_KEY`, `SUPABASE_PROD_DB_HOST`, `SUPABASE_PROD_DB_PASSWORD`, `SUPABASE_PROD_JWT_SECRET`, `SUPABASE_PROD_URL`, `SPRING_PROFILES_ACTIVE=prod`

**Optional:** `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `MISTRAL_AI_API_KEY`, `BRAVE_SEARCH_API_KEY`

## Auto-start

Dev and prod servers auto-start on Windows login via:
- `C:\Users\Lenovo\start-agent-suite-dev.ps1`
- `C:\Users\Lenovo\start-agent-suite-prod.ps1`

(Windows Startup folder shortcuts)

## Frontend Logging

| Environment | Log file |
|-------------|----------|
| Dev (port 5177) | `logs/frontend-dev-access.log` |
| Prod/preview (port 5176) | `logs/frontend-prod-access.log` |

Morgan `combined` format. Append-mode — not truncated on restart. `logs/` is gitignored.
