@echo off
rem Starts the AI CLI Orchestrator server + web UI on http://localhost:47120 (Windows).
rem Extra arguments are passed to the server, e.g.  run.cmd --orchestrator.workspace=C:\dev\my-service
setlocal
cd /d "%~dp0"
if not exist "web\node_modules" (
  echo [info] web\node_modules not found: serving the bundled web\dist ^(run "cd web ^&^& npm install" to rebuild the UI^)
)
call gradlew.bat :server:bootRun -PskipWeb --console=plain --args="%*"
endlocal
