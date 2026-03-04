#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_REPORT_DIR="${ROOT_DIR}/docs/reports"
REPORT_DIR="${REPORT_DIR:-${DEFAULT_REPORT_DIR}}"
BUNDLE_DIR="${BUNDLE_DIR:-${REPORT_DIR}/bundles}"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

LIMIT="${LIMIT:-10}"
STRICT="${STRICT:-true}"
FAIL_ON_WARN="${FAIL_ON_WARN:-false}"

OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a19_bundle_audit_${TIMESTAMP}.md}"
INDEX_REPORT="${INDEX_REPORT:-${BUNDLE_DIR}/a19_bundle_index.md}"

mkdir -p "${REPORT_DIR}" "${BUNDLE_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
}

sha_check_file() {
  local checksums_file="$1"
  local root_dir="$2"

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${root_dir}" && sha256sum -c "${checksums_file}" >/dev/null 2>&1)
    return $?
  fi

  local line expected file actual
  while IFS= read -r line; do
    expected="$(echo "${line}" | awk '{print $1}')"
    file="$(echo "${line}" | awk '{print $2}')"
    if [[ -z "${expected}" || -z "${file}" ]]; then
      return 1
    fi
    if [[ ! -f "${root_dir}/${file}" ]]; then
      return 1
    fi
    actual="$(shasum -a 256 "${root_dir}/${file}" | awk '{print $1}')"
    if [[ "${actual}" != "${expected}" ]]; then
      return 1
    fi
  done <"${root_dir}/${checksums_file}"
  return 0
}

bundle_count_files() {
  local root_dir="$1"
  if [[ -d "${root_dir}/reports" ]]; then
    ls -1 "${root_dir}/reports"/*.md 2>/dev/null | wc -l | tr -d ' '
  else
    echo "0"
  fi
}

bundle_manifest_value() {
  local manifest="$1"
  local key="$2"
  awk -F'=' -v k="${key}" '
    function trim(s) {
      gsub(/^[ \t]+|[ \t]+$/, "", s);
      return s;
    }
    $1 == k {
      print trim($2);
      exit;
    }
  ' "${manifest}"
}

SUMMARY_FILE="${TMP_DIR}/a19_summary.txt"
: >"${SUMMARY_FILE}"

bundle_files="$(ls -1t "${BUNDLE_DIR}"/a18_rc_bundle_*.tar.gz 2>/dev/null | head -n "${LIMIT}" || true)"
if [[ -z "${bundle_files}" ]]; then
  {
    echo "# A19 证据包审计报告"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "- 结论：fail"
    echo "- 原因：no A18 bundle found"
    echo "- BUNDLE_DIR：${BUNDLE_DIR}"
    echo
    echo "## 建议"
    echo
    echo "1. 先执行 A18 生成证据包，再执行 A19 审计。"
  } >"${OUTPUT_REPORT}"
  echo "[A19] report generated: ${OUTPUT_REPORT}"
  exit 1
fi

overall_status="pass"
overall_reason="all bundles verified"

while IFS= read -r bundle_path; do
  [[ -n "${bundle_path}" ]] || continue
  bundle_name="$(basename "${bundle_path}")"
  extract_dir="${TMP_DIR}/extract_${bundle_name%.tar.gz}"
  mkdir -p "${extract_dir}"

  status="pass"
  reason=""
  a17_conclusion="n/a"
  report_count="0"
  manifest_path="n/a"
  checksums_path="n/a"
  generated_at="n/a"

  if ! tar -xzf "${bundle_path}" -C "${extract_dir}" >/dev/null 2>&1; then
    status="fail"
    reason="bundle extract failed"
  fi

  if [[ "${status}" == "pass" ]]; then
    root_name="$(tar -tzf "${bundle_path}" | head -n 1 | cut -d'/' -f1)"
    root_path="${extract_dir}/${root_name}"
    manifest_path="${root_path}/manifest.txt"
    checksums_path="${root_path}/checksums.sha256"

    if [[ ! -f "${manifest_path}" ]]; then
      status="fail"
      reason="manifest missing"
    elif [[ ! -f "${checksums_path}" ]]; then
      status="fail"
      reason="checksums missing"
    fi

    if [[ "${status}" == "pass" ]]; then
      if ! sha_check_file "checksums.sha256" "${root_path}"; then
        status="fail"
        reason="checksum verify failed"
      fi
    fi

    generated_at="$(bundle_manifest_value "${manifest_path}" "generated_at")"
    a17_conclusion="$(bundle_manifest_value "${manifest_path}" "a17_conclusion")"
    if [[ -z "${a17_conclusion}" ]]; then
      a17_conclusion="unknown"
    fi
    if [[ -z "${generated_at}" ]]; then
      generated_at="n/a"
    fi
    report_count="$(bundle_count_files "${root_path}")"

    if [[ "${status}" == "pass" && "${a17_conclusion}" != "pass" ]]; then
      status="warn"
      reason="a17 conclusion is ${a17_conclusion}"
    fi
  fi

  if [[ "${status}" == "fail" ]]; then
    overall_status="fail"
    overall_reason="at least one bundle failed verification"
  elif [[ "${status}" == "warn" && "${overall_status}" == "pass" ]]; then
    overall_status="warn"
    overall_reason="at least one bundle has warning"
  fi

  echo "${bundle_name}|${status}|${reason}|${a17_conclusion}|${report_count}|${generated_at}|${bundle_path}" >>"${SUMMARY_FILE}"
done <<<"${bundle_files}"

if [[ "${overall_status}" == "warn" ]] && is_true "${FAIL_ON_WARN}"; then
  overall_status="fail"
  overall_reason="warn treated as fail"
fi

{
  echo "# A19 证据包审计报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${overall_status}"
  echo "- 原因：${overall_reason}"
  echo "- LIMIT：${LIMIT}"
  echo "- STRICT：${STRICT}"
  echo "- FAIL_ON_WARN：${FAIL_ON_WARN}"
  echo "- BUNDLE_DIR：${BUNDLE_DIR}"
  echo
  echo "## 审计结果"
  echo
  echo "| Bundle | Status | Reason | A17 | Reports | Generated At | Path |"
  echo "| --- | --- | --- | --- | ---: | --- | --- |"
  while IFS='|' read -r name status reason a17 count gen path; do
    echo "| ${name} | ${status} | ${reason} | ${a17} | ${count} | ${gen} | ${path} |"
  done <"${SUMMARY_FILE}"
  echo
  echo "## 建议"
  echo
  if [[ "${overall_status}" == "pass" ]]; then
    echo "1. 证据包完整性通过，可作为收官评审输入。"
  elif [[ "${overall_status}" == "warn" ]]; then
    echo "1. 证据包有告警项，建议人工复核后再使用。"
  else
    echo "1. 存在证据包校验失败，建议重新执行 A18 归档。"
  fi
} >"${OUTPUT_REPORT}"

{
  echo "# A19 证据包索引"
  echo
  echo "- 更新时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 来源目录：${BUNDLE_DIR}"
  echo
  echo "| Bundle | Status | A17 | Reports | Generated At |"
  echo "| --- | --- | --- | ---: | --- |"
  while IFS='|' read -r name status reason a17 count gen path; do
    echo "| ${name} | ${status} | ${a17} | ${count} | ${gen} |"
  done <"${SUMMARY_FILE}"
} >"${INDEX_REPORT}"

echo "[A19] report generated: ${OUTPUT_REPORT}"
echo "[A19] index generated: ${INDEX_REPORT}"

if [[ "${overall_status}" == "fail" ]] && is_true "${STRICT}"; then
  echo "[A19] STRICT mode enabled and audit status is fail"
  exit 1
fi

if [[ "${overall_status}" == "warn" ]] && is_true "${STRICT}" && is_true "${FAIL_ON_WARN}"; then
  echo "[A19] STRICT+FAIL_ON_WARN enabled and audit status is warn"
  exit 1
fi

echo "[A19] SUCCESS"
