@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Run from the repository root regardless of where the script lives.
cd /d "%~dp0.."

set "BACKUP_DIR=%TEMP%\telegram-mcp-hotfix-backups"
set "HEALTH_URL=http://localhost:8080/actuator/health"
set "WAIT_SECONDS=30"

if /I "%~1"=="/?" goto :usage
if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage

set "TARGET_ARG=%~1"
set "BACKUP_ARG=%~2"

echo.
echo [1/5] Resolving target container...
call :resolve_container "%TARGET_ARG%"
if errorlevel 1 goto :fail
echo      Using container: !RESOLVED_CONTAINER! [!RESOLVED_CONTAINER_ID!]

echo.
echo [2/5] Resolving backup JAR...
set "BACKUP_JAR="
if defined BACKUP_ARG (
    set "BACKUP_JAR=%BACKUP_ARG%"
) else (
    if exist "!BACKUP_DIR!\!RESOLVED_CONTAINER!-original-app.jar" (
        set "BACKUP_JAR=!BACKUP_DIR!\!RESOLVED_CONTAINER!-original-app.jar"
    ) else (
        for /f "delims=" %%I in ('dir /b /a:-d /o-d "!BACKUP_DIR!\!RESOLVED_CONTAINER!-*-app.jar" 2^>nul') do (
            if not defined BACKUP_JAR set "BACKUP_JAR=!BACKUP_DIR!\%%I"
        )
    )
)

if not defined BACKUP_JAR (
    echo ERROR: No backup JAR found for !RESOLVED_CONTAINER! in %BACKUP_DIR%
    goto :fail
)

if not exist "!BACKUP_JAR!" (
    echo ERROR: Backup JAR not found: !BACKUP_JAR!
    goto :fail
)

echo      Using backup: !BACKUP_JAR!

echo.
echo [3/5] Restoring backup into container...
docker cp "!BACKUP_JAR!" "!RESOLVED_CONTAINER_ID!:/app/app.jar"
if errorlevel 1 goto :fail

echo.
echo [4/5] Restarting container...
docker restart "!RESOLVED_CONTAINER_ID!"
if errorlevel 1 goto :fail

echo.
echo [5/5] Waiting for health endpoint inside container (up to %WAIT_SECONDS%s)...
call :wait_for_health
if errorlevel 1 goto :fail

echo.
echo Restore completed successfully.
goto :eof

:wait_for_health
powershell -NoProfile -Command ^
    "$ErrorActionPreference='SilentlyContinue'; " ^
    "$maxAttempts=%WAIT_SECONDS%; " ^
    "$url='%HEALTH_URL%'; " ^
    "for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) { " ^
    "  try { " ^
    "    $result = docker exec '!RESOLVED_CONTAINER_ID!' sh -lc \"curl -fsS $url\" 2>$null; " ^
    "    if ($LASTEXITCODE -eq 0 -and $result) { Write-Host $result; exit 0 } " ^
    "  } catch {} " ^
    "  Start-Sleep -Seconds 1; " ^
    "} " ^
    "Write-Host 'ERROR: Health endpoint did not become ready within %WAIT_SECONDS%s.'; exit 1"
exit /b %errorlevel%

:resolve_container
set "RESOLVED_CONTAINER="
set "RESOLVED_CONTAINER_ID="
set "CANDIDATE="
set "CANDIDATE_ID="
set "COMPOSE_CONTAINER="
set "MATCH_COUNT=0"

if not "%~1"=="" (
    call :resolve_from_target "%~1"
    if not errorlevel 1 goto :normalize_container
    echo ERROR: Container not found for name or ID: %~1
    exit /b 1
)

if exist "docker-compose.yml" (
    for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "$match = Get-Content 'docker-compose.yml' | Select-String '^[ ]{4}container_name:[ ]*(.+)$' | Select-Object -First 1; if ($match) { $match.Matches[0].Groups[1].Value.Trim() }"`) do set "COMPOSE_CONTAINER=%%I"
    if defined COMPOSE_CONTAINER (
        call :resolve_from_target "!COMPOSE_CONTAINER!"
        if not errorlevel 1 goto :normalize_container
    )
)

for /f "tokens=1-3 delims=|" %%I in ('docker ps -a --format "{{.ID}}|{{.Names}}|{{.Image}}" ^| findstr /i /c:"telegram-mcp-tdlib"') do (
    set /a MATCH_COUNT+=1
    set "CANDIDATE_ID=%%I"
    set "CANDIDATE=%%J"
)

if "!MATCH_COUNT!"=="1" (
    set "RESOLVED_CONTAINER_ID=!CANDIDATE_ID!"
    set "RESOLVED_CONTAINER=!CANDIDATE!"
    goto :normalize_container
)

if "!MATCH_COUNT!"=="0" (
    echo ERROR: No container matched image name telegram-mcp-tdlib.
) else (
    echo ERROR: Multiple containers matched telegram-mcp-tdlib. Pass container name or ID explicitly.
)
exit /b 1

:resolve_from_target
set "RESOLVED_CONTAINER="
set "RESOLVED_CONTAINER_ID="
for /f "usebackq delims=" %%I in (`docker inspect --format "{{.Id}}" "%~1" 2^>nul`) do set "RESOLVED_CONTAINER_ID=%%I"
if not defined RESOLVED_CONTAINER_ID exit /b 1
for /f "usebackq delims=" %%I in (`docker inspect --format "{{.Name}}" "%~1" 2^>nul`) do set "RESOLVED_CONTAINER=%%I"
if not defined RESOLVED_CONTAINER set "RESOLVED_CONTAINER=%~1"
exit /b 0

:normalize_container
if "!RESOLVED_CONTAINER:~0,1!"=="/" set "RESOLVED_CONTAINER=!RESOLVED_CONTAINER:~1!"
if "!RESOLVED_CONTAINER:~-1!"=="^" set "RESOLVED_CONTAINER=!RESOLVED_CONTAINER:~0,-1!"
if not defined RESOLVED_CONTAINER set "RESOLVED_CONTAINER=!RESOLVED_CONTAINER_ID!"
exit /b 0

:usage
echo Usage:
echo   %~nx0 [container-name-or-id] [backup-jar]
echo.
echo Defaults:
echo   container-name-or-id: auto-resolve from docker-compose.yml or running image
echo   backup-jar: latest matching backup in %BACKUP_DIR%
echo.
echo Examples:
echo   %~nx0
echo   %~nx0 bb3c5c842a80395d1da3d7cf8f6c553e76866c70966f79fdffecf11ef93a830c
echo   %~nx0 telegram-mcp-server C:\path\to\backup.jar
echo.
echo Notes:
echo   Waits up to %WAIT_SECONDS%s for %HEALTH_URL% after restart.
exit /b 0

:fail
echo.
echo Restore failed.
exit /b 1
