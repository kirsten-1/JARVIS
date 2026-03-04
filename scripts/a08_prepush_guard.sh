#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
REPORT_FILE="${REPORT_DIR}/a08_prepush_${TIMESTAMP}.md"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

RUN_M11_SMOKE="${RUN_M11_SMOKE:-true}"
RUN_A04_SMOKE="${RUN_A04_SMOKE:-true}"
RUN_M13_SMOKE="${RUN_M13_SMOKE:-false}"
PROVIDER="${PROVIDER:-aiservice}"
MODEL="${MODEL:-MiniMax-M2.5}"
AISERVICE_HEALTH_URL="${AISERVICE_HEALTH_URL:-}"

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

run_step() {
  local key="$1"
  local cmd="$2"
  local logfile="$3"
  echo "[A08] ${key}: ${cmd}"
  if bash -c "${cmd}" >"${logfile}" 2>&1; then
    echo "success" >"${logfile}.status"
  else
    echo "failed" >"${logfile}.status"
  fi
}

status_or_na() {
  local status_file="$1"
  if [[ -f "${status_file}" ]]; then
    cat "${status_file}"
  else
    echo "n/a"
  fi
}

resolve_aiservice_health_url() {
  local env_file="${ROOT_DIR}/.env.prod"
  local ai_aiservice_compose_enabled="false"
  local ai_aiservice_base_url=""
  local aiservice_port="18000"

  if [[ -f "${env_file}" ]]; then
    set -a
    . "${env_file}"
    set +a
    ai_aiservice_compose_enabled="${AI_AISERVICE_COMPOSE_ENABLED:-false}"
    ai_aiservice_base_url="${AI_AISERVICE_BASE_URL:-}"
    aiservice_port="${AISERVICE_PORT:-18000}"
  fi

  if [[ -n "${AISERVICE_HEALTH_URL}" ]]; then
    echo "${AISERVICE_HEALTH_URL}"
    return 0
  fi

  if is_true "${ai_aiservice_compose_enabled}"; then
    echo "http://localhost:${aiservice_port}/health"
    return 0
  fi

  if [[ -n "${ai_aiservice_base_url}" ]]; then
    echo "${ai_aiservice_base_url%/}/health"
    return 0
  fi

  echo "http://localhost:8000/health"
}

normalize_health_url_for_host_check() {
  local raw_url="$1"
  if [[ "${raw_url}" == *"host.docker.internal"* ]]; then
    echo "${raw_url/host.docker.internal/localhost}"
    return 0
  fi
  echo "${raw_url}"
}

check_env_tracking() {
  local logfile="$1"
  if git -C "${ROOT_DIR}" -c core.quotePath=false ls-files | grep -Eq '^\.env$|^\.env\.prod$'; then
    echo "tracked forbidden env files detected (.env or .env.prod)" >"${logfile}"
    return 1
  fi
  echo "ok" >"${logfile}"
}

check_secret_like_literals() {
  local logfile="$1"
  local matches_file="${TMP_DIR}/secret_matches.txt"
  : >"${matches_file}"
  while IFS= read -r file; do
    case "${file}" in
      *.md|*.txt|*.pdf|*.png|*.jpg|*.jpeg|*.gif|*.svg) continue ;;
      docs/reports/*) continue ;;
      target/*|frontend/dist/*) continue ;;
    esac
    # 1) hardcoded quoted literals in code/config
    grep -nE "(api[_-]?key|secret|token|password)[[:space:]]*[:=][[:space:]]*['\"][A-Za-z0-9._:-]{16,}['\"]" "${ROOT_DIR}/${file}" 2>/dev/null \
      | grep -viE "change_me|your[_-]?key|example|placeholder|null|dummy|test|tokenEstimator|passwordEncoder|JARVIS_JWT_SECRET" \
      | awk -v f="${file}" '{print f ":" $0}' >>"${matches_file}" || true

    # 2) env/properties style assignments
    case "${file}" in
      *.env|*.env.*|*.properties|*.yml|*.yaml)
        grep -nE "^[A-Za-z0-9_.-]*(API[_-]?KEY|SECRET|TOKEN|PASSWORD)[A-Za-z0-9_.-]*=[A-Za-z0-9._:-]{16,}$" "${ROOT_DIR}/${file}" 2>/dev/null \
          | grep -viE "change_me|your[_-]?key|example|placeholder|null|dummy|test|JARVIS_JWT_SECRET" \
          | awk -v f="${file}" '{print f ":" $0}' >>"${matches_file}" || true
        ;;
    esac
  done < <(git -C "${ROOT_DIR}" -c core.quotePath=false ls-files)

  if [[ -s "${matches_file}" ]]; then
    cat "${matches_file}" >"${logfile}"
    return 1
  fi
  echo "ok" >"${logfile}"
}

STATUS_OVERALL="success"
NEED_AISERVICE_HEALTH_CHECK="false"
AISERVICE_UNAVAILABLE="false"
RESOLVED_AISERVICE_HEALTH_URL=""
HOST_CHECK_AISERVICE_HEALTH_URL=""

run_step "git_status" "git -C \"${ROOT_DIR}\" status --short" "${TMP_DIR}/git_status.log"
check_env_tracking "${TMP_DIR}/env_tracking.log" || STATUS_OVERALL="failed"
check_secret_like_literals "${TMP_DIR}/secret_scan.log" || STATUS_OVERALL="failed"

if is_true "${RUN_M11_SMOKE}"; then
  run_step "m11_smoke" "\"${ROOT_DIR}/scripts/m11_smoke.sh\"" "${TMP_DIR}/m11_smoke.log"
  [[ "$(cat "${TMP_DIR}/m11_smoke.log.status")" == "success" ]] || STATUS_OVERALL="failed"
fi

if is_true "${RUN_A04_SMOKE}"; then
  NEED_AISERVICE_HEALTH_CHECK="true"
fi

if is_true "${RUN_M13_SMOKE}" && [[ "${PROVIDER}" == "aiservice" ]]; then
  NEED_AISERVICE_HEALTH_CHECK="true"
fi

if is_true "${NEED_AISERVICE_HEALTH_CHECK}"; then
  RESOLVED_AISERVICE_HEALTH_URL="$(resolve_aiservice_health_url)"
  HOST_CHECK_AISERVICE_HEALTH_URL="$(normalize_health_url_for_host_check "${RESOLVED_AISERVICE_HEALTH_URL}")"
  run_step "aiservice_health" "curl --connect-timeout 5 --max-time 10 -fsS \"${HOST_CHECK_AISERVICE_HEALTH_URL}\"" "${TMP_DIR}/aiservice_health.log"
  if [[ "$(cat "${TMP_DIR}/aiservice_health.log.status")" != "success" ]]; then
    STATUS_OVERALL="failed"
    AISERVICE_UNAVAILABLE="true"
  fi
fi

if is_true "${RUN_A04_SMOKE}"; then
  if is_true "${AISERVICE_UNAVAILABLE}"; then
    {
      echo "[A04-SMOKE] blocked: aiservice health check failed."
      echo "[A04-SMOKE] check: ${HOST_CHECK_AISERVICE_HEALTH_URL}"
    } >"${TMP_DIR}/a04_smoke.log"
    echo "blocked" >"${TMP_DIR}/a04_smoke.log.status"
  else
    run_step "a04_smoke" "\"${ROOT_DIR}/scripts/a04_smoke.sh\"" "${TMP_DIR}/a04_smoke.log"
    [[ "$(cat "${TMP_DIR}/a04_smoke.log.status")" == "success" ]] || STATUS_OVERALL="failed"
  fi
fi

if is_true "${RUN_M13_SMOKE}"; then
  if is_true "${AISERVICE_UNAVAILABLE}" && [[ "${PROVIDER}" == "aiservice" ]]; then
    {
      echo "[M13-SMOKE] blocked: aiservice health check failed."
      echo "[M13-SMOKE] check: ${HOST_CHECK_AISERVICE_HEALTH_URL}"
    } >"${TMP_DIR}/m13_smoke.log"
    echo "blocked" >"${TMP_DIR}/m13_smoke.log.status"
  else
    run_step "m13_smoke" "PROVIDER='${PROVIDER}' MODEL='${MODEL}' \"${ROOT_DIR}/scripts/m13_smoke.sh\"" "${TMP_DIR}/m13_smoke.log"
    [[ "$(cat "${TMP_DIR}/m13_smoke.log.status")" == "success" ]] || STATUS_OVERALL="failed"
  fi
fi

{
  echo "# A08 预推送守卫报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${STATUS_OVERALL}"
  echo "- 分支：$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo N/A)"
  echo "- 提交：$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo N/A)"
  echo
  echo "## 校验结果"
  echo
  echo "| Check | Status |"
  echo "| --- | --- |"
  echo "| env_tracking | $([[ -s "${TMP_DIR}/env_tracking.log" && "$(cat "${TMP_DIR}/env_tracking.log")" == "ok" ]] && echo success || echo failed) |"
  echo "| secret_scan | $([[ -s "${TMP_DIR}/secret_scan.log" && "$(cat "${TMP_DIR}/secret_scan.log")" == "ok" ]] && echo success || echo failed) |"
  if [[ -f "${TMP_DIR}/aiservice_health.log.status" ]]; then
    echo "| aiservice_health | $(status_or_na "${TMP_DIR}/aiservice_health.log.status") |"
  fi
  if [[ -f "${TMP_DIR}/m11_smoke.log.status" ]]; then
    echo "| m11_smoke | $(status_or_na "${TMP_DIR}/m11_smoke.log.status") |"
  fi
  if [[ -f "${TMP_DIR}/a04_smoke.log.status" ]]; then
    echo "| a04_smoke | $(status_or_na "${TMP_DIR}/a04_smoke.log.status") |"
  fi
  if [[ -f "${TMP_DIR}/m13_smoke.log.status" ]]; then
    echo "| m13_smoke | $(status_or_na "${TMP_DIR}/m13_smoke.log.status") |"
  fi
  echo
  echo "## env 跟踪检查"
  echo
  echo '```text'
  cat "${TMP_DIR}/env_tracking.log"
  echo '```'
  echo
  echo "## 密钥样式扫描"
  echo
  echo '```text'
  cat "${TMP_DIR}/secret_scan.log"
  echo '```'
  echo
  if [[ -f "${TMP_DIR}/aiservice_health.log" ]]; then
    echo "## aiservice 健康检查"
    echo
    echo "- URL: ${HOST_CHECK_AISERVICE_HEALTH_URL}"
    if [[ -n "${RESOLVED_AISERVICE_HEALTH_URL}" && "${RESOLVED_AISERVICE_HEALTH_URL}" != "${HOST_CHECK_AISERVICE_HEALTH_URL}" ]]; then
      echo "- 来源配置URL: ${RESOLVED_AISERVICE_HEALTH_URL}"
    fi
    echo
    echo '```text'
    cat "${TMP_DIR}/aiservice_health.log"
    echo '```'
    echo
  fi
  echo "## Git 工作区"
  echo
  echo '```text'
  cat "${TMP_DIR}/git_status.log"
  echo '```'
  echo
  if [[ -f "${TMP_DIR}/m11_smoke.log" ]]; then
    echo "## m11_smoke 输出"
    echo
    echo '```text'
    cat "${TMP_DIR}/m11_smoke.log"
    echo '```'
    echo
  fi
  if [[ -f "${TMP_DIR}/a04_smoke.log" ]]; then
    echo "## a04_smoke 输出"
    echo
    echo '```text'
    cat "${TMP_DIR}/a04_smoke.log"
    echo '```'
    echo
  fi
  if [[ -f "${TMP_DIR}/m13_smoke.log" ]]; then
    echo "## m13_smoke 输出"
    echo
    echo '```text'
    cat "${TMP_DIR}/m13_smoke.log"
    echo '```'
    echo
  fi
  echo "## 建议"
  echo
  if [[ "${STATUS_OVERALL}" == "success" ]]; then
    echo "1. 可执行阶段性提交与推送：\`git push origin main\`"
    echo "2. 报告路径：\`docs/reports/$(basename "${REPORT_FILE}")\`"
  else
    echo "1. 先修复 failed 项，再执行 push。"
    if [[ -f "${TMP_DIR}/aiservice_health.log.status" && "$(cat "${TMP_DIR}/aiservice_health.log.status")" != "success" ]]; then
      echo "2. aiservice 当前不可达，先确认 ai-service 已启动并能被 gateway 访问。"
      echo "3. 手动检查：\`curl -fsS \"${HOST_CHECK_AISERVICE_HEALTH_URL}\" && echo ok\`"
      echo "4. 修复后重跑：\`RUN_M13_SMOKE=${RUN_M13_SMOKE} PROVIDER=${PROVIDER} MODEL=${MODEL} ./scripts/a08_prepush_guard.sh\`"
      echo "5. 报告路径：\`docs/reports/$(basename "${REPORT_FILE}")\`"
    else
      echo "2. 报告路径：\`docs/reports/$(basename "${REPORT_FILE}")\`"
    fi
  fi
} >"${REPORT_FILE}"

echo "[A08] report generated: ${REPORT_FILE}"
if [[ "${STATUS_OVERALL}" != "success" ]]; then
  echo "[A08] prepush guard failed"
  exit 1
fi
echo "[A08] SUCCESS"
