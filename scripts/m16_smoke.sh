#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M16-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
RUN_AGENT="${RUN_AGENT:-false}"
M16_QUERY="${M16_QUERY:-vector retrieval}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M16-SMOKE] BASE_URL=${BASE_URL}"

if [[ -z "${TOKEN}" && -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
  set -a
  . "${ROOT_DIR}/.env.prod"
  set +a
  JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
  JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
  JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
fi

echo "[1/6] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/6] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M16-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M16-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
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
    echo "[M16-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
fi

echo "[3/6] create knowledge snippets"
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
    echo "[M16-SMOKE] create snippet failed (http=${http_code})"
    cat "${resp_file}"
    rm -f "${resp_file}"
    exit 1
  fi
  cat "${resp_file}"
  rm -f "${resp_file}"
}

CREATE_JSON_1=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"title\": \"M16 Vector Retrieval Guide\",
  \"content\": \"Hybrid retrieval combines keyword retrieval and vector retrieval for stable ranking.\",
  \"tags\": [\"m16\", \"vector\", \"retrieval\"]
}")
WORKSPACE_ID=$(echo "${CREATE_JSON_1}" | jq -r '.data.workspaceId')
SNIPPET_ID_1=$(echo "${CREATE_JSON_1}" | jq -r '.data.id')

CREATE_JSON_2=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"workspaceId\": ${WORKSPACE_ID},
  \"title\": \"M16 Cache Notes\",
  \"content\": \"Cache eviction and session ttl strategy.\",
  \"tags\": [\"cache\", \"ttl\"]
}")
SNIPPET_ID_2=$(echo "${CREATE_JSON_2}" | jq -r '.data.id')

if [[ -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" || -z "${SNIPPET_ID_1}" || "${SNIPPET_ID_1}" == "null" ]]; then
  echo "[M16-SMOKE] failed to parse create snippet response"
  echo "${CREATE_JSON_1}"
  exit 1
fi

echo "[4/6] verify keyword/vector/hybrid search modes"
ENCODED_QUERY=$(printf '%s' "${M16_QUERY}" | jq -sRr @uri)
for MODE in keyword vector hybrid; do
  SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=${MODE}&limit=5" \
    -H "Authorization: Bearer ${TOKEN}")
  SEARCH_CODE=$(echo "${SEARCH_JSON}" | jq -r '.code')
  RETURN_MODE=$(echo "${SEARCH_JSON}" | jq -r '.data.searchMode')
  ITEM_COUNT=$(echo "${SEARCH_JSON}" | jq -r '.data.items | length')
  TOP_TITLE=$(echo "${SEARCH_JSON}" | jq -r '.data.items[0].title // ""')
  if [[ "${SEARCH_CODE}" != "0" ]]; then
    echo "[M16-SMOKE] search failed for mode=${MODE}"
    echo "${SEARCH_JSON}"
    exit 1
  fi
  if [[ "${RETURN_MODE}" != "${MODE}" ]]; then
    echo "[M16-SMOKE] expected searchMode=${MODE}, got ${RETURN_MODE}"
    echo "${SEARCH_JSON}"
    exit 1
  fi
  if [[ "${ITEM_COUNT}" -lt 1 ]]; then
    echo "[M16-SMOKE] expected >=1 items for mode=${MODE}"
    echo "${SEARCH_JSON}"
    exit 1
  fi
  if [[ "${TOP_TITLE}" != *"Vector"* ]]; then
    echo "[M16-SMOKE] expected top title to include Vector for mode=${MODE}"
    echo "${SEARCH_JSON}"
    exit 1
  fi
done

echo "[5/6] optional agent run with hybrid retrieval"
if [[ "${RUN_AGENT}" == "true" ]]; then
  CONV_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"workspaceId\":${WORKSPACE_ID},\"title\":\"M16 Agent Smoke\"}")
  CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
  if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" ]]; then
    echo "[M16-SMOKE] create conversation failed"
    echo "${CONV_JSON}"
    exit 1
  fi
  AGENT_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"message\": \"Please summarize M16 retrieval strategy\",
      \"maxSteps\": 3,
      \"metadata\": {
        \"allowedTools\": [\"workspace_knowledge_search\"],
        \"knowledgeQuery\": \"${M16_QUERY}\",
        \"knowledgeSearchMode\": \"hybrid\"
      }
    }")
  AGENT_CODE=$(echo "${AGENT_JSON}" | jq -r '.code')
  if [[ "${AGENT_CODE}" != "0" ]]; then
    echo "[M16-SMOKE] optional agent run failed"
    echo "${AGENT_JSON}"
    exit 1
  fi
else
  echo "[M16-SMOKE] skipped agent run (RUN_AGENT=false)"
fi

echo "[6/6] cleanup snippets"
for ID in "${SNIPPET_ID_1}" "${SNIPPET_ID_2}"; do
  if [[ -n "${ID}" && "${ID}" != "null" ]]; then
    DELETE_JSON=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${ID}?userId=${USER_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    DELETE_CODE=$(echo "${DELETE_JSON}" | jq -r '.code')
    if [[ "${DELETE_CODE}" != "0" ]]; then
      echo "[M16-SMOKE] cleanup failed for snippetId=${ID}"
      echo "${DELETE_JSON}"
      exit 1
    fi
  fi
done

echo "[M16-SMOKE] workspaceId=${WORKSPACE_ID}, created=[${SNIPPET_ID_1},${SNIPPET_ID_2}]"
echo "[M16-SMOKE] SUCCESS"
