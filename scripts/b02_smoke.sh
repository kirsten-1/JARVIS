#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8091}" \
"${ROOT_DIR}/knowledge-workflow-service/scripts/b02_smoke.sh"
