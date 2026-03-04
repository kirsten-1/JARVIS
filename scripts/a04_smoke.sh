#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  . "${ENV_FILE}"
  set +a
fi

BASE_URL="${BASE_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
AISERVICE_URL="${AISERVICE_URL:-http://localhost:${AISERVICE_PORT:-18000}}"
PROVIDER="${PROVIDER:-aiservice}"
MODEL="${MODEL:-${AI_AISERVICE_MODEL:-${AIS_MINIMAX_MODEL:-${AI_MINIMAX_MODEL:-MiniMax-M2.5}}}}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

echo "[A04-SMOKE] BASE_URL=${BASE_URL}"
echo "[A04-SMOKE] PROVIDER=${PROVIDER}"
echo "[A04-SMOKE] MODEL=${MODEL}"

if [[ "${PROVIDER}" != "aiservice" ]]; then
  echo "[A04-SMOKE] warning: PROVIDER is not aiservice"
fi

if ! is_true "${AI_AISERVICE_ENABLED:-false}"; then
  echo "[A04-SMOKE] warning: AI_AISERVICE_ENABLED is false"
fi

echo "[1/3] gateway/frontend/basic guard"
"${ROOT_DIR}/scripts/m11_smoke.sh"

if is_true "${AI_AISERVICE_COMPOSE_ENABLED:-false}"; then
  echo "[2/3] ai-service health"
  curl -fsS "${AISERVICE_URL}/health" >/dev/null
else
  echo "[2/3] ai-service compose health skipped (AI_AISERVICE_COMPOSE_ENABLED=false)"
fi

echo "[3/3] agent loop via aiservice"
BASE_URL="${BASE_URL}" PROVIDER="${PROVIDER}" MODEL="${MODEL}" "${ROOT_DIR}/scripts/m13_smoke.sh"

echo "[A04-SMOKE] SUCCESS"
