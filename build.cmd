@echo off

echo Killing frontend (port 5176)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :5176 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Killing backend (port 8090)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8090 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo Building jar...
call mvnw.cmd clean package -DskipTests
