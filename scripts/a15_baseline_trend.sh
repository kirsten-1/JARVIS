#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/docs/reports"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

RUN_BASELINE_FIRST="${RUN_BASELINE_FIRST:-false}"
REPORT_PATTERN="${REPORT_PATTERN:-${REPORT_DIR}/a11_baseline_*.md}"
OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a15_baseline_trend_${TIMESTAMP}.md}"

WINDOW="${WINDOW:-7}"
TREND_ROWS="${TREND_ROWS:-10}"
MIN_REPORTS="${MIN_REPORTS:-2}"

STRICT="${STRICT:-false}"
FAIL_ON_WARN="${FAIL_ON_WARN:-false}"
STABLE_PCT_THRESHOLD="${STABLE_PCT_THRESHOLD:-8}"
MAX_DRIFT_PCT="${MAX_DRIFT_PCT:-20}"
EPSILON_MS="${EPSILON_MS:-0.05}"

CORE_METRICS=("health" "conversation_list_hot" "message_list_hot" "agent_loop")
METRICS=("health" "conversation_list_hot" "message_list_hot" "conversation_list_cold" "message_list_cold" "agent_loop")

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

is_number() {
  local v="${1:-}"
  [[ "${v}" =~ ^-?[0-9]+([.][0-9]+)?$ ]]
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

report_time() {
  local report="$1"
  local t
  t="$(awk -F'：' '/^- 时间：/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' "${report}" || true)"
  if [[ -z "${t}" ]]; then
    t="$(basename "${report}")"
  fi
  echo "${t}"
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

calc_abs() {
  local v="$1"
  awk -v x="${v}" 'BEGIN { if (x < 0) x = -x; printf "%.2f", x }'
}

median_of_file() {
  local file="$1"
  local sorted count idx
  sorted="${TMP_DIR}/median_sorted_$(basename "${file}").txt"
  sort -n "${file}" >"${sorted}"
  count="$(wc -l <"${sorted}" | tr -d ' ')"
  if [[ "${count}" -eq 0 ]]; then
    echo "n/a"
    return 0
  fi
  idx=$(( (count - 1) * 50 / 100 + 1 ))
  sed -n "${idx}p" "${sorted}"
}

contains_metric() {
  local metric="$1"
  shift
  for m in "$@"; do
    if [[ "${m}" == "${metric}" ]]; then
      return 0
    fi
  done
  return 1
}

if is_true "${RUN_BASELINE_FIRST}"; then
  echo "[A15] RUN_BASELINE_FIRST=true, running A11 baseline ..."
  "${ROOT_DIR}/scripts/a11_baseline.sh"
fi

REPORTS=()
while IFS= read -r report_file; do
  REPORTS+=("${report_file}")
done < <(compgen -G "${REPORT_PATTERN}" | sort || true)
if [[ "${#REPORTS[@]}" -lt "${MIN_REPORTS}" ]]; then
  echo "[A15] need at least ${MIN_REPORTS} reports, found ${#REPORTS[@]}"
  echo "[A15] pattern=${REPORT_PATTERN}"
  exit 1
fi

LAST_INDEX=$(( ${#REPORTS[@]} - 1 ))
LATEST_REPORT="${REPORTS[${LAST_INDEX}]}"

echo "[A15] reports found: ${#REPORTS[@]}"
echo "[A15] latest report: ${LATEST_REPORT}"

SERIES_FILE="${TMP_DIR}/a15_series.txt"
: >"${SERIES_FILE}"
for report in "${REPORTS[@]}"; do
  t="$(report_time "${report}")"
  health_p90="$(metric_value "${report}" "health" "P90")"
  conv_hot_p90="$(metric_value "${report}" "conversation_list_hot" "P90")"
  msg_hot_p90="$(metric_value "${report}" "message_list_hot" "P90")"
  conv_cold_p90="$(metric_value "${report}" "conversation_list_cold" "P90")"
  msg_cold_p90="$(metric_value "${report}" "message_list_cold" "P90")"
  agent_p90="$(metric_value "${report}" "agent_loop" "P90")"
  echo "${report}|${t}|${health_p90}|${conv_hot_p90}|${msg_hot_p90}|${conv_cold_p90}|${msg_cold_p90}|${agent_p90}" >>"${SERIES_FILE}"
done

SUMMARY_FILE="${TMP_DIR}/a15_summary.txt"
: >"${SUMMARY_FILE}"

overall_status="pass"
for metric in "${METRICS[@]}"; do
  idx=0
  case "${metric}" in
    health) idx=3 ;;
    conversation_list_hot) idx=4 ;;
    message_list_hot) idx=5 ;;
    conversation_list_cold) idx=6 ;;
    message_list_cold) idx=7 ;;
    agent_loop) idx=8 ;;
  esac

  metric_values_file="${TMP_DIR}/${metric}_values.txt"
  : >"${metric_values_file}"
  awk -F'|' -v idx="${idx}" '
    function trim(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s);
      return s;
    }
    {
      v = trim($idx);
      if (v ~ /^-?[0-9]+([.][0-9]+)?$/) {
        print v;
      }
    }
  ' "${SERIES_FILE}" >"${metric_values_file}"

  count="$(wc -l <"${metric_values_file}" | tr -d ' ')"
  if [[ "${count}" -eq 0 ]]; then
    echo "${metric}|0|n/a|n/a|n/a|n/a|skipped|" >>"${SUMMARY_FILE}"
    continue
  fi

  latest="$(tail -n 1 "${metric_values_file}")"
  best="$(sort -n "${metric_values_file}" | head -n 1)"
  worst="$(sort -n "${metric_values_file}" | tail -n 1)"
  window_values_file="${TMP_DIR}/${metric}_window.txt"
  tail -n "${WINDOW}" "${metric_values_file}" >"${window_values_file}"
  window_median="$(median_of_file "${window_values_file}")"

  if ! is_number "${window_median}" || ! is_number "${latest}"; then
    echo "${metric}|${count}|${latest}|${best}|${worst}|${window_median}|skipped|" >>"${SUMMARY_FILE}"
    continue
  fi

  drift_pct="$(calc_pct "${window_median}" "${latest}")"
  abs_drift_pct="$(calc_abs "${drift_pct}")"
  verdict="stable"
  fail_reason=""

  if awk -v d="${latest}" -v m="${window_median}" -v e="${EPSILON_MS}" 'BEGIN { exit((d - m) < -e ? 0 : 1) }'; then
    verdict="improved"
  elif awk -v p="${abs_drift_pct}" -v t="${STABLE_PCT_THRESHOLD}" 'BEGIN { exit(p <= t ? 0 : 1) }'; then
    verdict="stable"
  else
    verdict="regressed"
  fi

  if contains_metric "${metric}" "${CORE_METRICS[@]}"; then
    if [[ "${verdict}" == "regressed" ]] && awk -v p="${drift_pct}" -v t="${MAX_DRIFT_PCT}" 'BEGIN { exit(p > t ? 0 : 1) }'; then
      fail_reason="core drift > ${MAX_DRIFT_PCT}%"
      overall_status="fail"
    elif [[ "${verdict}" == "regressed" && "${overall_status}" != "fail" ]]; then
      overall_status="warn"
    fi
  fi

  echo "${metric}|${count}|${latest}|${best}|${worst}|${window_median}|${drift_pct}|${verdict}|${fail_reason}" >>"${SUMMARY_FILE}"
done

if [[ "${overall_status}" == "pass" ]]; then
  :
elif [[ "${overall_status}" == "warn" ]] && is_true "${FAIL_ON_WARN}"; then
  overall_status="fail"
fi

{
  echo "# A15 基线趋势报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${overall_status}"
  echo "- 报告数量：${#REPORTS[@]}"
  echo "- 报告匹配：\`${REPORT_PATTERN}\`"
  echo "- 最新报告：${LATEST_REPORT}"
  echo "- window：${WINDOW}"
  echo "- stable 阈值：±${STABLE_PCT_THRESHOLD}%"
  echo "- core 最大漂移阈值：${MAX_DRIFT_PCT}%"
  echo
  echo "## 趋势摘要（P90）"
  echo
  echo "| Metric | Samples | Latest | Best | Worst | Window Median | Drift vs Median (%) | Verdict |"
  echo "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
  while IFS='|' read -r metric count latest best worst median drift verdict reason; do
    line_verdict="${verdict}"
    if [[ -n "${reason:-}" ]]; then
      line_verdict="${line_verdict} (${reason})"
    fi
    echo "| ${metric} | ${count} | ${latest} | ${best} | ${worst} | ${median} | ${drift:-n/a} | ${line_verdict} |"
  done <"${SUMMARY_FILE}"
  echo
  echo "## 最近趋势（按报告）"
  echo
  echo "| Report Time | health | conversation_hot | message_hot | agent_loop |"
  echo "| --- | ---: | ---: | ---: | ---: |"
  tail -n "${TREND_ROWS}" "${SERIES_FILE}" | while IFS='|' read -r report time h ch mh cc mc ag; do
    echo "| ${time} | ${h:-n/a} | ${ch:-n/a} | ${mh:-n/a} | ${ag:-n/a} |"
  done
  echo
  echo "## 建议"
  echo
  if [[ "${overall_status}" == "pass" ]]; then
    echo "1. 指标整体稳定或提升，可继续推进版本。"
  elif [[ "${overall_status}" == "warn" ]]; then
    echo "1. 出现回归趋势，建议结合 A13/A14 做针对性复核。"
  else
    echo "1. 核心指标出现明显漂移，建议先优化后再推进。"
  fi
  echo "2. 若要将趋势分析作为门禁，可启用 \`STRICT=true\`。"
} >"${OUTPUT_REPORT}"

echo "[A15] report generated: ${OUTPUT_REPORT}"

if is_true "${STRICT}" && [[ "${overall_status}" == "fail" ]]; then
  echo "[A15] STRICT mode enabled and trend status is fail"
  exit 1
fi

if is_true "${STRICT}" && is_true "${FAIL_ON_WARN}" && [[ "${overall_status}" == "warn" ]]; then
  echo "[A15] STRICT+FAIL_ON_WARN enabled and trend status is warn"
  exit 1
fi

echo "[A15] SUCCESS"
