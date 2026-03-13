#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[B06-SMOKE] jq is required"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8091}"

echo "[B06-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/8] health"
curl -fsS "${BASE_URL}/health" >/dev/null

echo "[2/8] list workflow templates"
TPL_RESP="$(curl -fsS "${BASE_URL}/api/v1/workflow-templates")"
TPL_TOTAL="$(echo "${TPL_RESP}" | jq -r '.total')"
HAS_APPROVAL="$(echo "${TPL_RESP}" | jq -r '[.items[].template_id] | index("content-approval-webhook") != null')"
HAS_INCIDENT="$(echo "${TPL_RESP}" | jq -r '[.items[].template_id] | index("incident-escalation-webhook") != null')"
if [[ "${TPL_TOTAL}" -lt 2 || "${HAS_APPROVAL}" != "true" || "${HAS_INCIDENT}" != "true" ]]; then
  echo "[B06-SMOKE] template list validation failed"
  echo "${TPL_RESP}"
  exit 1
fi

echo "[3/8] instantiate content approval template"
WF1_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflow-templates/content-approval-webhook/instantiate" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"b06-content-flow",
    "metadata":{"scene":"b06-smoke"},
    "overrides":{"webhook_dry_run":true}
  }')"
WF1_ID="$(echo "${WF1_RESP}" | jq -r '.workflow_id')"
if [[ -z "${WF1_ID}" || "${WF1_ID}" == "null" ]]; then
  echo "[B06-SMOKE] failed to instantiate content template"
  echo "${WF1_RESP}"
  exit 1
fi

echo "[4/8] run content flow approved=true"
RUN1_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WF1_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"review":{"approved":true,"reviewer":"ops-user","comment":"looks good"}},
    "idempotency_key":"b06-content-run-001",
    "max_steps":30
  }')"
RUN1_ID="$(echo "${RUN1_RESP}" | jq -r '.run_id')"
RUN1_STATUS="$(echo "${RUN1_RESP}" | jq -r '.status')"
RUN1_RESULT="$(echo "${RUN1_RESP}" | jq -r '.final_context.result.status // ""')"
RUN1_WEBHOOK_DRY="$(echo "${RUN1_RESP}" | jq -r '.final_context.webhook.last.dry_run // false')"
RUN1_APPROVAL_STEP="$(echo "${RUN1_RESP}" | jq -r '[.steps[] | select(.node_type=="approval")] | length')"
RUN1_WEBHOOK_STEP="$(echo "${RUN1_RESP}" | jq -r '[.steps[] | select(.node_type=="webhook")] | length')"
if [[ "${RUN1_STATUS}" != "success" || "${RUN1_RESULT}" != "published" || "${RUN1_WEBHOOK_DRY}" != "true" || "${RUN1_APPROVAL_STEP}" -lt 1 || "${RUN1_WEBHOOK_STEP}" -lt 1 ]]; then
  echo "[B06-SMOKE] approved run validation failed"
  echo "${RUN1_RESP}"
  exit 1
fi

echo "[5/8] idempotent replay"
REPLAY_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WF1_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"review":{"approved":true,"reviewer":"ops-user","comment":"looks good"}},
    "idempotency_key":"b06-content-run-001",
    "max_steps":30
  }')"
REPLAY_HIT="$(echo "${REPLAY_RESP}" | jq -r '.idempotent_hit')"
REPLAY_RUN_ID="$(echo "${REPLAY_RESP}" | jq -r '.run_id')"
if [[ "${REPLAY_HIT}" != "true" || "${REPLAY_RUN_ID}" != "${RUN1_ID}" ]]; then
  echo "[B06-SMOKE] idempotent replay validation failed"
  echo "${REPLAY_RESP}"
  exit 1
fi

echo "[6/8] run content flow approved=false"
RUN2_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WF1_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"review":{"approved":false,"reviewer":"ops-user","comment":"need changes"}},
    "idempotency_key":"b06-content-run-002",
    "max_steps":30
  }')"
RUN2_STATUS="$(echo "${RUN2_RESP}" | jq -r '.status')"
RUN2_RESULT="$(echo "${RUN2_RESP}" | jq -r '.final_context.result.status // ""')"
if [[ "${RUN2_STATUS}" != "success" || "${RUN2_RESULT}" != "pending_review" ]]; then
  echo "[B06-SMOKE] rejected run validation failed"
  echo "${RUN2_RESP}"
  exit 1
fi

echo "[7/8] instantiate incident escalation template"
WF2_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflow-templates/incident-escalation-webhook/instantiate" \
  -H 'Content-Type: application/json' \
  -d '{
    "name":"b06-incident-flow",
    "metadata":{"scene":"b06-smoke"},
    "overrides":{"webhook_dry_run":true}
  }')"
WF2_ID="$(echo "${WF2_RESP}" | jq -r '.workflow_id')"
if [[ -z "${WF2_ID}" || "${WF2_ID}" == "null" ]]; then
  echo "[B06-SMOKE] failed to instantiate incident template"
  echo "${WF2_RESP}"
  exit 1
fi

echo "[8/8] run incident flow severity=4"
RUN3_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/workflows/${WF2_ID}/runs" \
  -H 'Content-Type: application/json' \
  -d '{
    "input":{"incident":{"id":"inc-100","severity":4}},
    "idempotency_key":"b06-incident-run-001",
    "max_steps":30
  }')"
RUN3_STATUS="$(echo "${RUN3_RESP}" | jq -r '.status')"
RUN3_RESULT="$(echo "${RUN3_RESP}" | jq -r '.final_context.result.status // ""')"
RUN3_WEBHOOK_STEP="$(echo "${RUN3_RESP}" | jq -r '[.steps[] | select(.node_type=="webhook")] | length')"
if [[ "${RUN3_STATUS}" != "success" || "${RUN3_RESULT}" != "escalated" || "${RUN3_WEBHOOK_STEP}" -lt 1 ]]; then
  echo "[B06-SMOKE] incident run validation failed"
  echo "${RUN3_RESP}"
  exit 1
fi

echo "[B06-SMOKE] wf1=${WF1_ID}, run1=${RUN1_ID}, wf2=${WF2_ID}, incidentResult=${RUN3_RESULT}"
echo "[B06-SMOKE] SUCCESS"
