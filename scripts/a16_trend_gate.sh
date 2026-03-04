#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

RUN_BASELINE_FIRST="${RUN_BASELINE_FIRST:-false}"
REPORT_PATTERN="${REPORT_PATTERN:-${REPORT_DIR}/a11_baseline_*.md}"
MIN_REPORTS="${MIN_REPORTS:-3}"

WINDOW="${WINDOW:-7}"
TREND_ROWS="${TREND_ROWS:-10}"
STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD:-8}"
MAX_DRIFT_PCT="${MAX_DRIFT_PCT:-20}"
EPSILON_MS="${EPSILON_MS:-0.05}"

STRICT="${STRICT:-true}"
FAIL_ON_WARN="${FAIL_ON_WARN:-false}"

TREND_REPORT="${TREND_REPORT:-${REPORT_DIR}/a15_baseline_trend_${TIMESTAMP}.md}"
GATE_REPORT="${GATE_REPORT:-${REPORT_DIR}/a16_trend_gate_${TIMESTAMP}.md}"

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

collect_reports() {
  REPORTS=()
  while IFS= read -r report_file; do
    REPORTS+=("${report_file}")
  done < <(compgen -G "${REPORT_PATTERN}" | sort || true)
}

latest_report_or_na() {
  if [[ "${#REPORTS[@]}" -eq 0 ]]; then
    echo "n/a"
    return 0
  fi
  local last_index
  last_index=$(( ${#REPORTS[@]} - 1 ))
  echo "${REPORTS[${last_index}]}"
}

render_report() {
  local gate_status="$1"
  local gate_reason="$2"
  local trend_status="$3"
  local a15_exit_code="$4"
  local latest_report="$5"
  {
    echo "# A16 趋势门禁报告"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "- gate 结论：${gate_status}"
    echo "- gate 原因：${gate_reason}"
    echo "- trend 结论：${trend_status}"
    echo "- A15 退出码：${a15_exit_code}"
    echo "- strict：${STRICT}"
    echo "- failOnWarn：${FAIL_ON_WARN}"
    echo "- 报告匹配：\`${REPORT_PATTERN}\`"
    echo "- 报告数量：${#REPORTS[@]}"
    echo "- 最新 A11 报告：${latest_report}"
    echo "- A15 报告：${TREND_REPORT}"
    echo
    echo "## 建议"
    echo
    if [[ "${gate_status}" == "pass" ]]; then
      echo "1. 趋势门禁通过，可继续推进。"
    elif [[ "${gate_status}" == "warn" ]]; then
      echo "1. 指标出现回归趋势，建议结合 A13/A14 复核后再推进。"
    else
      echo "1. 趋势门禁未通过，建议先优化核心指标后再推进。"
    fi
    echo
    echo "## A15 执行日志"
    echo
    echo '```text'
    cat "${TMP_DIR}/a15.log"
    echo '```'
  } >"${GATE_REPORT}"
}

if is_true "${RUN_BASELINE_FIRST}"; then
  echo "[A16] RUN_BASELINE_FIRST=true, running A11 baseline ..."
  "${ROOT_DIR}/scripts/a11_baseline.sh"
fi

collect_reports
latest_report="$(latest_report_or_na)"

if [[ "${#REPORTS[@]}" -lt "${MIN_REPORTS}" ]]; then
  echo "[A16] need at least ${MIN_REPORTS} reports, found ${#REPORTS[@]}"
  echo "[A16] pattern=${REPORT_PATTERN}"
  : >"${TMP_DIR}/a15.log"
  echo "[A16] skipped A15: insufficient reports" >>"${TMP_DIR}/a15.log"
  render_report "fail" "insufficient reports for trend analysis" "n/a" "1" "${latest_report}"
  echo "[A16] report generated: ${GATE_REPORT}"
  exit 1
fi

set +e
REPORT_PATTERN="${REPORT_PATTERN}" \
OUTPUT_REPORT="${TREND_REPORT}" \
WINDOW="${WINDOW}" \
TREND_ROWS="${TREND_ROWS}" \
MIN_REPORTS="${MIN_REPORTS}" \
STRICT="${STRICT}" \
FAIL_ON_WARN="${FAIL_ON_WARN}" \
STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD}" \
MAX_DRIFT_PCT="${MAX_DRIFT_PCT}" \
EPSILON_MS="${EPSILON_MS}" \
"${ROOT_DIR}/scripts/a15_baseline_trend.sh" >"${TMP_DIR}/a15.log" 2>&1
a15_exit_code=$?
set -e

trend_status="unknown"
if [[ -f "${TREND_REPORT}" ]]; then
  trend_status="$(awk -F'：' '/^- 结论：/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' "${TREND_REPORT}" || true)"
fi
if [[ -z "${trend_status}" ]]; then
  trend_status="unknown"
fi

gate_status="pass"
gate_reason="trend stable"

if [[ "${a15_exit_code}" -ne 0 && "${trend_status}" == "unknown" ]]; then
  gate_status="fail"
  gate_reason="A15 execution failed before report generation"
elif [[ "${trend_status}" == "pass" ]]; then
  gate_status="pass"
  gate_reason="trend passed"
elif [[ "${trend_status}" == "warn" ]]; then
  if is_true "${FAIL_ON_WARN}"; then
    gate_status="fail"
    gate_reason="warn treated as failure by FAIL_ON_WARN=true"
  else
    gate_status="warn"
    gate_reason="non-blocking trend warning"
  fi
elif [[ "${trend_status}" == "fail" ]]; then
  gate_status="fail"
  gate_reason="trend check failed"
else
  gate_status="fail"
  gate_reason="unknown trend conclusion"
fi

render_report "${gate_status}" "${gate_reason}" "${trend_status}" "${a15_exit_code}" "${latest_report}"

echo "[A16] trend report: ${TREND_REPORT}"
echo "[A16] gate report: ${GATE_REPORT}"

if [[ "${gate_status}" == "fail" ]]; then
  exit 1
fi

echo "[A16] SUCCESS"
