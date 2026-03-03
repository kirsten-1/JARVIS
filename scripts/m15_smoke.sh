#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M15-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
RUN_AGENT="${RUN_AGENT:-false}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M15-SMOKE] BASE_URL=${BASE_URL}"

if [[ -z "${TOKEN}" && -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
  set -a
  . "${ROOT_DIR}/.env.prod"
  set +a
  JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
  JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
  JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
fi

echo "[1/5] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/5] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M15-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M15-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
    exit 1
  fi
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
    echo "[M15-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
fi

echo "[3/5] create knowledge snippet"
CREATE_RESP_FILE="$(mktemp)"
CREATE_HTTP=$(curl -sS -o "${CREATE_RESP_FILE}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/knowledge/snippets" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"title\": \"M15 知识片段\",
    \"content\": \"RAG 最小闭环包含文档入库、检索与 Agent 工具调用。\",
    \"tags\": [\"m15\", \"rag\", \"knowledge\"]
  }" || true)
if [[ "${CREATE_HTTP}" != "200" ]]; then
  echo "[M15-SMOKE] create snippet failed (http=${CREATE_HTTP})"
  cat "${CREATE_RESP_FILE}"
  rm -f "${CREATE_RESP_FILE}"
  exit 1
fi
CREATE_JSON=$(cat "${CREATE_RESP_FILE}")
rm -f "${CREATE_RESP_FILE}"
WORKSPACE_ID=$(echo "${CREATE_JSON}" | jq -r '.data.workspaceId')
SNIPPET_ID=$(echo "${CREATE_JSON}" | jq -r '.data.id')
if [[ -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" || -z "${SNIPPET_ID}" || "${SNIPPET_ID}" == "null" ]]; then
  echo "[M15-SMOKE] failed to parse created snippet"
  echo "${CREATE_JSON}"
  exit 1
fi

echo "[4/7] update knowledge snippet"
UPDATE_RESP_FILE="$(mktemp)"
UPDATE_HTTP=$(curl -sS -o "${UPDATE_RESP_FILE}" -w "%{http_code}" -X PUT "${BASE_URL}/api/v1/knowledge/snippets/${SNIPPET_ID}" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"title\": \"M15 知识片段（更新）\",
    \"content\": \"RAG 最小闭环包含文档入库、检索、Agent 工具调用与结果归并。\",
    \"tags\": [\"m15\", \"rag\", \"update\"]
  }" || true)
if [[ "${UPDATE_HTTP}" != "200" ]]; then
  echo "[M15-SMOKE] update snippet failed (http=${UPDATE_HTTP})"
  cat "${UPDATE_RESP_FILE}"
  rm -f "${UPDATE_RESP_FILE}"
  exit 1
fi
rm -f "${UPDATE_RESP_FILE}"

echo "[5/7] search knowledge snippets"
SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=RAG&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")
SEARCH_CODE=$(echo "${SEARCH_JSON}" | jq -r '.code')
ITEM_COUNT=$(echo "${SEARCH_JSON}" | jq -r '.data.items | length')
UPDATED_TITLE=$(echo "${SEARCH_JSON}" | jq -r --arg sid "${SNIPPET_ID}" '.data.items[] | select((.id|tostring)==$sid) | .title' | head -n 1)
if [[ "${SEARCH_CODE}" != "0" ]]; then
  echo "[M15-SMOKE] search api failed"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ "${ITEM_COUNT}" -lt 1 ]]; then
  echo "[M15-SMOKE] expected >=1 search item"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ -z "${UPDATED_TITLE}" || "${UPDATED_TITLE}" != *"更新"* ]]; then
  echo "[M15-SMOKE] expected updated snippet to appear in search result"
  echo "${SEARCH_JSON}"
  exit 1
fi

echo "[6/7] delete knowledge snippet"
DELETE_RESP=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${SNIPPET_ID}?userId=${USER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
DELETE_CODE=$(echo "${DELETE_RESP}" | jq -r '.code')
if [[ "${DELETE_CODE}" != "0" ]]; then
  echo "[M15-SMOKE] delete snippet failed"
  echo "${DELETE_RESP}"
  exit 1
fi

echo "[7/7] optional agent knowledge tool run"
if [[ "${RUN_AGENT}" == "true" ]]; then
  CONV_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"workspaceId\":${WORKSPACE_ID},\"title\":\"M15 Agent Smoke\"}")
  CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
  if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" ]]; then
    echo "[M15-SMOKE] create conversation for agent failed"
    echo "${CONV_JSON}"
    exit 1
  fi
  AGENT_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"message\": \"请基于知识库说明 M15 目标\",
      \"maxSteps\": 3,
      \"metadata\": {
        \"allowedTools\": [\"workspace_knowledge_search\"],
        \"knowledgeQuery\": \"RAG 最小闭环\"
      }
    }")
  AGENT_CODE=$(echo "${AGENT_JSON}" | jq -r '.code')
  if [[ "${AGENT_CODE}" != "0" ]]; then
    echo "[M15-SMOKE] optional agent run failed"
    echo "${AGENT_JSON}"
    exit 1
  fi
else
  echo "[M15-SMOKE] skipped agent run (RUN_AGENT=false)"
fi

echo "[M15-SMOKE] workspaceId=${WORKSPACE_ID}, snippetId=${SNIPPET_ID}, items=${ITEM_COUNT}"
echo "[M15-SMOKE] SUCCESS"
