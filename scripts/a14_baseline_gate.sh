#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
BASELINE_DIR="${ROOT_DIR}/docs/baseline"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"

MODE="${MODE:-${1:-check}}"

REFERENCE_REPORT="${REFERENCE_REPORT:-${BASELINE_DIR}/a11_reference.md}"
SOURCE_REPORT="${SOURCE_REPORT:-}"
CANDIDATE_REPORT="${CANDIDATE_REPORT:-}"

STRICT="${STRICT:-false}"
FAIL_ON_WARN="${FAIL_ON_WARN:-false}"
RUN_BASELINE_FIRST="${RUN_BASELINE_FIRST:-false}"

STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD:-5}"
MAX_REGRESSION_PCT="${MAX_REGRESSION_PCT:-20}"
EPSILON_MS="${EPSILON_MS:-0.05}"

DIFF_REPORT="${DIFF_REPORT:-${REPORT_DIR}/a14_gate_diff_${TIMESTAMP}.md}"
GATE_REPORT="${GATE_REPORT:-${REPORT_DIR}/a14_gate_${TIMESTAMP}.md}"

mkdir -p "${REPORT_DIR}" "${BASELINE_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

latest_a11_report() {
  ls -1t "${REPORT_DIR}"/a11_baseline_*.md 2>/dev/null | head -n 1 || true
}

ensure_file() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo "[A14] file not found: ${file}"
    exit 1
  fi
}

capture_reference() {
  local from_report="${SOURCE_REPORT}"
  if [[ -z "${from_report}" ]]; then
    from_report="$(latest_a11_report)"
  fi
  if [[ -z "${from_report}" ]]; then
    echo "[A14] no A11 report found to capture."
    echo "[A14] run ./scripts/a11_baseline.sh first or set SOURCE_REPORT."
    exit 1
  fi
  ensure_file "${from_report}"

  cp "${from_report}" "${REFERENCE_REPORT}"

  {
    echo "# A14 基线固化记录"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "- 来源报告：${from_report}"
    echo "- 固化路径：${REFERENCE_REPORT}"
    echo
    echo "## 建议"
    echo
    echo "1. 若该基线已确认有效，可提交到仓库用于后续门禁对比。"
    echo "2. 如需刷新基线，重复执行 capture。"
  } >"${GATE_REPORT}"

  echo "[A14] reference captured: ${REFERENCE_REPORT}"
  echo "[A14] report generated: ${GATE_REPORT}"
  echo "[A14] SUCCESS"
}

run_gate_check() {
  if is_true "${RUN_BASELINE_FIRST}"; then
    echo "[A14] running A11 baseline first ..."
    "${ROOT_DIR}/scripts/a11_baseline.sh"
  fi

  ensure_file "${REFERENCE_REPORT}"
  local candidate="${CANDIDATE_REPORT}"
  if [[ -z "${candidate}" ]]; then
    candidate="$(latest_a11_report)"
  fi
  if [[ -z "${candidate}" ]]; then
    echo "[A14] no candidate A11 report found."
    echo "[A14] run ./scripts/a11_baseline.sh first or set CANDIDATE_REPORT."
    exit 1
  fi
  ensure_file "${candidate}"

  echo "[A14] reference=${REFERENCE_REPORT}"
  echo "[A14] candidate=${candidate}"

  local a13_exit=0
  set +e
  BASE_REPORT="${REFERENCE_REPORT}" \
  CANDIDATE_REPORT="${candidate}" \
  OUTPUT_REPORT="${DIFF_REPORT}" \
  STRICT="${STRICT}" \
  STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD}" \
  MAX_REGRESSION_PCT="${MAX_REGRESSION_PCT}" \
  EPSILON_MS="${EPSILON_MS}" \
  "${ROOT_DIR}/scripts/a13_baseline_diff.sh"
  a13_exit=$?
  set -e

  local conclusion
  conclusion="$(awk -F'：' '/^- 结论：/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' "${DIFF_REPORT}" || true)"
  if [[ -z "${conclusion}" ]]; then
    conclusion="unknown"
  fi

  local gate_status="pass"
  local gate_reason="all checks passed"

  if [[ "${a13_exit}" -ne 0 ]]; then
    gate_status="fail"
    gate_reason="A13 strict check failed"
  elif [[ "${conclusion}" == "fail" ]]; then
    gate_status="fail"
    gate_reason="baseline diff conclusion is fail"
  elif [[ "${conclusion}" == "warn" ]]; then
    if is_true "${FAIL_ON_WARN}"; then
      gate_status="fail"
      gate_reason="warn treated as failure by FAIL_ON_WARN=true"
    else
      gate_status="warn"
      gate_reason="non-blocking regression warning"
    fi
  fi

  {
    echo "# A14 参考基线门禁报告"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "- gate 结论：${gate_status}"
    echo "- gate 原因：${gate_reason}"
    echo "- reference：${REFERENCE_REPORT}"
    echo "- candidate：${candidate}"
    echo "- diff 报告：${DIFF_REPORT}"
    echo "- strict：${STRICT}"
    echo "- failOnWarn：${FAIL_ON_WARN}"
    echo
    echo "## 建议"
    echo
    if [[ "${gate_status}" == "pass" ]]; then
      echo "1. 当前候选版本通过基线门禁，可继续推进。"
    elif [[ "${gate_status}" == "warn" ]]; then
      echo "1. 发现轻度回归，建议复核后再决定是否推进。"
    else
      echo "1. 门禁未通过，建议先优化回归指标再推进。"
    fi
  } >"${GATE_REPORT}"

  echo "[A14] report generated: ${GATE_REPORT}"
  echo "[A14] diff report: ${DIFF_REPORT}"

  if [[ "${gate_status}" == "fail" ]]; then
    exit 1
  fi
  echo "[A14] SUCCESS"
}

case "${MODE}" in
  capture)
    capture_reference
    ;;
  check)
    run_gate_check
    ;;
  *)
    echo "[A14] unsupported MODE=${MODE}"
    echo "[A14] supported modes: capture | check"
    exit 1
    ;;
esac
