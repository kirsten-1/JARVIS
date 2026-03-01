#!/usr/bin/env bash
set -euo pipefail

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M10-DOWN] docker compose is required"
  exit 1
fi

if [[ "${1:-}" == "--volumes" ]]; then
  echo "[M10-DOWN] stopping and removing containers + volumes ..."
  "${DC[@]}" down -v
else
  echo "[M10-DOWN] stopping and removing containers ..."
  "${DC[@]}" down
fi
