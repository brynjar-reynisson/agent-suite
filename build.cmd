@echo off

echo Killing dev servers...
call npx --yes kill-port 8090 5177

echo Building jar...
call mvnw.cmd clean package -DskipTests
if errorlevel 1 exit /b 1

echo Restarting dev servers...
powershell.exe -File "C:\Users\Lenovo\start-agent-suite-dev.ps1"
echo Restarted dev servers