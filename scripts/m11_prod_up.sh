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

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

ensure_aiservice_image() {
  local image="${AISERVICE_IMAGE:-jarvis-ai-service:local}"
  local auto_build="${AISERVICE_AUTO_BUILD:-true}"
  local build_context="${AISERVICE_BUILD_CONTEXT:-../../ai-service}"
  local dockerfile="${AISERVICE_DOCKERFILE:-Dockerfile}"
  local resolved_context=""
  local resolved_dockerfile=""

  export AISERVICE_IMAGE="${image}"

  if docker image inspect "${image}" >/dev/null 2>&1; then
    echo "[M11-UP] ai-service image found locally: ${image}"
    return 0
  fi

  if ! is_true "${auto_build}"; then
    echo "[M11-UP] ai-service image not found locally: ${image}"
    echo "[M11-UP] set AISERVICE_AUTO_BUILD=true or pre-build the image."
    return 1
  fi

  if [[ "${build_context}" = /* ]]; then
    resolved_context="${build_context}"
  else
    resolved_context="${ROOT_DIR}/${build_context}"
  fi

  if [[ ! -d "${resolved_context}" ]]; then
    echo "[M11-UP] ai-service build context not found: ${resolved_context}"
    echo "[M11-UP] set AISERVICE_BUILD_CONTEXT to your ai-service directory."
    return 1
  fi

  if [[ "${dockerfile}" = /* ]]; then
    resolved_dockerfile="${dockerfile}"
  else
    resolved_dockerfile="${resolved_context}/${dockerfile}"
  fi

  if [[ ! -f "${resolved_dockerfile}" ]]; then
    echo "[M11-UP] ai-service Dockerfile not found: ${resolved_dockerfile}"
    echo "[M11-UP] set AISERVICE_DOCKERFILE to a valid Dockerfile path."
    return 1
  fi

  echo "[M11-UP] ai-service image missing, building local image ..."
  echo "[M11-UP] docker build -t ${image} -f ${resolved_dockerfile} ${resolved_context}"
  docker build -t "${image}" -f "${resolved_dockerfile}" "${resolved_context}"
}

COMPOSE_WITH_AISERVICE=false
if is_true "${AI_AISERVICE_COMPOSE_ENABLED:-false}"; then
  COMPOSE_WITH_AISERVICE=true

  # Keep gateway->ai-service endpoint stable in compose mode.
  if [[ -z "${AI_AISERVICE_BASE_URL:-}" || "${AI_AISERVICE_BASE_URL}" == "http://host.docker.internal:8000" ]]; then
    export AI_AISERVICE_BASE_URL="http://aiservice:8000"
  fi
  export AI_AISERVICE_ENABLED="${AI_AISERVICE_ENABLED:-true}"

  # Reuse gateway minimax settings if A04-specific values are absent.
  export AIS_MINIMAX_API_KEY="${AIS_MINIMAX_API_KEY:-${AI_MINIMAX_API_KEY:-}}"
  export AIS_MINIMAX_MODEL="${AIS_MINIMAX_MODEL:-${AI_MINIMAX_MODEL:-MiniMax-M2.5}}"

  ensure_aiservice_image
fi

echo "[M11-UP] starting production stack ..."
COMPOSE_CMD=("${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  --env-file "${ENV_FILE}")
if [[ "${COMPOSE_WITH_AISERVICE}" == "true" ]]; then
  COMPOSE_CMD+=(--profile aiservice)
fi
COMPOSE_CMD+=(up -d)
"${COMPOSE_CMD[@]}"

echo "[M11-UP] waiting for gateway health ..."
GATEWAY_PORT_VALUE="${GATEWAY_PORT:-8080}"
FRONTEND_PORT_VALUE="${FRONTEND_PORT:-80}"
GATEWAY_HEALTHY=false
for i in {1..60}; do
  if curl -fsS "http://localhost:${GATEWAY_PORT_VALUE}/actuator/health" >/dev/null 2>&1; then
    GATEWAY_HEALTHY=true
    break
  fi
  sleep 2
done

if [[ "${GATEWAY_HEALTHY}" != "true" ]]; then
  echo "[M11-UP] gateway health check timeout"
  exit 1
fi

echo "[M11-UP] gateway is healthy"
echo "[M11-UP] frontend: http://localhost:${FRONTEND_PORT_VALUE}"
echo "[M11-UP] gateway:  http://localhost:${GATEWAY_PORT_VALUE}"

if [[ "${COMPOSE_WITH_AISERVICE}" == "true" ]]; then
  echo "[M11-UP] waiting for ai-service health ..."
  AISERVICE_PORT_VALUE="${AISERVICE_PORT:-18000}"
  for i in {1..40}; do
    if curl -fsS "http://localhost:${AISERVICE_PORT_VALUE}/health" >/dev/null 2>&1; then
      echo "[M11-UP] ai-service is healthy: http://localhost:${AISERVICE_PORT_VALUE}"
      exit 0
    fi
    sleep 1
  done
  echo "[M11-UP] ai-service health check timeout"
  exit 1
fi

exit 0
