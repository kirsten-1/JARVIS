#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M12-OBS-DOWN] docker compose is required"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  ENV_FILE="${ROOT_DIR}/.env.prod.example"
fi

if [[ "${1:-}" == "--all" ]]; then
  echo "[M12-OBS-DOWN] stopping full stack ..."
  "${DC[@]}" \
    -f "${ROOT_DIR}/docker-compose.prod.yml" \
    -f "${ROOT_DIR}/docker-compose.observability.yml" \
    --env-file "${ENV_FILE}" \
    down
  exit 0
fi

if [[ "${1:-}" == "--volumes" ]]; then
  echo "[M12-OBS-DOWN] stopping full stack and removing volumes ..."
  "${DC[@]}" \
    -f "${ROOT_DIR}/docker-compose.prod.yml" \
    -f "${ROOT_DIR}/docker-compose.observability.yml" \
    --env-file "${ENV_FILE}" \
    down -v
  exit 0
fi

echo "[M12-OBS-DOWN] stopping observability services only ..."
"${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/docker-compose.observability.yml" \
  --env-file "${ENV_FILE}" \
  stop prometheus grafana

"${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/docker-compose.observability.yml" \
  --env-file "${ENV_FILE}" \
  rm -f prometheus grafana >/dev/null 2>&1 || true
