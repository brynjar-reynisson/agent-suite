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
