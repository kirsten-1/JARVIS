#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8091}" \
DATASET_PATH="${DATASET_PATH:-${ROOT_DIR}/knowledge-workflow-service/eval/b04_eval_dataset.json}" \
  "${ROOT_DIR}/knowledge-workflow-service/scripts/b04_eval.sh"
