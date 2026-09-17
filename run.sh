#!/usr/bin/env bash
# Starts the AI CLI Orchestrator server + web UI on http://localhost:47120 (Linux / macOS / WSL).
# Extra arguments are passed to the server, e.g.  ./run.sh --orchestrator.workspace=/path/to/project
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -d web/node_modules ]; then
  echo "[info] web/node_modules not found: serving the bundled web/dist (run 'cd web && npm install' to rebuild the UI)"
fi
exec ./gradlew :server:bootRun -PskipWeb --console=plain --args="$*"
