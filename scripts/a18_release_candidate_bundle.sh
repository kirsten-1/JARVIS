#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_REPORT_DIR="${ROOT_DIR}/docs/reports"
REPORT_DIR="${REPORT_DIR:-${DEFAULT_REPORT_DIR}}"
BUNDLE_DIR="${BUNDLE_DIR:-${REPORT_DIR}/bundles}"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a18_release_candidate_bundle_${TIMESTAMP}.md}"
BUNDLE_NAME="${BUNDLE_NAME:-a18_rc_bundle_${TIMESTAMP}}"
BUNDLE_PATH="${BUNDLE_DIR}/${BUNDLE_NAME}.tar.gz"

STRICT="${STRICT:-true}"
REQUIRE_A17_PASS="${REQUIRE_A17_PASS:-true}"

REQUIRED_CHECKS="${REQUIRED_CHECKS:-a08,a11,a13,a15,a16,a17}"
OPTIONAL_CHECKS="${OPTIONAL_CHECKS:-a14}"

mkdir -p "${REPORT_DIR}" "${BUNDLE_DIR}"

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

report_path_for_check() {
  local check="$1"
  case "${check}" in
    a08) latest_report "${REPORT_DIR}/a08_prepush_*.md" ;;
    a11) latest_report "${REPORT_DIR}/a11_baseline_*.md" ;;
    a13) latest_report "${REPORT_DIR}/a13_baseline_diff_*.md" ;;
    a14) latest_report "${REPORT_DIR}/a14_gate_*.md" ;;
    a15) latest_report "${REPORT_DIR}/a15_baseline_trend_*.md" ;;
    a16) latest_report "${REPORT_DIR}/a16_trend_gate_*.md" ;;
    a17) latest_report "${REPORT_DIR}/a17_readiness_bundle_*.md" ;;
    *) echo "" ;;
  esac
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

normalize_check_status() {
  local check="$1"
  local raw="$2"
  case "${check}" in
    a08)
      case "${raw}" in
        success) echo "pass" ;;
        fail|failed) echo "fail" ;;
        *) echo "warn" ;;
      esac
      ;;
    a11)
      if [[ "${raw}" == "available" ]]; then
        echo "pass"
      else
        echo "warn"
      fi
      ;;
    a13|a14|a15|a16|a17)
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

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  else
    shasum -a 256 "${file}" | awk '{print $1}'
  fi
}

CHECKS=("a08" "a11" "a13" "a14" "a15" "a16" "a17")
SUMMARY_FILE="${TMP_DIR}/a18_summary.txt"
: >"${SUMMARY_FILE}"

overall_status="pass"
overall_reason="bundle ready"

for check in "${CHECKS[@]}"; do
  required="false"
  optional="false"
  if csv_contains "${REQUIRED_CHECKS}" "${check}"; then
    required="true"
  elif csv_contains "${OPTIONAL_CHECKS}" "${check}"; then
    optional="true"
  fi

  report_path="$(report_path_for_check "${check}")"
  has_file="false"
  if [[ -n "${report_path}" && -f "${report_path}" ]]; then
    has_file="true"
  fi

  raw_status="n/a"
  if [[ "${has_file}" == "true" ]]; then
    case "${check}" in
      a08|a13|a15|a17) raw_status="$(extract_line_value "${report_path}" "- 结论：")" ;;
      a14|a16) raw_status="$(extract_line_value "${report_path}" "- gate 结论：")" ;;
      a11) raw_status="available" ;;
      *) raw_status="available" ;;
    esac
    if [[ -z "${raw_status}" ]]; then
      raw_status="unknown"
    fi
  fi

  status="$(normalize_check_status "${check}" "${raw_status}")"
  note=""
  if [[ "${required}" == "true" && "${has_file}" != "true" ]]; then
    status="fail"
    raw_status="missing"
    note="required report missing"
    overall_status="fail"
    overall_reason="required report missing"
  elif [[ "${optional}" == "true" && "${has_file}" != "true" ]]; then
    status="warn"
    raw_status="missing"
    note="optional report missing"
    if [[ "${overall_status}" == "pass" ]]; then
      overall_status="warn"
      overall_reason="optional report missing"
    fi
  elif [[ "${required}" == "true" && "${status}" == "fail" ]]; then
    note="required check failed"
    overall_status="fail"
    overall_reason="required check failed"
  elif [[ "${required}" == "true" && "${status}" == "warn" ]]; then
    note="required check warning"
    if [[ "${overall_status}" == "pass" ]]; then
      overall_status="warn"
      overall_reason="required check warning"
    fi
  elif [[ "${optional}" == "true" && "${status}" == "fail" ]]; then
    note="optional check failed"
    if [[ "${overall_status}" == "pass" ]]; then
      overall_status="warn"
      overall_reason="optional check failed"
    fi
  elif [[ "${optional}" == "true" && "${status}" == "warn" ]]; then
    note="optional check warning"
    if [[ "${overall_status}" == "pass" ]]; then
      overall_status="warn"
      overall_reason="optional check warning"
    fi
  elif [[ "${required}" != "true" && "${optional}" != "true" ]]; then
    status="warn"
    note="not included in required/optional set"
  fi

  echo "${check}|${required}|${optional}|${raw_status}|${status}|${note}|${report_path}" >>"${SUMMARY_FILE}"
done

a17_report="$(awk -F'|' '$1=="a17" {print $7}' "${SUMMARY_FILE}")"
a17_conclusion="n/a"
if [[ -n "${a17_report}" && -f "${a17_report}" ]]; then
  a17_conclusion="$(extract_line_value "${a17_report}" "- 结论：")"
  if [[ -z "${a17_conclusion}" ]]; then
    a17_conclusion="unknown"
  fi
fi

if is_true "${REQUIRE_A17_PASS}"; then
  if [[ "${a17_conclusion}" != "pass" ]]; then
    overall_status="fail"
    overall_reason="a17 conclusion is not pass"
  fi
fi

STAGE_DIR="${TMP_DIR}/${BUNDLE_NAME}"
mkdir -p "${STAGE_DIR}/reports"

MANIFEST_FILE="${STAGE_DIR}/manifest.txt"
CHECKSUM_FILE="${STAGE_DIR}/checksums.sha256"

{
  echo "bundle=${BUNDLE_NAME}"
  echo "generated_at=$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "git_branch=$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  echo "git_commit=$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "a17_conclusion=${a17_conclusion}"
  echo "required_checks=${REQUIRED_CHECKS}"
  echo "optional_checks=${OPTIONAL_CHECKS}"
  echo
  echo "[reports]"
} >"${MANIFEST_FILE}"

: >"${CHECKSUM_FILE}"

while IFS='|' read -r check required optional raw status note report_path; do
  if [[ -n "${report_path}" && -f "${report_path}" ]]; then
    target_name="${check}_$(basename "${report_path}")"
    cp "${report_path}" "${STAGE_DIR}/reports/${target_name}"
    checksum="$(sha256_file "${STAGE_DIR}/reports/${target_name}")"
    echo "${checksum}  reports/${target_name}" >>"${CHECKSUM_FILE}"
    echo "${check}=reports/${target_name}" >>"${MANIFEST_FILE}"
  else
    echo "${check}=n/a" >>"${MANIFEST_FILE}"
  fi
done <"${SUMMARY_FILE}"

(
  cd "${TMP_DIR}"
  tar -czf "${BUNDLE_PATH}" "${BUNDLE_NAME}"
)

bundle_checksum="$(sha256_file "${BUNDLE_PATH}")"

{
  echo "# A18 候选发布证据包报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${overall_status}"
  echo "- 原因：${overall_reason}"
  echo "- STRICT：${STRICT}"
  echo "- REQUIRE_A17_PASS：${REQUIRE_A17_PASS}"
  echo "- A17 结论：${a17_conclusion}"
  echo "- 证据包：${BUNDLE_PATH}"
  echo "- 证据包 SHA256：${bundle_checksum}"
  echo
  echo "## 报告收集状态"
  echo
  echo "| Check | Required | Optional | Raw | Status | Note | Report |"
  echo "| --- | --- | --- | --- | --- | --- | --- |"
  while IFS='|' read -r check required optional raw status note report_path; do
    report_display="${report_path}"
    if [[ -z "${report_display}" ]]; then
      report_display="n/a"
    fi
    echo "| ${check} | ${required} | ${optional} | ${raw} | ${status} | ${note} | ${report_display} |"
  done <"${SUMMARY_FILE}"
  echo
  echo "## 包内容"
  echo
  echo '```text'
  tar -tzf "${BUNDLE_PATH}"
  echo '```'
  echo
  echo "## 建议"
  echo
  if [[ "${overall_status}" == "pass" ]]; then
    echo "1. 证据包已就绪，可作为阶段归档与评审输入。"
  elif [[ "${overall_status}" == "warn" ]]; then
    echo "1. 存在告警项，建议补齐可选报告后重新归档。"
  else
    echo "1. 存在阻断项，建议先修复 required 检查再生成证据包。"
  fi
  echo "2. 本阶段仅归档，不执行 release。"
} >"${OUTPUT_REPORT}"

echo "[A18] report generated: ${OUTPUT_REPORT}"
echo "[A18] bundle generated: ${BUNDLE_PATH}"

if [[ "${overall_status}" == "fail" ]] && is_true "${STRICT}"; then
  echo "[A18] STRICT mode enabled and bundle status is fail"
  exit 1
fi

echo "[A18] SUCCESS"
