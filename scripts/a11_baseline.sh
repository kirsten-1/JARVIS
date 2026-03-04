#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[A11] jq is required. Please install jq first."
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
REPORT_PATH="${REPORT_PATH:-${REPORT_DIR}/a11_baseline_${TIMESTAMP}.md}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

BASE_URL="${BASE_URL:-http://localhost:8080}"
USER_ID="${USER_ID:-1001}"
ROLE="${ROLE:-USER}"
SAMPLES="${SAMPLES:-20}"
WARMUP="${WARMUP:-3}"
TOKEN="${TOKEN:-}"
JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"

RUN_AGENT_BASELINE="${RUN_AGENT_BASELINE:-false}"
AGENT_SAMPLES="${AGENT_SAMPLES:-3}"
AGENT_WARMUP="${AGENT_WARMUP:-1}"
AGENT_PROVIDER="${AGENT_PROVIDER:-${PROVIDER:-}}"
AGENT_MODEL="${AGENT_MODEL:-${MODEL:-}}"
AGENT_MAX_STEPS="${AGENT_MAX_STEPS:-3}"
AGENT_MESSAGE="${AGENT_MESSAGE:-请告诉我现在时间，并给出当前工作区运营指标摘要。}"

TARGET_HEALTH_P90_MS="${TARGET_HEALTH_P90_MS:-120}"
TARGET_CONV_LIST_HOT_P90_MS="${TARGET_CONV_LIST_HOT_P90_MS:-250}"
TARGET_MSG_LIST_HOT_P90_MS="${TARGET_MSG_LIST_HOT_P90_MS:-250}"
TARGET_AGENT_P90_MS="${TARGET_AGENT_P90_MS:-2500}"
STRICT="${STRICT:-false}"

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

to_ms() {
  local seconds="$1"
  awk -v s="${seconds}" 'BEGIN { printf "%.2f", s * 1000 }'
}

gen_local_jwt() {
  if ! command -v openssl >/dev/null 2>&1; then
    echo "[A11] openssl is required to sign local jwt when JARVIS_JWT_SECRET is set"
    exit 1
  fi
  base64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
  }
  local now_ts exp_ts jwt_header jwt_payload sign_input sig
  now_ts="$(date +%s)"
  exp_ts=$((now_ts + JARVIS_JWT_EXPIRE_SECONDS))
  jwt_header="$(printf '{"alg":"HS256","typ":"JWT"}' | base64url)"
  jwt_payload="$(jq -nc \
    --arg sub "${USER_ID}" \
    --arg iss "${JARVIS_JWT_ISSUER}" \
    --arg role "${ROLE}" \
    --argjson iat "${now_ts}" \
    --argjson exp "${exp_ts}" \
    '{sub:$sub,iss:$iss,iat:$iat,exp:$exp,role:$role}' | base64url)"
  sign_input="${jwt_header}.${jwt_payload}"
  sig="$(printf '%s' "${sign_input}" | openssl dgst -binary -sha256 -hmac "${JARVIS_JWT_SECRET}" | base64url)"
  TOKEN="${sign_input}.${sig}"
}

resolve_token() {
  if [[ -n "${TOKEN}" ]]; then
    echo "[A11] using provided TOKEN"
    return 0
  fi

  if [[ -z "${JARVIS_JWT_SECRET}" && -f "${ROOT_DIR}/.env.prod" ]]; then
    set -a
    . "${ROOT_DIR}/.env.prod"
    set +a
    JARVIS_JWT_SECRET="${JARVIS_JWT_SECRET:-}"
    JARVIS_JWT_ISSUER="${JARVIS_JWT_ISSUER:-jarvis-gateway}"
    JARVIS_JWT_EXPIRE_SECONDS="${JARVIS_JWT_EXPIRE_SECONDS:-86400}"
    if [[ -z "${AGENT_PROVIDER}" && -n "${AI_DEFAULT_PROVIDER:-}" ]]; then
      AGENT_PROVIDER="${AI_DEFAULT_PROVIDER}"
    fi
    if [[ -z "${AGENT_MODEL}" && -n "${AGENT_PROVIDER}" ]]; then
      AGENT_PROVIDER_UPPER="$(echo "${AGENT_PROVIDER}" | tr '[:lower:]' '[:upper:]')"
      AGENT_MODEL_VAR="AI_${AGENT_PROVIDER_UPPER}_MODEL"
      AGENT_MODEL="${!AGENT_MODEL_VAR:-}"
    fi
  fi

  if [[ -n "${JARVIS_JWT_SECRET}" ]]; then
    echo "[A11] generating local jwt from JARVIS_JWT_SECRET"
    gen_local_jwt
    return 0
  fi

  echo "[A11] issuing dev token"
  local dev_resp_file dev_http
  dev_resp_file="$(mktemp)"
  dev_http=$(curl -sS -o "${dev_resp_file}" -w "%{http_code}" -X POST "${BASE_URL}/api/v1/auth/dev-token" \
    -H 'Content-Type: application/json' \
    -d "{\"userId\":${USER_ID},\"role\":\"${ROLE}\"}" || true)
  if [[ "${dev_http}" != "200" ]]; then
    echo "[A11] dev-token endpoint unavailable (http=${dev_http})"
    cat "${dev_resp_file}"
    rm -f "${dev_resp_file}"
    exit 1
  fi
  TOKEN="$(jq -r '.data.token' "${dev_resp_file}")"
  rm -f "${dev_resp_file}"
  if [[ -z "${TOKEN}" || "${TOKEN}" == "null" ]]; then
    echo "[A11] failed to parse token"
    exit 1
  fi
}

call_api() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local need_auth="${4:-false}"
  local expected_http="${5:-200}"
  local out_file="${6:-}"
  local tmp_file http_and_time http_code time_total

  tmp_file="${out_file}"
  if [[ -z "${tmp_file}" ]]; then
    tmp_file="$(mktemp)"
  fi

  local cmd=(curl -sS -o "${tmp_file}" -w "%{http_code}\t%{time_total}" -X "${method}" "${url}")
  if is_true "${need_auth}"; then
    cmd+=(-H "Authorization: Bearer ${TOKEN}")
  fi
  if [[ -n "${body}" ]]; then
    cmd+=(-H 'Content-Type: application/json' -d "${body}")
  fi

  http_and_time="$("${cmd[@]}" || true)"
  http_code="$(printf '%s' "${http_and_time}" | awk -F'\t' '{print $1}')"
  time_total="$(printf '%s' "${http_and_time}" | awk -F'\t' '{print $2}')"

  if [[ "${http_code}" != "${expected_http}" ]]; then
    echo "[A11] api failed: ${method} ${url} (http=${http_code}, expected=${expected_http})"
    cat "${tmp_file}"
    if [[ -z "${out_file}" ]]; then
      rm -f "${tmp_file}"
    fi
    exit 1
  fi

  printf '%s' "$(to_ms "${time_total}")"
  if [[ -z "${out_file}" ]]; then
    rm -f "${tmp_file}"
  fi
}

calc_stats() {
  local file="$1"
  local prefix="$2"
  local sorted_file count avg min p50 p90 p99 max idx50 idx90 idx99
  sorted_file="${TMP_DIR}/${prefix}_sorted.txt"
  sort -n "${file}" >"${sorted_file}"
  count="$(wc -l <"${sorted_file}" | tr -d ' ')"
  if [[ "${count}" -eq 0 ]]; then
    echo "[A11] empty metric file: ${file}"
    exit 1
  fi
  avg="$(awk '{s+=$1} END {printf "%.2f", s/NR}' "${sorted_file}")"
  min="$(head -n 1 "${sorted_file}")"
  max="$(tail -n 1 "${sorted_file}")"

  idx50=$(( (count - 1) * 50 / 100 + 1 ))
  idx90=$(( (count - 1) * 90 / 100 + 1 ))
  idx99=$(( (count - 1) * 99 / 100 + 1 ))
  p50="$(sed -n "${idx50}p" "${sorted_file}")"
  p90="$(sed -n "${idx90}p" "${sorted_file}")"
  p99="$(sed -n "${idx99}p" "${sorted_file}")"

  eval "${prefix}_count='${count}'"
  eval "${prefix}_avg='${avg}'"
  eval "${prefix}_min='${min}'"
  eval "${prefix}_p50='${p50}'"
  eval "${prefix}_p90='${p90}'"
  eval "${prefix}_p99='${p99}'"
  eval "${prefix}_max='${max}'"
}

run_benchmark() {
  local metric_name="$1"
  local method="$2"
  local url="$3"
  local body="${4:-}"
  local need_auth="${5:-false}"
  local expected_http="${6:-200}"
  local warmup_count="${7:-${WARMUP}}"
  local sample_count="${8:-${SAMPLES}}"
  local out_file="${TMP_DIR}/${metric_name}.txt"

  : >"${out_file}"
  for ((i=1; i<=warmup_count; i++)); do
    call_api "${method}" "${url}" "${body}" "${need_auth}" "${expected_http}" >/dev/null
  done

  for ((i=1; i<=sample_count; i++)); do
    call_api "${method}" "${url}" "${body}" "${need_auth}" "${expected_http}" >>"${out_file}"
    echo >>"${out_file}"
  done

  calc_stats "${out_file}" "${metric_name}"
}

check_target() {
  local metric_name="$1"
  local observed_p90="$2"
  local target="$3"
  local label="$4"
  if awk -v v="${observed_p90}" -v t="${target}" 'BEGIN { exit(v <= t ? 0 : 1) }'; then
    echo "${label}:pass (p90=${observed_p90}ms <= ${target}ms)"
  else
    echo "${label}:warn (p90=${observed_p90}ms > ${target}ms)"
    if is_true "${STRICT}"; then
      echo "[A11] STRICT mode enabled, threshold check failed: ${label}"
      exit 1
    fi
  fi
}

create_conversation() {
  local title="$1"
  local conv_file conv_json
  conv_file="$(mktemp)"
  call_api "POST" "${BASE_URL}/api/v1/conversations" \
    "{\"userId\":${USER_ID},\"title\":\"${title}\"}" \
    true \
    200 \
    "${conv_file}" >/dev/null
  conv_json="$(cat "${conv_file}")"
  rm -f "${conv_file}"
  CONV_ID="$(echo "${conv_json}" | jq -r '.data.id')"
  WORKSPACE_ID="$(echo "${conv_json}" | jq -r '.data.workspaceId')"
  if [[ -z "${CONV_ID}" || "${CONV_ID}" == "null" || -z "${WORKSPACE_ID}" || "${WORKSPACE_ID}" == "null" ]]; then
    echo "[A11] failed to create conversation"
    echo "${conv_json}"
    exit 1
  fi
}

append_message() {
  local text="$1"
  call_api "POST" "${BASE_URL}/api/v1/conversations/${CONV_ID}/messages" \
    "{\"userId\":${USER_ID},\"role\":\"USER\",\"content\":\"${text}\"}" \
    true \
    200 >/dev/null
}

run_agent_once() {
  local out_file payload http_and_time http_code time_total
  out_file="$(mktemp)"
  payload="$(jq -nc \
    --argjson userId "${USER_ID}" \
    --arg message "${AGENT_MESSAGE}" \
    --arg provider "${AGENT_PROVIDER}" \
    --arg model "${AGENT_MODEL}" \
    --argjson maxSteps "${AGENT_MAX_STEPS}" \
    '{
      userId: $userId,
      message: $message,
      maxSteps: $maxSteps
    }
    + (if ($provider | length) > 0 then {provider: $provider} else {} end)
    + (if ($model | length) > 0 then {model: $model} else {} end)'
  )"
  http_and_time=$(curl -sS -o "${out_file}" -w "%{http_code}\t%{time_total}" -X POST \
    "${BASE_URL}/api/v1/conversations/${CONV_ID}/agent/run" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "${payload}" || true)
  http_code="$(printf '%s' "${http_and_time}" | awk -F'\t' '{print $1}')"
  time_total="$(printf '%s' "${http_and_time}" | awk -F'\t' '{print $2}')"
  if [[ "${http_code}" != "200" ]]; then
    echo "[A11] agent run failed (http=${http_code})"
    cat "${out_file}"
    rm -f "${out_file}"
    exit 1
  fi
  if [[ "$(jq -r '.code' "${out_file}")" != "0" ]]; then
    echo "[A11] agent run business failed"
    cat "${out_file}"
    rm -f "${out_file}"
    exit 1
  fi
  rm -f "${out_file}"
  printf '%s' "$(to_ms "${time_total}")"
}

echo "[A11] BASE_URL=${BASE_URL}"
echo "[A11] samples=${SAMPLES}, warmup=${WARMUP}"
echo "[A11] run_agent_baseline=${RUN_AGENT_BASELINE}"

echo "[A11] health check"
call_api "GET" "${BASE_URL}/actuator/health" "" false 200 >/dev/null

resolve_token
create_conversation "A11 Baseline Conversation"
append_message "A11 baseline seed"

CONV_LIST_URL="${BASE_URL}/api/v1/conversations?userId=${USER_ID}&workspaceId=${WORKSPACE_ID}&page=0&size=20"
MSG_LIST_URL="${BASE_URL}/api/v1/conversations/${CONV_ID}/messages?userId=${USER_ID}"

echo "[A11] benchmark health"
run_benchmark "health" "GET" "${BASE_URL}/actuator/health" "" false 200

echo "[A11] benchmark conversation list (cold/hot)"
create_conversation "A11 Baseline Conversation Evict"
conv_list_cold_file="${TMP_DIR}/conv_list_cold.txt"
: >"${conv_list_cold_file}"
call_api "GET" "${CONV_LIST_URL}" "" true 200 >>"${conv_list_cold_file}"
echo >>"${conv_list_cold_file}"
calc_stats "${conv_list_cold_file}" "conv_list_cold"
run_benchmark "conv_list_hot" "GET" "${CONV_LIST_URL}" "" true 200

echo "[A11] benchmark message list (cold/hot)"
append_message "A11 baseline evict message cache"
msg_list_cold_file="${TMP_DIR}/msg_list_cold.txt"
: >"${msg_list_cold_file}"
call_api "GET" "${MSG_LIST_URL}" "" true 200 >>"${msg_list_cold_file}"
echo >>"${msg_list_cold_file}"
calc_stats "${msg_list_cold_file}" "msg_list_cold"
run_benchmark "msg_list_hot" "GET" "${MSG_LIST_URL}" "" true 200

if is_true "${RUN_AGENT_BASELINE}"; then
  echo "[A11] benchmark agent loop"
  agent_file="${TMP_DIR}/agent_loop.txt"
  : >"${agent_file}"
  for ((i=1; i<=AGENT_WARMUP; i++)); do
    create_conversation "A11 Agent Warmup ${i}"
    run_agent_once >/dev/null
  done
  for ((i=1; i<=AGENT_SAMPLES; i++)); do
    create_conversation "A11 Agent Sample ${i}"
    run_agent_once >>"${agent_file}"
    echo >>"${agent_file}"
  done
  calc_stats "${agent_file}" "agent_loop"
fi

conv_improve_pct="$(awk -v cold="${conv_list_cold_avg}" -v hot="${conv_list_hot_p50}" 'BEGIN { if (cold<=0) {print "0.00"} else {printf "%.2f", (cold-hot)*100/cold} }')"
msg_improve_pct="$(awk -v cold="${msg_list_cold_avg}" -v hot="${msg_list_hot_p50}" 'BEGIN { if (cold<=0) {print "0.00"} else {printf "%.2f", (cold-hot)*100/cold} }')"

TARGET_RESULTS_FILE="${TMP_DIR}/target_results.txt"
: >"${TARGET_RESULTS_FILE}"
check_target "health" "${health_p90}" "${TARGET_HEALTH_P90_MS}" "health" >>"${TARGET_RESULTS_FILE}"
check_target "conv_list_hot" "${conv_list_hot_p90}" "${TARGET_CONV_LIST_HOT_P90_MS}" "conversation_list_hot" >>"${TARGET_RESULTS_FILE}"
check_target "msg_list_hot" "${msg_list_hot_p90}" "${TARGET_MSG_LIST_HOT_P90_MS}" "message_list_hot" >>"${TARGET_RESULTS_FILE}"
if is_true "${RUN_AGENT_BASELINE}"; then
  check_target "agent_loop" "${agent_loop_p90}" "${TARGET_AGENT_P90_MS}" "agent_loop" >>"${TARGET_RESULTS_FILE}"
fi

{
  echo "# A11 性能基线报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- BASE_URL：${BASE_URL}"
  echo "- userId：${USER_ID}"
  echo "- 样本：${SAMPLES}（warmup=${WARMUP}）"
  echo "- Agent 基线：${RUN_AGENT_BASELINE}"
  if is_true "${RUN_AGENT_BASELINE}"; then
    echo "- Agent 样本：${AGENT_SAMPLES}（warmup=${AGENT_WARMUP}）"
    echo "- Agent provider/model：${AGENT_PROVIDER:-<default>} / ${AGENT_MODEL:-<default>}"
  fi
  echo
  echo "## 指标结果（ms）"
  echo
  echo "| Metric | Count | Avg | Min | P50 | P90 | P99 | Max |"
  echo "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
  echo "| health | ${health_count} | ${health_avg} | ${health_min} | ${health_p50} | ${health_p90} | ${health_p99} | ${health_max} |"
  echo "| conversation_list_cold | ${conv_list_cold_count} | ${conv_list_cold_avg} | ${conv_list_cold_min} | ${conv_list_cold_p50} | ${conv_list_cold_p90} | ${conv_list_cold_p99} | ${conv_list_cold_max} |"
  echo "| conversation_list_hot | ${conv_list_hot_count} | ${conv_list_hot_avg} | ${conv_list_hot_min} | ${conv_list_hot_p50} | ${conv_list_hot_p90} | ${conv_list_hot_p99} | ${conv_list_hot_max} |"
  echo "| message_list_cold | ${msg_list_cold_count} | ${msg_list_cold_avg} | ${msg_list_cold_min} | ${msg_list_cold_p50} | ${msg_list_cold_p90} | ${msg_list_cold_p99} | ${msg_list_cold_max} |"
  echo "| message_list_hot | ${msg_list_hot_count} | ${msg_list_hot_avg} | ${msg_list_hot_min} | ${msg_list_hot_p50} | ${msg_list_hot_p90} | ${msg_list_hot_p99} | ${msg_list_hot_max} |"
  if is_true "${RUN_AGENT_BASELINE}"; then
    echo "| agent_loop | ${agent_loop_count} | ${agent_loop_avg} | ${agent_loop_min} | ${agent_loop_p50} | ${agent_loop_p90} | ${agent_loop_p99} | ${agent_loop_max} |"
  fi
  echo
  echo "## 缓存收益（按 P50 估算）"
  echo
  echo "- conversation list：cold avg=${conv_list_cold_avg}ms，hot p50=${conv_list_hot_p50}ms，改善=${conv_improve_pct}%"
  echo "- message list：cold avg=${msg_list_cold_avg}ms，hot p50=${msg_list_hot_p50}ms，改善=${msg_improve_pct}%"
  echo
  echo "## 阈值检查"
  echo
  echo "| Check | Result |"
  echo "| --- | --- |"
  while IFS= read -r line; do
    check_name="$(echo "${line}" | awk -F':' '{print $1}')"
    check_result="$(echo "${line}" | awk -F':' '{print $2}')"
    echo "| ${check_name} | ${check_result} |"
  done <"${TARGET_RESULTS_FILE}"
  echo
  echo "## 说明"
  echo
  echo "1. 该脚本用于阶段性基线与回归对比，不等价于高并发压测。"
  echo "2. cold/hot 通过应用读缓存命中差异估算，适合作为趋势跟踪。"
  echo "3. 若需严格门禁，可设置 \`STRICT=true\`。"
} >"${REPORT_PATH}"

echo "[A11] report generated: ${REPORT_PATH}"
echo "[A11] SUCCESS"
