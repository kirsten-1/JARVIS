#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "[B03-SMOKE] jq is required"
  exit 1
fi

BASE_URL="${BASE_URL:-http://localhost:8091}"

echo "[B03-SMOKE] BASE_URL=${BASE_URL}"

echo "[1/5] health"
curl -fsS "${BASE_URL}/health" >/dev/null

echo "[2/5] ingest docs"
DOC_A="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/ingest" \
  -H 'Content-Type: application/json' \
  -d '{
    "source_type": "markdown",
    "title": "b03-workflow",
    "content": "# Workflow\nDAG workflow 引擎支持节点重试、审批、Webhook 回调。",
    "metadata": {"track": "b03"},
    "chunk_size": 120,
    "chunk_overlap": 20
  }' | jq -r '.document_id')"

DOC_B="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/ingest" \
  -H 'Content-Type: application/json' \
  -d '{
    "source_type": "markdown",
    "title": "b03-rag",
    "content": "# RAG\n混合检索使用 BM25 与向量检索召回，再做 rerank 重排序。",
    "metadata": {"track": "b03"},
    "chunk_size": 120,
    "chunk_overlap": 20
  }' | jq -r '.document_id')"

if [[ -z "${DOC_A}" || -z "${DOC_B}" || "${DOC_A}" == "null" || "${DOC_B}" == "null" ]]; then
  echo "[B03-SMOKE] ingest failed"
  exit 1
fi

echo "[3/5] hybrid search + rerank"
HYBRID_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "混合检索 rerank",
    "top_k": 3,
    "search_mode": "hybrid",
    "candidate_k": 10,
    "rerank": true,
    "bm25_weight": 0.6,
    "dense_weight": 0.4
  }')"

HYBRID_TOTAL="$(echo "${HYBRID_RESP}" | jq -r '.total')"
HYBRID_STRATEGY="$(echo "${HYBRID_RESP}" | jq -r '.strategy')"
HYBRID_RERANK="$(echo "${HYBRID_RESP}" | jq -r '.rerank_applied')"

if [[ "${HYBRID_TOTAL}" -lt 1 || "${HYBRID_STRATEGY}" != "hybrid" || "${HYBRID_RERANK}" != "true" ]]; then
  echo "[B03-SMOKE] hybrid search validation failed"
  echo "${HYBRID_RESP}"
  exit 1
fi

echo "[4/5] bm25 mode"
BM25_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "workflow 节点重试",
    "top_k": 2,
    "search_mode": "bm25",
    "rerank": false
  }')"

BM25_STRATEGY="$(echo "${BM25_RESP}" | jq -r '.strategy')"
BM25_RERANK="$(echo "${BM25_RESP}" | jq -r '.rerank_applied')"
BM25_TOTAL="$(echo "${BM25_RESP}" | jq -r '.total')"

if [[ "${BM25_STRATEGY}" != "bm25" || "${BM25_RERANK}" != "false" || "${BM25_TOTAL}" -lt 1 ]]; then
  echo "[B03-SMOKE] bm25 validation failed"
  echo "${BM25_RESP}"
  exit 1
fi

echo "[5/5] dense mode"
DENSE_RESP="$(curl -fsS -X POST "${BASE_URL}/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "向量召回",
    "top_k": 2,
    "search_mode": "dense",
    "rerank": false
  }')"

DENSE_STRATEGY="$(echo "${DENSE_RESP}" | jq -r '.strategy')"
DENSE_TOTAL="$(echo "${DENSE_RESP}" | jq -r '.total')"

if [[ "${DENSE_STRATEGY}" != "dense" || "${DENSE_TOTAL}" -lt 1 ]]; then
  echo "[B03-SMOKE] dense validation failed"
  echo "${DENSE_RESP}"
  exit 1
fi

echo "[B03-SMOKE] docA=${DOC_A}, docB=${DOC_B}, hybridTotal=${HYBRID_TOTAL}, bm25Total=${BM25_TOTAL}, denseTotal=${DENSE_TOTAL}"
echo "[B03-SMOKE] SUCCESS"
