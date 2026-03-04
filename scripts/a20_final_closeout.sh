#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_REPORT_DIR="${ROOT_DIR}/docs/reports"
REPORT_DIR="${REPORT_DIR:-${DEFAULT_REPORT_DIR}}"
BUNDLE_DIR="${BUNDLE_DIR:-${REPORT_DIR}/bundles}"
TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

STRICT="${STRICT:-true}"
RUN_A19_FIRST="${RUN_A19_FIRST:-false}"

# 默认不自动发布：保持你当前策略“先完成整个 JARVIS 再 release”
ALLOW_RELEASE="${ALLOW_RELEASE:-false}"

OUTPUT_REPORT="${OUTPUT_REPORT:-${REPORT_DIR}/a20_final_closeout_${TIMESTAMP}.md}"
SNAPSHOT_JSON="${SNAPSHOT_JSON:-${BUNDLE_DIR}/a20_closeout_snapshot_${TIMESTAMP}.json}"
PACKAGE_PATH="${PACKAGE_PATH:-${BUNDLE_DIR}/a20_closeout_package_${TIMESTAMP}.tar.gz}"

mkdir -p "${REPORT_DIR}" "${BUNDLE_DIR}"

is_true() {
  local value="${1:-}"
  value="$(echo "${value}" | tr '[:upper:]' '[:lower:]')"
  [[ "${value}" == "1" || "${value}" == "true" || "${value}" == "yes" || "${value}" == "on" ]]
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

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  else
    shasum -a 256 "${file}" | awk '{print $1}'
  fi
}

if is_true "${RUN_A19_FIRST}"; then
  echo "[A20] RUN_A19_FIRST=true, running A19 audit ..."
  "${ROOT_DIR}/scripts/a19_bundle_audit.sh"
fi

A17_REPORT="$(latest_report "${REPORT_DIR}/a17_readiness_bundle_*.md")"
A18_REPORT="$(latest_report "${REPORT_DIR}/a18_release_candidate_bundle_*.md")"
A19_REPORT="$(latest_report "${REPORT_DIR}/a19_bundle_audit_*.md")"

if [[ -z "${A17_REPORT}" || -z "${A18_REPORT}" || -z "${A19_REPORT}" ]]; then
  {
    echo "# A20 收官决策报告"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "- 结论：fail"
    echo "- 决策：hold"
    echo "- 原因：missing required reports (A17/A18/A19)"
    echo
    echo "## 报告状态"
    echo
    echo "- A17：${A17_REPORT:-n/a}"
    echo "- A18：${A18_REPORT:-n/a}"
    echo "- A19：${A19_REPORT:-n/a}"
  } >"${OUTPUT_REPORT}"
  echo "[A20] report generated: ${OUTPUT_REPORT}"
  exit 1
fi

A17_STATUS="$(extract_line_value "${A17_REPORT}" "- 结论：")"
A18_STATUS="$(extract_line_value "${A18_REPORT}" "- 结论：")"
A19_STATUS="$(extract_line_value "${A19_REPORT}" "- 结论：")"

A18_BUNDLE_PATH="$(extract_line_value "${A18_REPORT}" "- 证据包：")"
A18_BUNDLE_SHA="$(extract_line_value "${A18_REPORT}" "- 证据包 SHA256：")"
A19_REASON="$(extract_line_value "${A19_REPORT}" "- 原因：")"

if [[ -z "${A17_STATUS}" ]]; then A17_STATUS="unknown"; fi
if [[ -z "${A18_STATUS}" ]]; then A18_STATUS="unknown"; fi
if [[ -z "${A19_STATUS}" ]]; then A19_STATUS="unknown"; fi
if [[ -z "${A19_REASON}" ]]; then A19_REASON="n/a"; fi
if [[ -z "${A18_BUNDLE_PATH}" ]]; then A18_BUNDLE_PATH="n/a"; fi
if [[ -z "${A18_BUNDLE_SHA}" ]]; then A18_BUNDLE_SHA="n/a"; fi

quality_status="pass"
quality_reason="all closeout gates passed"

if [[ "${A17_STATUS}" != "pass" || "${A18_STATUS}" != "pass" || "${A19_STATUS}" != "pass" ]]; then
  quality_status="fail"
  quality_reason="at least one gate is not pass"
fi

if [[ "${quality_status}" == "pass" ]]; then
  if [[ "${A18_BUNDLE_PATH}" == "n/a" || ! -f "${A18_BUNDLE_PATH}" ]]; then
    quality_status="fail"
    quality_reason="a18 bundle path is missing or unreadable"
  elif [[ "${A18_BUNDLE_SHA}" == "n/a" || -z "${A18_BUNDLE_SHA}" ]]; then
    quality_status="fail"
    quality_reason="a18 bundle sha256 is missing"
  fi
fi

decision="hold"
decision_reason=""

if [[ "${quality_status}" == "pass" ]]; then
  if is_true "${ALLOW_RELEASE}"; then
    decision="go"
    decision_reason="quality gates passed and release is allowed"
  else
    decision="hold"
    decision_reason="quality passed but release is disabled by policy"
  fi
else
  decision="hold"
  decision_reason="quality gate failed"
fi

if [[ "${quality_status}" == "pass" ]]; then
  overall_status="pass"
else
  overall_status="fail"
fi

git_branch="$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
git_commit="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo unknown)"

cat >"${SNAPSHOT_JSON}" <<EOF
{
  "timestamp": "$(date '+%Y-%m-%d %H:%M:%S %Z')",
  "branch": "${git_branch}",
  "commit": "${git_commit}",
  "decision": "${decision}",
  "decisionReason": "${decision_reason}",
  "qualityStatus": "${quality_status}",
  "qualityReason": "${quality_reason}",
  "allowRelease": "${ALLOW_RELEASE}",
  "reports": {
    "a17": {
      "path": "${A17_REPORT}",
      "status": "${A17_STATUS}"
    },
    "a18": {
      "path": "${A18_REPORT}",
      "status": "${A18_STATUS}",
      "bundlePath": "${A18_BUNDLE_PATH}",
      "bundleSha256": "${A18_BUNDLE_SHA}"
    },
    "a19": {
      "path": "${A19_REPORT}",
      "status": "${A19_STATUS}",
      "reason": "${A19_REASON}"
    }
  }
}
EOF

PACKAGE_STAGE="${TMP_DIR}/a20_package"
mkdir -p "${PACKAGE_STAGE}"

cp "${OUTPUT_REPORT}" "${PACKAGE_STAGE}/a20_final_closeout_placeholder.md" 2>/dev/null || true
cp "${SNAPSHOT_JSON}" "${PACKAGE_STAGE}/$(basename "${SNAPSHOT_JSON}")"
cp "${A17_REPORT}" "${PACKAGE_STAGE}/$(basename "${A17_REPORT}")"
cp "${A18_REPORT}" "${PACKAGE_STAGE}/$(basename "${A18_REPORT}")"
cp "${A19_REPORT}" "${PACKAGE_STAGE}/$(basename "${A19_REPORT}")"
if [[ -f "${A18_BUNDLE_PATH}" ]]; then
  cp "${A18_BUNDLE_PATH}" "${PACKAGE_STAGE}/$(basename "${A18_BUNDLE_PATH}")"
fi

{
  echo "# A20 收官决策报告"
  echo
  echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "- 结论：${overall_status}"
  echo "- 决策：${decision}"
  echo "- 决策原因：${decision_reason}"
  echo "- 质量原因：${quality_reason}"
  echo "- ALLOW_RELEASE：${ALLOW_RELEASE}"
  echo "- STRICT：${STRICT}"
  echo
  echo "## 关键门禁状态"
  echo
  echo "| Gate | Status | Report |"
  echo "| --- | --- | --- |"
  echo "| A17 Readiness | ${A17_STATUS} | ${A17_REPORT} |"
  echo "| A18 Bundle | ${A18_STATUS} | ${A18_REPORT} |"
  echo "| A19 Audit | ${A19_STATUS} | ${A19_REPORT} |"
  echo
  echo "## 证据信息"
  echo
  echo "- A18 bundle path：${A18_BUNDLE_PATH}"
  echo "- A18 bundle sha256：${A18_BUNDLE_SHA}"
  echo "- A19 reason：${A19_REASON}"
  echo "- snapshot：${SNAPSHOT_JSON}"
  echo "- closeout package：${PACKAGE_PATH}"
  echo
  echo "## 建议"
  echo
  if [[ "${decision}" == "go" ]]; then
    echo "1. 网关子项目收官条件满足，可进入最终 release 执行窗口。"
  else
    echo "1. 当前保持 hold，待全项目收官策略满足后再 release。"
  fi
  echo "2. 本阶段输出已完成归档，可直接用于评审与审计。"
} >"${OUTPUT_REPORT}"

cp "${OUTPUT_REPORT}" "${PACKAGE_STAGE}/$(basename "${OUTPUT_REPORT}")"

(
  cd "${TMP_DIR}"
  tar -czf "${PACKAGE_PATH}" "a20_package"
)

echo "[A20] report generated: ${OUTPUT_REPORT}"
echo "[A20] snapshot generated: ${SNAPSHOT_JSON}"
echo "[A20] package generated: ${PACKAGE_PATH}"

if [[ "${overall_status}" == "fail" ]] && is_true "${STRICT}"; then
  echo "[A20] STRICT mode enabled and closeout status is fail"
  exit 1
fi

echo "[A20] SUCCESS"
