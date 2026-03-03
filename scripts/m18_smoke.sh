#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M18-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
RUN_AGENT="${RUN_AGENT:-false}"
M18_QUERY="${M18_QUERY:-hybrid retrieval ranking}"
OVERRIDE_MAX_CANDIDATES="${OVERRIDE_MAX_CANDIDATES:-50}"
OVERRIDE_KEYWORD_WEIGHT="${OVERRIDE_KEYWORD_WEIGHT:-0.2}"
OVERRIDE_VECTOR_WEIGHT="${OVERRIDE_VECTOR_WEIGHT:-0.8}"
OVERRIDE_HYBRID_MIN_SCORE="${OVERRIDE_HYBRID_MIN_SCORE:-0.25}"
OVERRIDE_VECTOR_MIN_SIMILARITY="${OVERRIDE_VECTOR_MIN_SIMILARITY:-0.4}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M18-SMOKE] BASE_URL=${BASE_URL}"

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
  echo "[M18-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M18-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
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
    echo "[M18-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
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
    echo "[M18-SMOKE] create snippet failed (http=${http_code})"
    cat "${resp_file}"
    rm -f "${resp_file}"
    exit 1
  fi
  cat "${resp_file}"
  rm -f "${resp_file}"
}

CREATE_JSON_1=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"title\": \"M18 Retrieval Policy Override\",
  \"content\": \"Request level override allows hybrid retrieval tuning per query.\",
  \"tags\": [\"m18\", \"hybrid\", \"policy\"]
}")
WORKSPACE_ID=$(echo "${CREATE_JSON_1}" | jq -r '.data.workspaceId')
SNIPPET_ID_1=$(echo "${CREATE_JSON_1}" | jq -r '.data.id')

CREATE_JSON_2=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"workspaceId\": ${WORKSPACE_ID},
  \"title\": \"M18 Cache Notes\",
  \"content\": \"Cache invalidation and ttl strategy.\",
  \"tags\": [\"cache\", \"ttl\"]
}")
SNIPPET_ID_2=$(echo "${CREATE_JSON_2}" | jq -r '.data.id')

if [[ -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" ]]; then
  echo "[M18-SMOKE] failed to parse workspaceId"
  echo "${CREATE_JSON_1}"
  exit 1
fi

echo "[4/6] verify default search policy output"
ENCODED_QUERY=$(printf '%s' "${M18_QUERY}" | jq -sRr @uri)
DEFAULT_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${DEFAULT_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M18-SMOKE] default search failed"
  echo "${DEFAULT_JSON}"
  exit 1
fi
if [[ "$(echo "${DEFAULT_JSON}" | jq -r '.data.overrideApplied')" != "false" ]]; then
  echo "[M18-SMOKE] expected overrideApplied=false for default request"
  echo "${DEFAULT_JSON}"
  exit 1
fi

echo "[5/6] verify request-level override policy output"
OVERRIDE_JSON=$(curl -sS \
  "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&limit=5&maxCandidates=${OVERRIDE_MAX_CANDIDATES}&hybridKeywordWeight=${OVERRIDE_KEYWORD_WEIGHT}&hybridVectorWeight=${OVERRIDE_VECTOR_WEIGHT}&hybridMinScore=${OVERRIDE_HYBRID_MIN_SCORE}&vectorMinSimilarity=${OVERRIDE_VECTOR_MIN_SIMILARITY}" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${OVERRIDE_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M18-SMOKE] override search failed"
  echo "${OVERRIDE_JSON}"
  exit 1
fi
if [[ "$(echo "${OVERRIDE_JSON}" | jq -r '.data.overrideApplied')" != "true" ]]; then
  echo "[M18-SMOKE] expected overrideApplied=true"
  echo "${OVERRIDE_JSON}"
  exit 1
fi
if [[ "$(echo "${OVERRIDE_JSON}" | jq -r '.data.maxCandidates')" != "${OVERRIDE_MAX_CANDIDATES}" ]]; then
  echo "[M18-SMOKE] expected maxCandidates=${OVERRIDE_MAX_CANDIDATES}"
  echo "${OVERRIDE_JSON}"
  exit 1
fi
if ! echo "${OVERRIDE_JSON}" | jq -e --argjson expected "${OVERRIDE_KEYWORD_WEIGHT}" '((.data.keywordWeight - $expected)|if .<0 then -. else . end) < 0.0001' >/dev/null; then
  echo "[M18-SMOKE] keywordWeight mismatch"
  echo "${OVERRIDE_JSON}"
  exit 1
fi
if ! echo "${OVERRIDE_JSON}" | jq -e --argjson expected "${OVERRIDE_VECTOR_WEIGHT}" '((.data.vectorWeight - $expected)|if .<0 then -. else . end) < 0.0001' >/dev/null; then
  echo "[M18-SMOKE] vectorWeight mismatch"
  echo "${OVERRIDE_JSON}"
  exit 1
fi
if ! echo "${OVERRIDE_JSON}" | jq -e --argjson expected "${OVERRIDE_HYBRID_MIN_SCORE}" '((.data.scoreThreshold - $expected)|if .<0 then -. else . end) < 0.0001' >/dev/null; then
  echo "[M18-SMOKE] scoreThreshold mismatch"
  echo "${OVERRIDE_JSON}"
  exit 1
fi

if [[ "${RUN_AGENT}" == "true" ]]; then
  echo "[M18-SMOKE] optional agent run with metadata overrides"
  CONV_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"workspaceId\":${WORKSPACE_ID},\"title\":\"M18 Agent Smoke\"}")
  CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
  AGENT_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"message\": \"Please search m18 policy notes\",
      \"maxSteps\": 3,
      \"metadata\": {
        \"allowedTools\": [\"workspace_knowledge_search\"],
        \"knowledgeQuery\": \"${M18_QUERY}\",
        \"knowledgeSearchMode\": \"hybrid\",
        \"knowledgeSearchOverrides\": {
          \"hybridKeywordWeight\": ${OVERRIDE_KEYWORD_WEIGHT},
          \"hybridVectorWeight\": ${OVERRIDE_VECTOR_WEIGHT},
          \"hybridMinScore\": ${OVERRIDE_HYBRID_MIN_SCORE},
          \"maxCandidates\": ${OVERRIDE_MAX_CANDIDATES}
        }
      }
    }")
  if [[ "$(echo "${AGENT_JSON}" | jq -r '.code')" != "0" ]]; then
    echo "[M18-SMOKE] optional agent run failed"
    echo "${AGENT_JSON}"
    exit 1
  fi
else
  echo "[M18-SMOKE] skipped agent run (RUN_AGENT=false)"
fi

echo "[6/6] cleanup snippets"
for ID in "${SNIPPET_ID_1}" "${SNIPPET_ID_2}"; do
  if [[ -n "${ID}" && "${ID}" != "null" ]]; then
    DELETE_JSON=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${ID}?userId=${USER_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    if [[ "$(echo "${DELETE_JSON}" | jq -r '.code')" != "0" ]]; then
      echo "[M18-SMOKE] cleanup failed for snippetId=${ID}"
      echo "${DELETE_JSON}"
      exit 1
    fi
  fi
done

echo "[M18-SMOKE] workspaceId=${WORKSPACE_ID}, created=[${SNIPPET_ID_1},${SNIPPET_ID_2}]"
echo "[M18-SMOKE] overrides={maxCandidates:${OVERRIDE_MAX_CANDIDATES},keywordWeight:${OVERRIDE_KEYWORD_WEIGHT},vectorWeight:${OVERRIDE_VECTOR_WEIGHT},hybridMinScore:${OVERRIDE_HYBRID_MIN_SCORE}}"
echo "[M18-SMOKE] SUCCESS"
