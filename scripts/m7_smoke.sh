#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M7-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
RUN_AI_SMOKE="${RUN_AI_SMOKE:-false}"
PROVIDER="${PROVIDER:-glm}"
MODEL="${MODEL:-glm-4.6v-flashx}"
MESSAGE="${MESSAGE:-你好，请简单介绍一下你自己。}"

echo "[M7-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/6] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/6] issue dev token"
TOKEN_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/dev-token" \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":${USER_ID},\"role\":\"${ROLE}\"}")
TOKEN=$(echo "${TOKEN_JSON}" | jq -r '.data.token')
if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
  echo "[M7-SMOKE] failed to get token"
  exit 1
fi

echo "[3/6] create conversation"
CONV_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"title\":\"M7 Smoke Conversation\"}")
CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" ]]; then
  echo "[M7-SMOKE] failed to create conversation"
  exit 1
fi

echo "[4/6] append message"
curl -fsS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/messages" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{\"userId\":${USER_ID},\"role\":\"USER\",\"content\":\"M7 smoke append message\"}" >/dev/null

echo "[5/6] list messages"
MSGS_JSON=$(curl -fsS "${BASE_URL}/api/v1/conversations/${CONV_ID}/messages?userId=${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
MSG_COUNT=$(echo "${MSGS_JSON}" | jq '.data | length')
if [[ "${MSG_COUNT}" -lt 1 ]]; then
  echo "[M7-SMOKE] expected at least one message"
  exit 1
fi

echo "[6/6] list conversations"
LIST_JSON=$(curl -fsS "${BASE_URL}/api/v1/conversations?userId=${USER_ID}&page=0&size=10" \
  -H "Authorization: Bearer ${TOKEN}")
TOTAL=$(echo "${LIST_JSON}" | jq -r '.data.totalElements')
echo "[M7-SMOKE] total conversations: ${TOTAL}"

if [[ "${RUN_AI_SMOKE}" == "true" ]]; then
  echo "[AI] sync chat"
  CHAT_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/chat" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"message\":\"${MESSAGE}\",\"provider\":\"${PROVIDER}\",\"model\":\"${MODEL}\"}")
  ASSISTANT=$(echo "${CHAT_JSON}" | jq -r '.data.assistantContent')
  if [[ -z "${ASSISTANT}" || "${ASSISTANT}" == "null" ]]; then
    echo "[M7-SMOKE] sync chat returned empty assistant content"
    exit 1
  fi
  echo "[AI] sync chat ok, assistant length=$(echo -n "${ASSISTANT}" | wc -m | tr -d ' ')"
fi

echo "[M7-SMOKE] SUCCESS"
