#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
REPORT_FILE="${REPORT_DIR}/a06_checkpoint_${TIMESTAMP}.md"

RUN_UP_FIRST="${RUN_UP_FIRST:-false}"
RUN_M11_SMOKE="${RUN_M11_SMOKE:-true}"
RUN_A04_SMOKE="${RUN_A04_SMOKE:-true}"

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  . "${ENV_FILE}"
  set +a
fi

run_step() {
  local key="$1"
  local cmd="$2"
  local output_file="$3"

  echo "[A06] ${key}: ${cmd}"
  if bash -lc "${cmd}" >"${output_file}" 2>&1; then
    echo "success" >"${output_file}.status"
  else
    echo "failed" >"${output_file}.status"
  fi
}

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

if is_true "${RUN_UP_FIRST}"; then
  run_step "m11_up" "\"${ROOT_DIR}/scripts/m11_prod_up.sh\"" "${TMP_DIR}/m11_up.log"
fi

if is_true "${RUN_M11_SMOKE}"; then
  run_step "m11_smoke" "\"${ROOT_DIR}/scripts/m11_smoke.sh\"" "${TMP_DIR}/m11_smoke.log"
fi

if is_true "${RUN_A04_SMOKE}"; then
  run_step "a04_smoke" "\"${ROOT_DIR}/scripts/a04_smoke.sh\"" "${TMP_DIR}/a04_smoke.log"
fi

if command -v docker >/dev/null 2>&1; then
  docker compose -f "${ROOT_DIR}/docker-compose.prod.yml" --env-file "${ENV_FILE:-/dev/null}" ps >"${TMP_DIR}/compose_ps.log" 2>&1 || true
fi

git -C "${ROOT_DIR}" rev-parse --short HEAD >"${TMP_DIR}/git_head.log" 2>/dev/null || echo "N/A" >"${TMP_DIR}/git_head.log"
git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD >"${TMP_DIR}/git_branch.log" 2>/dev/null || echo "N/A" >"${TMP_DIR}/git_branch.log"
git -C "${ROOT_DIR}" status --short >"${TMP_DIR}/git_status.log" 2>/dev/null || true

{
  echo "# A06 阶段检查点报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 分支：$(cat "${TMP_DIR}/git_branch.log")"
  echo "- 提交：$(cat "${TMP_DIR}/git_head.log")"
  echo
  echo "## 配置快照（脱敏）"
  echo
  echo "- AI_DEFAULT_PROVIDER=${AI_DEFAULT_PROVIDER:-}"
  echo "- AI_AISERVICE_ENABLED=${AI_AISERVICE_ENABLED:-}"
  echo "- AI_AISERVICE_COMPOSE_ENABLED=${AI_AISERVICE_COMPOSE_ENABLED:-}"
  echo "- AI_AISERVICE_BASE_URL=${AI_AISERVICE_BASE_URL:-}"
  echo "- AISERVICE_IMAGE=${AISERVICE_IMAGE:-}"
  echo "- AISERVICE_PORT=${AISERVICE_PORT:-}"
  echo "- AI_MINIMAX_ENABLED=${AI_MINIMAX_ENABLED:-}"
  echo "- AIS_MINIMAX_ENABLED=${AIS_MINIMAX_ENABLED:-}"
  echo
  echo "## 验收结果"
  echo
  echo "| Step | Status |"
  echo "| --- | --- |"
  if [[ -f "${TMP_DIR}/m11_up.log.status" ]]; then
    echo "| m11_up | $(cat "${TMP_DIR}/m11_up.log.status") |"
  fi
  if [[ -f "${TMP_DIR}/m11_smoke.log.status" ]]; then
    echo "| m11_smoke | $(cat "${TMP_DIR}/m11_smoke.log.status") |"
  fi
  if [[ -f "${TMP_DIR}/a04_smoke.log.status" ]]; then
    echo "| a04_smoke | $(cat "${TMP_DIR}/a04_smoke.log.status") |"
  fi
  echo

  if [[ -f "${TMP_DIR}/compose_ps.log" ]]; then
    echo "## Compose 状态"
    echo
    echo '```text'
    cat "${TMP_DIR}/compose_ps.log"
    echo '```'
    echo
  fi

  if [[ -f "${TMP_DIR}/m11_up.log" ]]; then
    echo "## m11_up 输出"
    echo
    echo '```text'
    cat "${TMP_DIR}/m11_up.log"
    echo '```'
    echo
  fi
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

  echo "## Git 工作区"
  echo
  echo '```text'
  cat "${TMP_DIR}/git_status.log"
  echo '```'
  echo
  echo "## 建议下一步"
  echo
  echo "1. 复核报告后执行：\`git add docs/reports/$(basename "${REPORT_FILE}")\`"
  echo "2. 结合代码改动统一提交：\`git commit -m \"chore: A06 checkpoint ${TIMESTAMP}\"\`"
  echo "3. 阶段节点 push：\`git push origin main\`"
} >"${REPORT_FILE}"

echo "[A06] report generated: ${REPORT_FILE}"
