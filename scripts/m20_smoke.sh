#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[M20-SMOKE] jq is required. Please install jq first."
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
TOKEN="${TOKEN:-}"
RUN_AGENT="${RUN_AGENT:-false}"
M20_QUERY="${M20_QUERY:-retrieval policy governance}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[M20-SMOKE] BASE_URL=${BASE_URL}"

if [[ -z "${TOKEN}" && -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
  set -a
  . "${ROOT_DIR}/.env.prod"
  set +a
  JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
  JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
  JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
fi

echo "[1/8] health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/8] resolve token"
if [[ -n "${TOKEN}" ]]; then
  echo "[M20-SMOKE] using provided TOKEN"
elif [[ -n "${JARVIS_JWT_SECRET}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[M20-SMOKE] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
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
    echo "[M20-SMOKE] dev-token endpoint unavailable (http=${HTTP_CODE})"
    cat "${DEV_TOKEN_RESP_FILE}"
    rm -f "${DEV_TOKEN_RESP_FILE}"
    exit 1
  fi
  TOKEN=$(jq -r '.data.token' "${DEV_TOKEN_RESP_FILE}")
  rm -f "${DEV_TOKEN_RESP_FILE}"
fi

echo "[3/8] create snippets and resolve workspace"
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
    echo "[M20-SMOKE] create snippet failed (http=${http_code})"
    cat "${resp_file}"
    rm -f "${resp_file}"
    exit 1
  fi
  cat "${resp_file}"
  rm -f "${resp_file}"
}

CREATE_JSON_1=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"title\": \"M20 Policy Governance\",
  \"content\": \"Workspace level retrieval policy supports manual recommend and off modes.\",
  \"tags\": [\"m20\", \"policy\", \"governance\"]
}")
WORKSPACE_ID=$(echo "${CREATE_JSON_1}" | jq -r '.data.workspaceId')
SNIPPET_ID_1=$(echo "${CREATE_JSON_1}" | jq -r '.data.id')

CREATE_JSON_2=$(create_snippet "{
  \"userId\": ${USER_ID},
  \"workspaceId\": ${WORKSPACE_ID},
  \"title\": \"M20 Recommendation Notes\",
  \"content\": \"Feedback can drive vector-biased recommendation in auto tune mode.\",
  \"tags\": [\"m20\", \"recommendation\"]
}")
SNIPPET_ID_2=$(echo "${CREATE_JSON_2}" | jq -r '.data.id')

if [[ -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" ]]; then
  echo "[M20-SMOKE] failed to parse workspaceId"
  echo "${CREATE_JSON_1}"
  exit 1
fi

echo "[4/8] set MANUAL policy and verify override source"
PUT_MANUAL_JSON=$(curl -sS -X PUT "${BASE_URL}/api/v1/knowledge/retrieval/policy" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"workspaceId\": ${WORKSPACE_ID},
    \"mode\": \"MANUAL\",
    \"keywordWeight\": 0.2,
    \"vectorWeight\": 0.8,
    \"hybridMinScore\": 0.2,
    \"maxCandidates\": 66
  }")
if [[ "$(echo "${PUT_MANUAL_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] set MANUAL policy failed"
  echo "${PUT_MANUAL_JSON}"
  exit 1
fi

ENCODED_QUERY=$(printf '%s' "${M20_QUERY}" | jq -sRr @uri)
MANUAL_SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&autoTune=true&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${MANUAL_SEARCH_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] MANUAL search failed"
  echo "${MANUAL_SEARCH_JSON}"
  exit 1
fi
if [[ "$(echo "${MANUAL_SEARCH_JSON}" | jq -r '.data.overrideSource')" != "workspace_manual_policy" ]]; then
  echo "[M20-SMOKE] expected overrideSource=workspace_manual_policy"
  echo "${MANUAL_SEARCH_JSON}"
  exit 1
fi

echo "[5/8] switch RECOMMEND policy and submit feedback"
PUT_RECOMMEND_JSON=$(curl -sS -X PUT "${BASE_URL}/api/v1/knowledge/retrieval/policy" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"workspaceId\": ${WORKSPACE_ID},
    \"mode\": \"RECOMMEND\"
  }")
if [[ "$(echo "${PUT_RECOMMEND_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] set RECOMMEND policy failed"
  echo "${PUT_RECOMMEND_JSON}"
  exit 1
fi

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
      \"query\": \"${M20_QUERY}\",
      \"searchMode\": \"hybrid\",
      \"helpful\": ${helpful},
      \"keywordWeight\": ${kw},
      \"vectorWeight\": ${vec},
      \"scoreThreshold\": ${score},
      \"maxCandidates\": ${candidates}
    }")
  if [[ "$(echo "${feedback_json}" | jq -r '.code')" != "0" ]]; then
    echo "[M20-SMOKE] feedback submit failed"
    echo "${feedback_json}"
    exit 1
  fi
}

submit_feedback true 0.20 0.80 0.20 50
submit_feedback true 0.25 0.75 0.18 55
submit_feedback true 0.30 0.70 0.22 60
submit_feedback false 0.70 0.30 0.10 100

echo "[6/8] verify RECOMMEND auto tune source"
RECOMMEND_SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&autoTune=true&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${RECOMMEND_SEARCH_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] RECOMMEND search failed"
  echo "${RECOMMEND_SEARCH_JSON}"
  exit 1
fi
if [[ "$(echo "${RECOMMEND_SEARCH_JSON}" | jq -r '.data.overrideSource')" != "auto_recommendation" ]]; then
  echo "[M20-SMOKE] expected overrideSource=auto_recommendation"
  echo "${RECOMMEND_SEARCH_JSON}"
  exit 1
fi
if ! echo "${RECOMMEND_SEARCH_JSON}" | jq -e '.data.vectorWeight > .data.keywordWeight' >/dev/null; then
  echo "[M20-SMOKE] expected vector-biased recommendation"
  echo "${RECOMMEND_SEARCH_JSON}"
  exit 1
fi

echo "[7/8] switch OFF policy and verify override disabled"
PUT_OFF_JSON=$(curl -sS -X PUT "${BASE_URL}/api/v1/knowledge/retrieval/policy" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"workspaceId\": ${WORKSPACE_ID},
    \"mode\": \"OFF\"
  }")
if [[ "$(echo "${PUT_OFF_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] set OFF policy failed"
  echo "${PUT_OFF_JSON}"
  exit 1
fi
OFF_SEARCH_JSON=$(curl -sS "${BASE_URL}/api/v1/knowledge/snippets?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&query=${ENCODED_QUERY}&searchMode=hybrid&autoTune=true&limit=5" \
  -H "Authorization: Bearer ${TOKEN}")
if [[ "$(echo "${OFF_SEARCH_JSON}" | jq -r '.code')" != "0" ]]; then
  echo "[M20-SMOKE] OFF search failed"
  echo "${OFF_SEARCH_JSON}"
  exit 1
fi
if [[ "$(echo "${OFF_SEARCH_JSON}" | jq -r '.data.overrideApplied')" != "false" ]]; then
  echo "[M20-SMOKE] expected overrideApplied=false in OFF mode"
  echo "${OFF_SEARCH_JSON}"
  exit 1
fi
if [[ "$(echo "${OFF_SEARCH_JSON}" | jq -r '.data.overrideSource')" != "none" ]]; then
  echo "[M20-SMOKE] expected overrideSource=none in OFF mode"
  echo "${OFF_SEARCH_JSON}"
  exit 1
fi

if [[ "${RUN_AGENT}" == "true" ]]; then
  echo "[M20-SMOKE] optional agent run in RECOMMEND mode"
  curl -sS -X PUT "${BASE_URL}/api/v1/knowledge/retrieval/policy" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"workspaceId\": ${WORKSPACE_ID},
      \"mode\": \"RECOMMEND\"
    }" >/dev/null
  CONV_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{\"userId\":${USER_ID},\"workspaceId\":${WORKSPACE_ID},\"title\":\"M20 Agent Smoke\"}")
  CONV_ID=$(echo "${CONV_JSON}" | jq -r '.data.id')
  AGENT_JSON=$(curl -sS -X POST "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${TOKEN}" \
    -d "{
      \"userId\": ${USER_ID},
      \"message\": \"Please summarize retrieval governance\",
      \"maxSteps\": 3,
      \"metadata\": {
        \"allowedTools\": [\"workspace_knowledge_search\"],
        \"knowledgeQuery\": \"${M20_QUERY}\",
        \"knowledgeAutoTune\": true
      }
    }")
  if [[ "$(echo "${AGENT_JSON}" | jq -r '.code')" != "0" ]]; then
    echo "[M20-SMOKE] optional agent run failed"
    echo "${AGENT_JSON}"
    exit 1
  fi
else
  echo "[M20-SMOKE] skipped agent run (RUN_AGENT=false)"
fi

echo "[8/8] cleanup snippets and restore RECOMMEND policy"
curl -sS -X PUT "${BASE_URL}/api/v1/knowledge/retrieval/policy" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "{
    \"userId\": ${USER_ID},
    \"workspaceId\": ${WORKSPACE_ID},
    \"mode\": \"RECOMMEND\"
  }" >/dev/null || true

for ID in "${SNIPPET_ID_1}" "${SNIPPET_ID_2}"; do
  if [[ -n "${ID}" && "${ID}" != "null" ]]; then
    DELETE_JSON=$(curl -sS -X DELETE "${BASE_URL}/api/v1/knowledge/snippets/${ID}?userId=${USER_ID}" \
      -H "Authorization: Bearer ${TOKEN}")
    if [[ "$(echo "${DELETE_JSON}" | jq -r '.code')" != "0" ]]; then
      echo "[M20-SMOKE] cleanup failed for snippetId=${ID}"
      echo "${DELETE_JSON}"
      exit 1
    fi
  fi
done

echo "[M20-SMOKE] workspaceId=${WORKSPACE_ID}, created=[${SNIPPET_ID_1},${SNIPPET_ID_2}]"
echo "[M20-SMOKE] SUCCESS"
