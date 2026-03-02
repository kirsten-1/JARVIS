#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M13-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
PROVIDER="${PROVIDER:-glm}"
MODEL="${MODEL:-glm-4.6v-flashx}"
MESSAGE="${MESSAGE:-请告诉我现在时间，并给出当前工作区运营指标摘要。}"
TOKEN="${TOKEN:-}"

echo "[M13-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/5] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/5] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M13-SMOKE] using provided TOKEN"
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
CONV_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"title\":\"M13 Agent Smoke\"}")
CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" ]]; then
  echo "[M13-SMOKE] failed to create conversation"
  exit 1
fi

echo "[4/5] run agent loop"
AGENT_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"message\":\"${MESSAGE}\",\"provider\":\"${PROVIDER}\",\"model\":\"${MODEL}\",\"maxSteps\":3}")
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
