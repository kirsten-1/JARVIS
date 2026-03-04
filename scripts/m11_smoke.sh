#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  . "${ENV_FILE}"
  set +a
fi

FRONTEND_URL="${FRONTEND_URL:-http://localhost:${FRONTEND_PORT:-80}}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
AISERVICE_URL="${AISERVICE_URL:-http://localhost:${AISERVICE_PORT:-18000}}"

echo "[M11-SMOKE] FRONTEND_URL=${FRONTEND_URL}"
echo "[M11-SMOKE] GATEWAY_URL=${GATEWAY_URL}"

echo "[1/3] gateway health"
curl -fsS "${GATEWAY_URL}/actuator/health" >/dev/null

echo "[2/3] frontend index"
curl -fsS "${FRONTEND_URL}" >/dev/null

echo "[3/3] gateway unauthorized guard"
HTTP_CODE="$(curl -s -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/api/v1/conversations?page=1&size=10")"
if [[ "${HTTP_CODE}" != "401" && "${HTTP_CODE}" != "403" ]]; then
  echo "[M11-SMOKE] expected 401/403, got ${HTTP_CODE}"
  exit 1
fi

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

if is_true "${AI_AISERVICE_COMPOSE_ENABLED:-false}"; then
  echo "[4/4] ai-service health"
  curl -fsS "${AISERVICE_URL}/health" >/dev/null
fi

echo "[M11-SMOKE] SUCCESS"
