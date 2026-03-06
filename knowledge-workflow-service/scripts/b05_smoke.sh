#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[B05-SMOKE] jq is required"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8091}"

echo "[B05-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/6] health"
curl -fsS "${BASE_URL}/health" >/dev/null

echo "[2/6] create workflow"
WF_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"b05-approval-flow",
    "description":"B05 workflow dag smoke",
    "nodes":[
      {"node_id":"start","node_type":"start","config":{}},
      {"node_id":"draft","node_type":"task","config":{"op":"echo","template":"draft generated","output_key":"draft.message"}},
      {"node_id":"gate","node_type":"condition","config":{"left":"$input.auto_approve","operator":"equals","right":true}},
      {"node_id":"publish","node_type":"task","config":{"op":"set","output_key":"result.status","value":"published"}},
      {"node_id":"manual","node_type":"task","config":{"op":"set","output_key":"result.status","value":"pending_review"}},
      {"node_id":"end","node_type":"end","config":{}}
    ],
    "edges":[
      {"from_node":"start","to_node":"draft"},
      {"from_node":"draft","to_node":"gate"},
      {"from_node":"gate","to_node":"publish","condition":"true"},
      {"from_node":"gate","to_node":"manual","condition":"false"},
      {"from_node":"publish","to_node":"end"},
      {"from_node":"manual","to_node":"end"}
    ],
    "metadata":{"scene":"b05-smoke"}
  }')"
WORKFLOW_ID="$(echo "${WF_RESP}" | jq -r '.workflow_id')"
if [[ -z "${WORKFLOW_ID}" || "${WORKFLOW_ID}" == "null" ]]; then
  echo "[B05-SMOKE] failed to create workflow"
  echo "${WF_RESP}"
  exit 1
fi

echo "[3/6] run workflow (approve=true)"
RUN1_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WORKFLOW_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"auto_approve":true,"request_id":"b05-001"},
    "idempotency_key":"b05-run-approve-001",
    "max_steps":20
  }')"
RUN1_ID="$(echo "${RUN1_RESP}" | jq -r '.run_id')"
RUN1_STATUS="$(echo "${RUN1_RESP}" | jq -r '.status')"
RUN1_RESULT_STATUS="$(echo "${RUN1_RESP}" | jq -r '.final_context.result.status // ""')"
if [[ "${RUN1_STATUS}" != "success" || "${RUN1_RESULT_STATUS}" != "published" ]]; then
  echo "[B05-SMOKE] first run failed"
  echo "${RUN1_RESP}"
  exit 1
fi

echo "[4/6] idempotent replay"
RUN1_REPLAY="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WORKFLOW_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"auto_approve":true,"request_id":"b05-001"},
    "idempotency_key":"b05-run-approve-001",
    "max_steps":20
  }')"
REPLAY_HIT="$(echo "${RUN1_REPLAY}" | jq -r '.idempotent_hit')"
REPLAY_RUN_ID="$(echo "${RUN1_REPLAY}" | jq -r '.run_id')"
if [[ "${REPLAY_HIT}" != "true" || "${REPLAY_RUN_ID}" != "${RUN1_ID}" ]]; then
  echo "[B05-SMOKE] idempotent replay validation failed"
  echo "${RUN1_REPLAY}"
  exit 1
fi

echo "[5/6] run workflow (approve=false)"
RUN2_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WORKFLOW_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"auto_approve":false,"request_id":"b05-002"},
    "idempotency_key":"b05-run-approve-002",
    "max_steps":20
  }')"
RUN2_STATUS="$(echo "${RUN2_RESP}" | jq -r '.status')"
RUN2_RESULT_STATUS="$(echo "${RUN2_RESP}" | jq -r '.final_context.result.status // ""')"
if [[ "${RUN2_STATUS}" != "success" || "${RUN2_RESULT_STATUS}" != "pending_review" ]]; then
  echo "[B05-SMOKE] second run failed"
  echo "${RUN2_RESP}"
  exit 1
fi

echo "[6/6] query workflow run"
curl -fsS "${BASE_URL}/api/v1/workflow-runs/${RUN1_ID}" | jq -e '.run_id and .steps and .status' >/dev/null

echo "[B05-SMOKE] workflowId=${WORKFLOW_ID}, run1=${RUN1_ID}, result1=${RUN1_RESULT_STATUS}, result2=${RUN2_RESULT_STATUS}"
echo "[B05-SMOKE] SUCCESS"
