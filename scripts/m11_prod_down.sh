#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M11-DOWN] docker compose is required"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[M11-DOWN] missing .env.prod, fallback to .env.prod.example values"
  ENV_FILE="${ROOT_DIR}/.env.prod.example"
fi

if [[ "${1:-}" == "--volumes" ]]; then
  echo "[M11-DOWN] stopping stack and removing volumes ..."
  "${DC[@]}" \
    -f "${ROOT_DIR}/docker-compose.prod.yml" \
    --env-file "${ENV_FILE}" \
    down -v
else
  echo "[M11-DOWN] stopping stack ..."
  "${DC[@]}" \
    -f "${ROOT_DIR}/docker-compose.prod.yml" \
    --env-file "${ENV_FILE}" \
    down
fi
