#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M13-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
PROVIDER="${PROVIDER:-}"
MODEL="${MODEL:-}"
MAX_STEPS="${MAX_STEPS:-3}"
MESSAGE="${MESSAGE:-请告诉我现在时间，并给出当前工作区运营指标摘要。}"
TOKEN="${TOKEN:-}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M13-SMOKE] BASE_URL=${BASE_URL}"

if [[ -z "${TOKEN}" && -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
  set -a
  . "${ROOT_DIR}/.env.prod"
  set +a
  JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
  JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
  JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
fi
if [[ -z "${PROVIDER}" && -n "${AI_DEFAULT_PROVIDER:-}" ]]; then
  PROVIDER="${AI_DEFAULT_PROVIDER}"
fi
if [[ -z "${MODEL}" && -n "${PROVIDER}" ]]; then
  PROVIDER_UPPER=$(echo "${PROVIDER}" | tr '[:lower:]' '[:upper:]')
  MODEL_VAR="AI_${PROVIDER_UPPER}_MODEL"
  MODEL="${!MODEL_VAR:-}"
fi

echo "[1/5] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/5] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M13-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M13-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
    exit 1
  fi
  echo "[M13-SMOKE] generating local jwt from JARVIS_JWT_SECRET"
  base64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
  }
  NOW_TS=$(date +%s)
  EXP_TS=$((NOW_TS + JARVIS_JWT_EXPIRE_SECONDS))
  JWT_HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | base64url)
  JWT_PAYLOAD=$(jq -nc \
    --arg sub "${USER_ID}" \
    --arg iss "${JARVIS_JWT_ISSUER}" \
    --arg role "${ROLE}" \
    --argjson iat "${NOW_TS}" \
    --argjson exp "${EXP_TS}" \
    '{sub:$sub,iss:$iss,iat:$iat,exp:$exp,role:$role}' | base64url)
  JWT_SIGN_INPUT="${JWT_HEADER}.${JWT_PAYLOAD}"
  JWT_SIG=$(printf '%s' "${JWT_SIGN_INPUT}" | openssl dgst -binary -sha256 -hmac "${JARVIS_JWT_SECRET}" | base64url)
  TOKEN="${JWT_SIGN_INPUT}.${JWT_SIG}"
else
  DEV_TOKEN_RESP_FILE="$(mktemp)"
  HTTP_CODE=$(curl -sS -o "${DEV_TOKEN_RESP_FILE}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/auth/dev-token" \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":${USER_ID},\"role\":\"${ROLE}\"}" || true)
  if [[ "${HTTP_CODE}" != "200" ]]; then
    echo "[M13-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    echo "[M13-SMOKE] for prod profile, rerun with TOKEN env:"
    echo "TOKEN=<your_jwt> ./scripts/m13_smoke.sh"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
  if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
    echo "[M13-SMOKE] failed to parse token from dev-token response"
    exit 1
  fi
fi

echo "[3/5] create conversation"
CONV_RESP_FILE="$(mktemp)"
CONV_HTTP=$(curl -sS -o "${CONV_RESP_FILE}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/conversations" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"title\":\"M13 Agent Smoke\"}" || true)
if [[ "${CONV_HTTP}" != "200" ]]; then
  echo "[M13-SMOKE] create conversation failed (http=${CONV_HTTP})"
  cat "${CONV_RESP_FILE}"
  rm -f "${CONV_RESP_FILE}"
  exit 1
fi
CONV_JSON=$(cat "${CONV_RESP_FILE}")
rm -f "${CONV_RESP_FILE}"
CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" ]]; then
  echo "[M13-SMOKE] failed to create conversation"
  echo "${CONV_JSON}"
  exit 1
fi

echo "[4/5] run agent loop"
echo "[M13-SMOKE] provider=${PROVIDER:-<default>}, model=${MODEL:-<default>}, maxSteps=${MAX_STEPS}"
AGENT_PAYLOAD=$(jq -nc \
  --argjson userId "${USER_ID}" \
  --arg message "${MESSAGE}" \
  --arg provider "${PROVIDER}" \
  --arg model "${MODEL}" \
  --argjson maxSteps "${MAX_STEPS}" \
  '{
    userId: $userId,
    message: $message,
    maxSteps: $maxSteps
  }
  + (if ($provider | length) > 0 then {provider: $provider} else {} end)
  + (if ($model | length) > 0 then {model: $model} else {} end)')
AGENT_RESP_FILE="$(mktemp)"
AGENT_HTTP=$(curl -sS -o "${AGENT_RESP_FILE}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "${AGENT_PAYLOAD}" || true)
if [[ "${AGENT_HTTP}" != "200" ]]; then
  echo "[M13-SMOKE] agent api failed (http=${AGENT_HTTP})"
  cat "${AGENT_RESP_FILE}"
  rm -f "${AGENT_RESP_FILE}"
  exit 1
fi
AGENT_JSON=$(cat "${AGENT_RESP_FILE}")
rm -f "${AGENT_RESP_FILE}"
AGENT_CODE=$(echo "${AGENT_JSON}" | jq -r '.code')
ASSISTANT=$(echo "${AGENT_JSON}" | jq -r '.data.assistantContent')
STEP_COUNT=$(echo "${AGENT_JSON}" | jq -r '.data.steps | length')

if [[ "${AGENT_CODE}" != "0" ]]; then
  echo "[M13-SMOKE] agent api failed"
  echo "${AGENT_JSON}"
  exit 1
fi
if [[ -z "${ASSISTANT}" || "${ASSISTANT}" == "null" ]]; then
  echo "[M13-SMOKE] assistant content is empty"
  echo "${AGENT_JSON}"
  exit 1
fi

echo "[5/5] verify agent steps"
if [[ "${STEP_COUNT}" -lt 1 ]]; then
  echo "[M13-SMOKE] expected at least one agent step, got ${STEP_COUNT}"
  echo "${AGENT_JSON}"
  exit 1
fi

echo "[M13-SMOKE] conversationId=${CONV_ID}, steps=${STEP_COUNT}"
echo "[M13-SMOKE] SUCCESS"
