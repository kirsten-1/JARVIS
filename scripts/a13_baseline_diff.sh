#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

BASE_REPORT="${BASE_REPORT:-}"
CANDIDATE_REPORT="${CANDIDATE_REPORT:-}"
OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a13_baseline_diff_${TIMESTAMP}.md}"

STRICT="${STRICT:-false}"
STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD:-5}"
MAX_REGRESSION_PCT="${MAX_REGRESSION_PCT:-20}"
EPSILON_MS="${EPSILON_MS:-0.05}"

CORE_METRICS=("health" "conversation_list_hot" "message_list_hot" "agent_loop")
METRICS=("health" "conversation_list_cold" "conversation_list_hot" "message_list_cold" "message_list_hot" "agent_loop")

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

resolve_reports() {
  if [[ -n "${BASE_REPORT}" && -n "${CANDIDATE_REPORT}" ]]; then
    return 0
  fi

  local reports
  reports="$(ls -1t "${REPORT_DIR}"/a11_baseline_*.md 2>/dev/null || true)"
  if [[ -z "${reports}" ]]; then
    echo "[A13] no A11 reports found under ${REPORT_DIR}"
    echo "[A13] set BASE_REPORT and CANDIDATE_REPORT manually."
    exit 1
  fi

  if [[ -z "${CANDIDATE_REPORT}" ]]; then
    CANDIDATE_REPORT="$(echo "${reports}" | sed -n '1p')"
  fi
  if [[ -z "${BASE_REPORT}" ]]; then
    BASE_REPORT="$(echo "${reports}" | sed -n '2p')"
  fi

  if [[ -z "${BASE_REPORT}" || -z "${CANDIDATE_REPORT}" ]]; then
    echo "[A13] need at least two A11 reports to diff."
    echo "[A13] found candidate=${CANDIDATE_REPORT:-<none>}, base=${BASE_REPORT:-<none>}"
    exit 1
  fi
}

ensure_file() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo "[A13] report not found: ${file}"
    exit 1
  fi
}

metric_col_index() {
  local col="$1"
  case "${col}" in
    Count) echo 3 ;;
    Avg) echo 4 ;;
    Min) echo 5 ;;
    P50) echo 6 ;;
    P90) echo 7 ;;
    P99) echo 8 ;;
    Max) echo 9 ;;
    *) echo 0 ;;
  esac
}

metric_value() {
  local report="$1"
  local metric="$2"
  local col="$3"
  local idx
  idx="$(metric_col_index "${col}")"
  if [[ "${idx}" -eq 0 ]]; then
    echo ""
    return 0
  fi
  awk -F'|' -v metric="${metric}" -v idx="${idx}" '
    function trim(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s);
      return s;
    }
    /^\|/ {
      key = trim($2);
      if (key == metric) {
        print trim($idx);
        exit;
      }
    }
  ' "${report}"
}

is_number() {
  local v="$1"
  [[ "${v}" =~ ^-?[0-9]+([.][0-9]+)?$ ]]
}

calc_delta() {
  local base="$1"
  local candidate="$2"
  awk -v b="${base}" -v c="${candidate}" 'BEGIN { printf "%.2f", c - b }'
}

calc_pct() {
  local base="$1"
  local candidate="$2"
  awk -v b="${base}" -v c="${candidate}" '
    BEGIN {
      if (b == 0) {
        printf "0.00";
      } else {
        printf "%.2f", ((c - b) * 100.0 / b);
      }
    }
  '
}

compare_metric() {
  local metric="$1"
  local base_p90="$2"
  local cand_p90="$3"
  local delta pct abs_pct verdict

  if ! is_number "${base_p90}" || ! is_number "${cand_p90}"; then
    echo "${metric}|${base_p90:-n/a}|${cand_p90:-n/a}|n/a|n/a|skipped|"
    return 0
  fi

  delta="$(calc_delta "${base_p90}" "${cand_p90}")"
  pct="$(calc_pct "${base_p90}" "${cand_p90}")"
  abs_pct="$(awk -v p="${pct}" 'BEGIN { if (p < 0) p = -p; printf "%.2f", p }')"

  if awk -v d="${delta}" -v e="${EPSILON_MS}" 'BEGIN { exit(d < -e ? 0 : 1) }'; then
    verdict="improved"
  elif awk -v p="${abs_pct}" -v t="${STABLE_PCT_THRESHOLD}" 'BEGIN { exit(p <= t ? 0 : 1) }'; then
    verdict="stable"
  else
    verdict="regressed"
  fi

  local fail_reason=""
  for core in "${CORE_METRICS[@]}"; do
    if [[ "${metric}" == "${core}" ]]; then
      if [[ "${verdict}" == "regressed" ]] && awk -v p="${pct}" -v t="${MAX_REGRESSION_PCT}" 'BEGIN { exit(p > t ? 0 : 1) }'; then
        fail_reason="core regression > ${MAX_REGRESSION_PCT}%"
      fi
      break
    fi
  done

  echo "${metric}|${base_p90}|${cand_p90}|${delta}|${pct}|${verdict}|${fail_reason}"
}

resolve_reports
ensure_file "${BASE_REPORT}"
ensure_file "${CANDIDATE_REPORT}"

echo "[A13] base=${BASE_REPORT}"
echo "[A13] candidate=${CANDIDATE_REPORT}"

RESULTS_FILE="${TMP_DIR}/a13_results.txt"
: >"${RESULTS_FILE}"

OVERALL_STATUS="pass"
for metric in "${METRICS[@]}"; do
  base_p90="$(metric_value "${BASE_REPORT}" "${metric}" "P90")"
  cand_p90="$(metric_value "${CANDIDATE_REPORT}" "${metric}" "P90")"
  row="$(compare_metric "${metric}" "${base_p90}" "${cand_p90}")"
  echo "${row}" >>"${RESULTS_FILE}"

  reason="$(echo "${row}" | awk -F'|' '{print $7}')"
  verdict="$(echo "${row}" | awk -F'|' '{print $6}')"
  if [[ -n "${reason}" ]]; then
    OVERALL_STATUS="fail"
  elif [[ "${verdict}" == "regressed" && "${OVERALL_STATUS}" != "fail" ]]; then
    OVERALL_STATUS="warn"
  fi
done

{
  echo "# A13 基线差异报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${OVERALL_STATUS}"
  echo "- base：${BASE_REPORT}"
  echo "- candidate：${CANDIDATE_REPORT}"
  echo "- stable 阈值：±${STABLE_PCT_THRESHOLD}%"
  echo "- core 最大回归阈值：${MAX_REGRESSION_PCT}%"
  echo
  echo "## P90 对比（ms）"
  echo
  echo "| Metric | Base P90 | Candidate P90 | Delta (ms) | Delta (%) | Verdict |"
  echo "| --- | ---: | ---: | ---: | ---: | --- |"
  while IFS='|' read -r metric base_p90 cand_p90 delta pct verdict reason; do
    if [[ -n "${reason}" ]]; then
      verdict="${verdict} (${reason})"
    fi
    echo "| ${metric} | ${base_p90} | ${cand_p90} | ${delta} | ${pct} | ${verdict} |"
  done <"${RESULTS_FILE}"
  echo
  echo "## 建议"
  echo
  if [[ "${OVERALL_STATUS}" == "pass" ]]; then
    echo "1. 当前版本与基线相比稳定或提升，可继续推进。"
  elif [[ "${OVERALL_STATUS}" == "warn" ]]; then
    echo "1. 存在回归但未触发 core fail，建议复核最近改动并继续观察。"
  else
    echo "1. 存在核心指标明显回归，建议阻断发布并先回归优化。"
  fi
  echo "2. 若要将差异比较作为门禁，可设置 \`STRICT=true\`。"
} >"${OUTPUT_REPORT}"

echo "[A13] report generated: ${OUTPUT_REPORT}"

if is_true "${STRICT}" && [[ "${OVERALL_STATUS}" == "fail" ]]; then
  echo "[A13] STRICT mode enabled and regression failed"
  exit 1
fi

echo "[A13] SUCCESS"
