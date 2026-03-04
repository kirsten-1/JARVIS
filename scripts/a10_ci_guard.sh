#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[A10-CI] running A08 guard in lightweight mode ..."
RUN_M11_SMOKE=false \
RUN_A04_SMOKE=false \
RUN_M13_SMOKE=false \
"${ROOT_DIR}/scripts/a08_prepush_guard.sh"

LATEST_REPORT="$(ls -1t "${ROOT_DIR}"/docs/reports/a08_prepush_*.md 2>/dev/null | head -n 1 || true)"
if [[ -n "${LATEST_REPORT}" ]]; then
  echo "[A10-CI] latest report: ${LATEST_REPORT}"
else
  echo "[A10-CI] warning: no prepush report generated"
fi

echo "[A10-CI] SUCCESS"
