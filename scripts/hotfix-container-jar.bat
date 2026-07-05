@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Run from the repository root regardless of where the script lives.
cd /d "%~dp0.."

set "HEALTH_URL=http://localhost:8080/actuator/health"
set "WAIT_SECONDS=30"

if /I "%~1"=="/?" goto :usage
if /I "%~1"=="-h" goto :usage
if /I "%~1"=="--help" goto :usage

set "TARGET_ARG=%~1"
set "BUILD_DIR=%~2"
if not defined BUILD_DIR set "BUILD_DIR=build-runtime-fix"

set "JAR_PATH=%BUILD_DIR%\libs\telegram-mcp-server.jar"
set "BACKUP_DIR=%TEMP%\telegram-mcp-hotfix-backups"
set "BACKUP_STATUS=none"

echo.
echo [1/7] Resolving target container...
call :resolve_container "%TARGET_ARG%"
if errorlevel 1 goto :fail
echo      Using container: !RESOLVED_CONTAINER!

echo.
echo [2/7] Building bootJar into %BUILD_DIR%...
set "KTM_BUILD_DIR=%BUILD_DIR%"
call gradlew.bat bootJar -x test --no-daemon
if errorlevel 1 goto :fail

if not exist "%JAR_PATH%" (
  echo ERROR: Built JAR not found: %JAR_PATH%
  goto :fail
)

if not exist "%BACKUP_DIR%" mkdir "%BACKUP_DIR%"
set "BACKUP_JAR=%BACKUP_DIR%\!RESOLVED_CONTAINER!-original-app.jar"

call :ensure_backup

echo.
echo [4/7] Copying new JAR into container...
docker cp "%JAR_PATH%" "!RESOLVED_CONTAINER!:/app/app.jar"
if errorlevel 1 goto :fail

echo.
echo [5/7] Restarting container...
docker restart "!RESOLVED_CONTAINER!"
if errorlevel 1 goto :fail

echo.
echo [6/7] Waiting for health endpoint inside container (up to %WAIT_SECONDS%s)...
call :wait_for_health
if errorlevel 1 goto :fail

echo.
echo [7/7] Hotfix applied successfully.
if /I "!BACKUP_STATUS!"=="saved" echo      Original backup saved to: %BACKUP_JAR%
if /I "!BACKUP_STATUS!"=="existing" echo      Original backup already existed: %BACKUP_JAR%
if /I "!BACKUP_STATUS!"=="failed" echo      WARNING: Original backup was not created due to docker cp error.
goto :eof

:wait_for_health
powershell -NoProfile -Command ^
  "$ErrorActionPreference='SilentlyContinue'; " ^
  "$maxAttempts=%WAIT_SECONDS%; " ^
  "$url='%HEALTH_URL%'; " ^
  "for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) { " ^
  "  try { " ^
  "    $result = docker exec '!RESOLVED_CONTAINER!' sh -lc 'curl -fsS %HEALTH_URL%' 2>$null; " ^
  "    if ($LASTEXITCODE -eq 0 -and $result) { Write-Host $result; exit 0 } " ^
  "  } catch {} " ^
  "  Start-Sleep -Seconds 1; " ^
  "} " ^
  "Write-Host 'ERROR: Health endpoint did not become ready within %WAIT_SECONDS%s.'; exit 1"
exit /b %errorlevel%

:resolve_container
set "RESOLVED_CONTAINER="
set "CANDIDATE="
set "COMPOSE_CONTAINER="
set "MATCH_COUNT=0"

if not "%~1"=="" (
  for /f %%I in ('docker inspect --format "{{.Name}}" "%~1" 2^>nul') do set "RESOLVED_CONTAINER=%%I"
  if defined RESOLVED_CONTAINER goto :normalize_container
  echo ERROR: Container not found for name or ID: %~1
  exit /b 1
)

if exist "docker-compose.yml" (
  for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "$match = Get-Content 'docker-compose.yml' | Select-String '^[ ]{4}container_name:[ ]*(.+)$' | Select-Object -First 1; if ($match) { $match.Matches[0].Groups[1].Value.Trim() }"`) do set "COMPOSE_CONTAINER=%%I"
  if defined COMPOSE_CONTAINER (
    for /f %%I in ('docker inspect --format "{{.Name}}" "!COMPOSE_CONTAINER!" 2^>nul') do set "RESOLVED_CONTAINER=%%I"
    if defined RESOLVED_CONTAINER goto :normalize_container
  )
)

for /f "tokens=1 delims=|" %%I in ('docker ps --format "{{.Names}}|{{.Image}}" ^| findstr /i /c:"telegram-mcp-tdlib"') do (
  set /a MATCH_COUNT+=1
  set "CANDIDATE=%%I"
)

if "!MATCH_COUNT!"=="1" (
  set "RESOLVED_CONTAINER=!CANDIDATE!"
  goto :normalize_container
)

if "!MATCH_COUNT!"=="0" (
  echo ERROR: No running container matched image name telegram-mcp-tdlib.
) else (
  echo ERROR: Multiple running containers matched telegram-mcp-tdlib. Pass container name or ID explicitly.
)
exit /b 1

:normalize_container
if "!RESOLVED_CONTAINER:~0,1!"=="/" set "RESOLVED_CONTAINER=!RESOLVED_CONTAINER:~1!"
if "!RESOLVED_CONTAINER:~-1!"=="^" set "RESOLVED_CONTAINER=!RESOLVED_CONTAINER:~0,-1!"
exit /b 0

:ensure_backup
echo.
if exist "!BACKUP_JAR!" goto :ensure_backup_exists

echo [3/7] Saving one-time original /app/app.jar to:
echo      !BACKUP_JAR!
docker cp "!RESOLVED_CONTAINER!:/app/app.jar" "!BACKUP_JAR!"
if errorlevel 1 (
  echo WARNING: Backup failed ^(docker cp^). Continuing hotfix without backup.
  echo          You can still restore by rebuilding the original JAR from this repo.
  set "BACKUP_STATUS=failed"
  exit /b 0
)

set "BACKUP_STATUS=saved"
exit /b 0

:ensure_backup_exists
echo [3/7] Original backup already exists:
echo      !BACKUP_JAR!
set "BACKUP_STATUS=existing"
exit /b 0

:usage
if not defined BACKUP_DIR set "BACKUP_DIR=%TEMP%\telegram-mcp-hotfix-backups"
echo Usage:
echo   %~nx0 [container-name-or-id] [build-dir]
echo.
echo Defaults:
echo   container-name-or-id: auto-resolve from docker-compose.yml or running image
echo   build-dir: build-runtime-fix
echo.
echo Examples:
echo   %~nx0
echo   %~nx0 bb3c5c842a80395d1da3d7cf8f6c553e76866c70966f79fdffecf11ef93a830c
echo   %~nx0 telegram-mcp-server build-runtime-fix
echo.
echo Notes:
echo   One-time backup path: %BACKUP_DIR%\CONTAINER-original-app.jar
echo   If backup copy fails, hotfix continues anyway.
echo   Waits up to %WAIT_SECONDS%s for %HEALTH_URL% after restart.
exit /b 0

:fail
echo.
echo Hotfix failed.
exit /b 1
