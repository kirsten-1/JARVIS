#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M11-UP] docker compose is required"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[M11-UP] missing .env.prod"
  echo "[M11-UP] create one from template:"
  echo "cp .env.prod.example .env.prod"
  exit 1
fi

set -a
. "${ENV_FILE}"
set +a

echo "[M11-UP] starting production stack ..."
"${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  --env-file "${ENV_FILE}" \
  up -d

echo "[M11-UP] waiting for gateway health ..."
GATEWAY_PORT_VALUE="${GATEWAY_PORT:-8080}"
FRONTEND_PORT_VALUE="${FRONTEND_PORT:-80}"
for i in {1..60}; do
  if curl -fsS "http://localhost:${GATEWAY_PORT_VALUE}/actuator/health" >/dev/null 2>&1; then
    echo "[M11-UP] gateway is healthy"
    echo "[M11-UP] frontend: http://localhost:${FRONTEND_PORT_VALUE}"
    echo "[M11-UP] gateway:  http://localhost:${GATEWAY_PORT_VALUE}"
    exit 0
  fi
  sleep 2
done

echo "[M11-UP] gateway health check timeout"
exit 1
