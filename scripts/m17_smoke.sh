#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M17-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
M17_QUERY="${M17_QUERY:-hybrid retrieval ranking}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M17-SMOKE] BASE_URL=${BASE_URL}"

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
  echo "[M17-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M17-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
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
    echo "[M17-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
fi

echo "[3/5] create knowledge snippets"
create_snippet() {
  local payload="$1"
  local resp_file
  resp_file="$(mktemp)"
  local http_code
  http_code=$(curl -sS -o "${resp_file}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/knowledge/snippets" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "${payload}" || true)
  if [[ "${http_code}" != "200" ]]; then
    echo "[M17-SMOKE] create snippet failed (http=${http_code})"
    cat "${resp_file}"
    rm -f "${resp_file}"
    exit 1
  fi
  cat "${resp_file}"
  rm -f "${resp_file}"
}

CREATE_JSON_1=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"title\": \"M17 Hybrid Ranking Guide\",
  \"content\": \"Hybrid retrieval ranks snippets by keyword score and vector similarity with configurable weights.\",
  \"tags\": [\"m17\", \"hybrid\", \"retrieval\"]
}")
WORKSPACE_ID=$(echo "${CREATE_JSON_1}" | jq -r '.data.workspaceId')
SNIPPET_ID_1=$(echo "${CREATE_JSON_1}" | jq -r '.data.id')

CREATE_JSON_2=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"workspaceId\": ${WORKSPACE_ID},
  \"title\": \"M17 Cache Fallback\",
  \"content\": \"Cache invalidation and ttl details for message list caching.\",
  \"tags\": [\"cache\", \"ttl\"]
}")
SNIPPET_ID_2=$(echo "${CREATE_JSON_2}" | jq -r '.data.id')

echo "[4/5] verify response includes effective retrieval tuning"
ENCODED_QUERY=$(printf '%s' "${M17_QUERY}" | jq -sRr @uri)
SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")

SEARCH_CODE=$(echo "${SEARCH_JSON}" | jq -r '.code')
SEARCH_MODE=$(echo "${SEARCH_JSON}" | jq -r '.data.searchMode')
KEYWORD_WEIGHT=$(echo "${SEARCH_JSON}" | jq -r '.data.keywordWeight')
VECTOR_WEIGHT=$(echo "${SEARCH_JSON}" | jq -r '.data.vectorWeight')
SCORE_THRESHOLD=$(echo "${SEARCH_JSON}" | jq -r '.data.scoreThreshold')
ITEM_COUNT=$(echo "${SEARCH_JSON}" | jq -r '.data.items | length')
TOP_TITLE=$(echo "${SEARCH_JSON}" | jq -r '.data.items[0].title // ""')

if [[ "${SEARCH_CODE}" != "0" ]]; then
  echo "[M17-SMOKE] search failed"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ "${SEARCH_MODE}" != "hybrid" ]]; then
  echo "[M17-SMOKE] expected searchMode=hybrid, got ${SEARCH_MODE}"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ "${ITEM_COUNT}" -lt 1 ]]; then
  echo "[M17-SMOKE] expected at least one item"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ "${TOP_TITLE}" != *"M17"* ]]; then
  echo "[M17-SMOKE] expected top hit to include M17"
  echo "${SEARCH_JSON}"
  exit 1
fi
if [[ "${KEYWORD_WEIGHT}" == "null" || "${VECTOR_WEIGHT}" == "null" || "${SCORE_THRESHOLD}" == "null" ]]; then
  echo "[M17-SMOKE] expected keywordWeight/vectorWeight/scoreThreshold in response"
  echo "${SEARCH_JSON}"
  exit 1
fi

echo "[5/5] cleanup snippets"
for ID in "${SNIPPET_ID_1}" "${SNIPPET_ID_2}"; do
  if [[ -n "${ID}" && "${ID}" != "null" ]]; then
    DELETE_JSON=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${ID}?userId=${USER_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    DELETE_CODE=$(echo "${DELETE_JSON}" | jq -r '.code')
    if [[ "${DELETE_CODE}" != "0" ]]; then
      echo "[M17-SMOKE] cleanup failed for snippetId=${ID}"
      echo "${DELETE_JSON}"
      exit 1
    fi
  fi
done

echo "[M17-SMOKE] workspaceId=${WORKSPACE_ID}, created=[${SNIPPET_ID_1},${SNIPPET_ID_2}]"
echo "[M17-SMOKE] tuning={keywordWeight:${KEYWORD_WEIGHT},vectorWeight:${VECTOR_WEIGHT},scoreThreshold:${SCORE_THRESHOLD}}"
echo "[M17-SMOKE] SUCCESS"
