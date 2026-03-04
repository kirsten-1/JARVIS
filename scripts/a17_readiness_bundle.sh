#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_REPORT_DIR="${ROOT_DIR}/docs/reports"
REPORT_DIR="${REPORT_DIR:-${DEFAULT_REPORT_DIR}}"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"

OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a17_readiness_bundle_${TIMESTAMP}.md}"
STRICT="${STRICT:-true}"
FAIL_ON_WARN="${FAIL_ON_WARN:-false}"
FAIL_ON_MISSING="${FAIL_ON_MISSING:-true}"
REQUIRED_CHECKS="${REQUIRED_CHECKS:-a08,a11,a13,a15,a16}"
OPTIONAL_CHECKS="${OPTIONAL_CHECKS:-a14}"

mkdir -p "${REPORT_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

csv_contains() {
  local csv="$1"
  local target="$2"
  local item
  IFS=',' read -r -a _items <<<"${csv}"
  for item in "${_items[@]}"; do
    item="$(echo "${item}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [[ "${item}" == "${target}" ]]; then
      return 0
    fi
  done
  return 1
}

latest_report() {
  local pattern="$1"
  ls -1t ${pattern} 2>/dev/null | head -n 1 || true
}

extract_line_value() {
  local file="$1"
  local key="$2"
  if [[ ! -f "${file}" ]]; then
    echo ""
    return 0
  fi
  awk -F'：' -v k="${key}" '
    function trim(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s);
      return s;
    }
    {
      if (index($0, k) == 1) {
        print trim($2);
        exit;
      }
    }
  ' "${file}"
}

normalize_status() {
  local check="$1"
  local raw="$2"
  local has_file="$3"

  if [[ "${has_file}" != "true" ]]; then
    echo "missing"
    return 0
  fi

  case "${check}" in
    a08)
      case "${raw}" in
        success) echo "pass" ;;
        failed|fail) echo "fail" ;;
        *) echo "warn" ;;
      esac
      ;;
    a11)
      echo "pass"
      ;;
    a13|a14|a15|a16)
      case "${raw}" in
        pass) echo "pass" ;;
        warn) echo "warn" ;;
        fail|failed) echo "fail" ;;
        *) echo "warn" ;;
      esac
      ;;
    *)
      echo "warn"
      ;;
  esac
}

check_report_path() {
  local check="$1"
  case "${check}" in
    a08) latest_report "${REPORT_DIR}/a08_prepush_*.md" ;;
    a11) latest_report "${REPORT_DIR}/a11_baseline_*.md" ;;
    a13) latest_report "${REPORT_DIR}/a13_baseline_diff_*.md" ;;
    a14) latest_report "${REPORT_DIR}/a14_gate_*.md" ;;
    a15) latest_report "${REPORT_DIR}/a15_baseline_trend_*.md" ;;
    a16) latest_report "${REPORT_DIR}/a16_trend_gate_*.md" ;;
    *) echo "" ;;
  esac
}

check_raw_status() {
  local check="$1"
  local report="$2"
  case "${check}" in
    a08|a13|a15) extract_line_value "${report}" "- 结论：" ;;
    a14|a16) extract_line_value "${report}" "- gate 结论：" ;;
    a11)
      if [[ -n "${report}" ]]; then
        echo "available"
      else
        echo ""
      fi
      ;;
    *)
      echo ""
      ;;
  esac
}

CHECKS=("a08" "a11" "a13" "a14" "a15" "a16")
SUMMARY_FILE="$(mktemp)"
trap 'rm -f "${SUMMARY_FILE}"' EXIT

overall_status="pass"
overall_reason="all required checks passed"

for check in "${CHECKS[@]}"; do
  report_path="$(check_report_path "${check}")"
  required="false"
  optional="false"
  if csv_contains "${REQUIRED_CHECKS}" "${check}"; then
    required="true"
  elif csv_contains "${OPTIONAL_CHECKS}" "${check}"; then
    optional="true"
  fi

  has_file="false"
  if [[ -n "${report_path}" && -f "${report_path}" ]]; then
    has_file="true"
  fi

  raw_status="$(check_raw_status "${check}" "${report_path}")"
  normalized_status="$(normalize_status "${check}" "${raw_status}" "${has_file}")"
  note=""

  if [[ "${required}" == "true" ]]; then
    if [[ "${normalized_status}" == "missing" ]]; then
      if is_true "${FAIL_ON_MISSING}"; then
        normalized_status="fail"
        note="required report missing"
        overall_status="fail"
        overall_reason="required report missing"
      else
        normalized_status="warn"
        note="required report missing (non-blocking)"
        if [[ "${overall_status}" == "pass" ]]; then
          overall_status="warn"
          overall_reason="required report missing (non-blocking)"
        fi
      fi
    elif [[ "${normalized_status}" == "fail" ]]; then
      note="required check failed"
      overall_status="fail"
      overall_reason="required check failed"
    elif [[ "${normalized_status}" == "warn" ]]; then
      if is_true "${FAIL_ON_WARN}"; then
        normalized_status="fail"
        note="warn treated as fail"
        overall_status="fail"
        overall_reason="warn treated as fail"
      else
        note="required check warning"
        if [[ "${overall_status}" == "pass" ]]; then
          overall_status="warn"
          overall_reason="required check warning"
        fi
      fi
    fi
  elif [[ "${optional}" == "true" ]]; then
    if [[ "${normalized_status}" == "missing" ]]; then
      normalized_status="warn"
      note="optional report missing"
      if [[ "${overall_status}" == "pass" ]]; then
        overall_status="warn"
        overall_reason="optional report missing"
      fi
    elif [[ "${normalized_status}" == "fail" ]]; then
      note="optional check failed"
      if [[ "${overall_status}" == "pass" ]]; then
        overall_status="warn"
        overall_reason="optional check failed"
      fi
    elif [[ "${normalized_status}" == "warn" && "${overall_status}" == "pass" ]]; then
      note="optional check warning"
      overall_status="warn"
      overall_reason="optional check warning"
    fi
  else
    # Not required and not optional: informational only.
    if [[ "${normalized_status}" == "missing" ]]; then
      normalized_status="warn"
      note="not included in required/optional set"
    fi
  fi

  report_display="${report_path}"
  if [[ -z "${report_display}" ]]; then
    report_display="n/a"
  fi
  raw_display="${raw_status}"
  if [[ -z "${raw_display}" ]]; then
    raw_display="n/a"
  fi

  echo "${check}|${required}|${optional}|${report_display}|${raw_display}|${normalized_status}|${note}" >>"${SUMMARY_FILE}"
done

{
  echo "# A17 发布就绪总览报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${overall_status}"
  echo "- 原因：${overall_reason}"
  echo "- STRICT：${STRICT}"
  echo "- FAIL_ON_WARN：${FAIL_ON_WARN}"
  echo "- FAIL_ON_MISSING：${FAIL_ON_MISSING}"
  echo "- REQUIRED_CHECKS：\`${REQUIRED_CHECKS}\`"
  echo "- OPTIONAL_CHECKS：\`${OPTIONAL_CHECKS}\`"
  echo
  echo "## 检查结果"
  echo
  echo "| Check | Required | Optional | Raw | Status | Note | Report |"
  echo "| --- | --- | --- | --- | --- | --- | --- |"
  while IFS='|' read -r check required optional report raw status note; do
    echo "| ${check} | ${required} | ${optional} | ${raw} | ${status} | ${note:-} | ${report} |"
  done <"${SUMMARY_FILE}"
  echo
  echo "## 建议"
  echo
  if [[ "${overall_status}" == "pass" ]]; then
    echo "1. 当前质量门禁通过，可进入下一阶段。"
  elif [[ "${overall_status}" == "warn" ]]; then
    echo "1. 存在告警项，建议先复核后再继续推进。"
  else
    echo "1. 存在阻断项，建议修复后重新生成报告。"
  fi
  echo "2. 如需更严格策略，可设置 \`FAIL_ON_WARN=true\`。"
} >"${OUTPUT_REPORT}"

echo "[A17] report generated: ${OUTPUT_REPORT}"

if [[ "${overall_status}" == "fail" ]] && is_true "${STRICT}"; then
  echo "[A17] STRICT mode enabled and readiness status is fail"
  exit 1
fi

echo "[A17] SUCCESS"
