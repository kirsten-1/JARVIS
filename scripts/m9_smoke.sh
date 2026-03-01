#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M9-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
FAIL_PROVIDER="${FAIL_PROVIDER:-__invalid_provider__}"
FAIL_MODEL="${FAIL_MODEL:-invalid-model}"

echo "[M9-SMOKE] BASE_URL=${BASE_URL}"

TMP_CHAT="$(mktemp)"
trap 'rm -f "${TMP_CHAT}"' EXIT

echo "[1/7] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/7] issue dev token"
TOKEN_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/dev-token" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":${USER_ID},\"role\":\"${ROLE}\"}")
TOKEN=$(echo "${TOKEN_JSON}" | jq -r '.data.token')
if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
  echo "[M9-SMOKE] failed to get token"
  exit 1
fi

echo "[3/7] create conversation (capture workspaceId)"
CONV_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"title\":\"M9 Smoke Conversation\"}")
CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
WORKSPACE_ID=$(echo "${CONV_JSON}" | jq -r '.data.workspaceId')
if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" || -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" ]]; then
  echo "[M9-SMOKE] failed to create conversation or resolve workspaceId"
  exit 1
fi
echo "[M9-SMOKE] conversationId=${CONV_ID}, workspaceId=${WORKSPACE_ID}"

echo "[4/7] trigger one failed chat for metrics seed (expected non-2xx)"
CHAT_HTTP=$(curl -sS -o "${TMP_CHAT}" -w "%{http_code}" -X POST \
  "${BASE_URL}/api/v1/conversations/${CONV_ID}/chat" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"message\":\"M9 metrics seed\",\"provider\":\"${FAIL_PROVIDER}\",\"model\":\"${FAIL_MODEL}\"}")
if [[ "${CHAT_HTTP}" -lt 400 ]]; then
  echo "[M9-SMOKE] expected chat failure but got HTTP ${CHAT_HTTP}"
  cat "${TMP_CHAT}"
  exit 1
fi

echo "[5/7] query metrics overview"
OVERVIEW_JSON=$(curl -fsS \
  "${BASE_URL}/api/v1/metrics/overview?workspaceId=${WORKSPACE_ID}&userId=${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
OVERVIEW_CODE=$(echo "${OVERVIEW_JSON}" | jq -r '.code')
TOTAL_REQUESTS=$(echo "${OVERVIEW_JSON}" | jq -r '.data.totalRequests')
if [[ "${OVERVIEW_CODE}" != "0" ]]; then
  echo "[M9-SMOKE] overview api failed"
  echo "${OVERVIEW_JSON}"
  exit 1
fi
if [[ "${TOTAL_REQUESTS}" -lt 1 ]]; then
  echo "[M9-SMOKE] expected totalRequests >= 1, got ${TOTAL_REQUESTS}"
  echo "${OVERVIEW_JSON}"
  exit 1
fi

echo "[6/7] query provider metrics"
PROVIDERS_JSON=$(curl -fsS \
  "${BASE_URL}/api/v1/metrics/providers?workspaceId=${WORKSPACE_ID}&userId=${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
PROVIDERS_CODE=$(echo "${PROVIDERS_JSON}" | jq -r '.code')
PROVIDER_COUNT=$(echo "${PROVIDERS_JSON}" | jq -r '.data | length')
if [[ "${PROVIDERS_CODE}" != "0" ]]; then
  echo "[M9-SMOKE] providers api failed"
  echo "${PROVIDERS_JSON}"
  exit 1
fi
if [[ "${PROVIDER_COUNT}" -lt 1 ]]; then
  echo "[M9-SMOKE] expected provider metrics count >= 1, got ${PROVIDER_COUNT}"
  echo "${PROVIDERS_JSON}"
  exit 1
fi

echo "[7/7] summary"
SUCCESS_RATE=$(echo "${OVERVIEW_JSON}" | jq -r '.data.successRate')
FALLBACK_RATE=$(echo "${OVERVIEW_JSON}" | jq -r '.data.fallbackRate')
echo "[M9-SMOKE] totalRequests=${TOTAL_REQUESTS}, providers=${PROVIDER_COUNT}, successRate=${SUCCESS_RATE}, fallbackRate=${FALLBACK_RATE}"
echo "[M9-SMOKE] SUCCESS"
