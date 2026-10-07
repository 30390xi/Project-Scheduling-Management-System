#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT/frontend"
if [[ ! -d node_modules ]]; then
  npm ci
fi
exec npm run dev
