#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M19-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
RUN_AGENT="${RUN_AGENT:-false}"
M19_QUERY="${M19_QUERY:-policy override retrieval quality}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M19-SMOKE] BASE_URL=${BASE_URL}"

if [[ -z "${TOKEN}" && -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
  set -a
  . "${ROOT_DIR}/.env.prod"
  set +a
  JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
  JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
  JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
fi

echo "[1/7] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/7] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M19-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M19-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
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
    echo "[M19-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
fi

echo "[3/7] create knowledge snippets"
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
    echo "[M19-SMOKE] create snippet failed (http=${http_code})"
    cat "${resp_file}"
    rm -f "${resp_file}"
    exit 1
  fi
  cat "${resp_file}"
  rm -f "${resp_file}"
}

CREATE_JSON_1=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"title\": \"M19 Retrieval Feedback Loop\",
  \"content\": \"Feedback driven retrieval policy can shift weight to vector side when helpful rate is higher.\",
  \"tags\": [\"m19\", \"feedback\", \"retrieval\"]
}")
WORKSPACE_ID=$(echo "${CREATE_JSON_1}" | jq -r '.data.workspaceId')
SNIPPET_ID_1=$(echo "${CREATE_JSON_1}" | jq -r '.data.id')

CREATE_JSON_2=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"workspaceId\": ${WORKSPACE_ID},
  \"title\": \"M19 Cache Notes\",
  \"content\": \"Cache ttl and invalidation for message list.\",
  \"tags\": [\"cache\", \"ttl\"]
}")
SNIPPET_ID_2=$(echo "${CREATE_JSON_2}" | jq -r '.data.id')

if [[ -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" ]]; then
  echo "[M19-SMOKE] failed to parse workspace id"
  echo "${CREATE_JSON_1}"
  exit 1
fi

echo "[4/7] baseline search (autoTune=false)"
ENCODED_QUERY=$(printf '%s' "${M19_QUERY}" | jq -sRr @uri)
BASELINE_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&limit=5&autoTune=false" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${BASELINE_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M19-SMOKE] baseline search failed"
  echo "${BASELINE_JSON}"
  exit 1
fi
if [[ "$(echo "${BASELINE_JSON}" | jq -r '.data.overrideApplied')" != "false" ]]; then
  echo "[M19-SMOKE] expected baseline overrideApplied=false"
  echo "${BASELINE_JSON}"
  exit 1
fi

echo "[5/7] submit retrieval feedback samples"
submit_feedback() {
  local helpful="$1"
  local kw="$2"
  local vec="$3"
  local score="$4"
  local candidates="$5"
  local feedback_json
  feedback_json=$(curl -sS -X POST "${BASE_URL}/api/v1/knowledge/retrieval/feedback" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"workspaceId\": ${WORKSPACE_ID},
      \"query\": \"${M19_QUERY}\",
      \"searchMode\": \"hybrid\",
      \"helpful\": ${helpful},
      \"keywordWeight\": ${kw},
      \"vectorWeight\": ${vec},
      \"scoreThreshold\": ${score},
      \"maxCandidates\": ${candidates}
    }")
  if [[ "$(echo "${feedback_json}" | jq -r '.code')" != "0" ]]; then
    echo "[M19-SMOKE] feedback submit failed"
    echo "${feedback_json}"
    exit 1
  fi
}

# Simulate vector-biased positive feedback
submit_feedback true 0.20 0.80 0.24 60
submit_feedback true 0.25 0.75 0.22 55
submit_feedback true 0.30 0.70 0.20 50
submit_feedback false 0.70 0.30 0.10 120

RECO_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/retrieval/recommendation?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&minSamples=3" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${RECO_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M19-SMOKE] recommendation query failed"
  echo "${RECO_JSON}"
  exit 1
fi

FEEDBACK_COUNT=$(echo "${RECO_JSON}" | jq -r '.data.feedbackCount')
REC_KW=$(echo "${RECO_JSON}" | jq -r '.data.suggestedKeywordWeight')
REC_VEC=$(echo "${RECO_JSON}" | jq -r '.data.suggestedVectorWeight')
if [[ "${FEEDBACK_COUNT}" -lt 3 ]]; then
  echo "[M19-SMOKE] expected feedbackCount >= 3"
  echo "${RECO_JSON}"
  exit 1
fi
if ! echo "${RECO_JSON}" | jq -e '.data.suggestedVectorWeight > .data.suggestedKeywordWeight' >/dev/null; then
  echo "[M19-SMOKE] expected vector weight > keyword weight after feedback"
  echo "${RECO_JSON}"
  exit 1
fi

echo "[6/7] verify autoTune search applies recommendation"
AUTO_TUNE_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&limit=5&autoTune=true" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${AUTO_TUNE_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M19-SMOKE] autoTune search failed"
  echo "${AUTO_TUNE_JSON}"
  exit 1
fi
if [[ "$(echo "${AUTO_TUNE_JSON}" | jq -r '.data.overrideApplied')" != "true" ]]; then
  echo "[M19-SMOKE] expected overrideApplied=true with autoTune"
  echo "${AUTO_TUNE_JSON}"
  exit 1
fi
if ! echo "${AUTO_TUNE_JSON}" | jq -e '.data.vectorWeight > .data.keywordWeight' >/dev/null; then
  echo "[M19-SMOKE] expected autoTune result to be vector-biased"
  echo "${AUTO_TUNE_JSON}"
  exit 1
fi

if [[ "${RUN_AGENT}" == "true" ]]; then
  echo "[M19-SMOKE] optional agent run with knowledgeAutoTune=true"
  CONV_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"workspaceId\":${WORKSPACE_ID},\"title\":\"M19 Agent Smoke\"}")
  CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
  AGENT_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"message\": \"Please summarize m19 retrieval loop\",
      \"maxSteps\": 3,
      \"metadata\": {
        \"allowedTools\": [\"workspace_knowledge_search\"],
        \"knowledgeQuery\": \"${M19_QUERY}\",
        \"knowledgeAutoTune\": true
      }
    }")
  if [[ "$(echo "${AGENT_JSON}" | jq -r '.code')" != "0" ]]; then
    echo "[M19-SMOKE] optional agent run failed"
    echo "${AGENT_JSON}"
    exit 1
  fi
else
  echo "[M19-SMOKE] skipped agent run (RUN_AGENT=false)"
fi

echo "[7/7] cleanup snippets"
for ID in "${SNIPPET_ID_1}" "${SNIPPET_ID_2}"; do
  if [[ -n "${ID}" && "${ID}" != "null" ]]; then
    DELETE_JSON=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${ID}?userId=${USER_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    if [[ "$(echo "${DELETE_JSON}" | jq -r '.code')" != "0" ]]; then
      echo "[M19-SMOKE] cleanup failed for snippetId=${ID}"
      echo "${DELETE_JSON}"
      exit 1
    fi
  fi
done

echo "[M19-SMOKE] workspaceId=${WORKSPACE_ID}, created=[${SNIPPET_ID_1},${SNIPPET_ID_2}]"
echo "[M19-SMOKE] recommendation={keywordWeight:${REC_KW},vectorWeight:${REC_VEC},feedbackCount:${FEEDBACK_COUNT}}"
echo "[M19-SMOKE] SUCCESS"
