#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[B02-SMOKE] jq is required"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8091}"

echo "[B02-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/5] health"
curl -fsS "${BASE_URL}/health" >/dev/null

echo "[2/5] ingest"
INGEST_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/ingest" \
  -H 'Content-Type: application/json' \
  -d '{
    "source_type": "markdown",
    "title": "jarvis-b02-smoke",
    "content": "# Jarvis\n这是 B02 冒烟测试文档。\n\n它包含解析、分块、入库与检索关键词。",
    "metadata": {"scene": "smoke"},
    "chunk_size": 120,
    "chunk_overlap": 20
  }')"

DOC_ID="$(echo "${INGEST_RESP}" | jq -r '.document_id')"
CHUNK_COUNT="$(echo "${INGEST_RESP}" | jq -r '.chunk_count')"
if [[ -z "${DOC_ID}" || "${DOC_ID}" == "null" ]]; then
  echo "[B02-SMOKE] missing documentId"
  echo "${INGEST_RESP}"
  exit 1
fi

echo "[3/5] list documents"
curl -fsS "${BASE_URL}/api/v1/knowledge/documents?page=1&size=10" | jq '.total' >/dev/null

echo "[4/5] list chunks"
curl -fsS "${BASE_URL}/api/v1/knowledge/documents/${DOC_ID}/chunks" | jq 'length' >/dev/null

echo "[5/5] search"
SEARCH_TOTAL="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"检索关键词\",\"top_k\":5,\"document_id\":\"${DOC_ID}\"}" | jq -r '.total')"

if [[ "${SEARCH_TOTAL}" -lt 1 ]]; then
  echo "[B02-SMOKE] expected at least one search result"
  exit 1
fi

echo "[B02-SMOKE] documentId=${DOC_ID}, chunkCount=${CHUNK_COUNT}, searchTotal=${SEARCH_TOTAL}"
echo "[B02-SMOKE] SUCCESS"
