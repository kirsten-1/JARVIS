#!/usr/bin/env python3
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path


def env_bool(name, default):
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = max(0, math.ceil((p / 100) * len(ordered)) - 1)
    return ordered[idx]


def http_json(method, url, payload=None):
    body = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url=url, data=body, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = resp.read().decode("utf-8")
        if not raw:
            return {}
        return json.loads(raw)


def ingest_documents(base_url, dataset):
    mapped_ids = {}
    for doc in dataset["documents"]:
        payload = {
            "source_type": doc.get("source_type", "markdown"),
            "title": doc["title"],
            "content": doc["content"],
            "metadata": {
                "datasetVersion": dataset.get("version", "unknown"),
                "datasetDocId": doc["id"],
            },
            "chunk_size": 160,
            "chunk_overlap": 20,
        }
        resp = http_json("POST", f"{base_url}/api/v1/knowledge/ingest", payload=payload)
        document_id = resp.get("document_id")
        if not document_id:
            raise RuntimeError(f"ingest failed for document: {doc['id']}")
        mapped_ids[doc["id"]] = document_id
    return mapped_ids


def search_once(base_url, query, top_k, mode, candidate_k, rerank):
    payload = {
        "query": query,
        "top_k": top_k,
        "search_mode": mode,
        "candidate_k": candidate_k,
        "rerank": rerank,
    }
    start = time.perf_counter()
    resp = http_json("POST", f"{base_url}/api/v1/knowledge/search", payload=payload)
    elapsed_ms = (time.perf_counter() - start) * 1000
    return resp, elapsed_ms


def evaluate(dataset, mapped_ids, base_url, top_k, mode, candidate_k, rerank):
    query_results = []
    latencies_ms = []
    hit_count = 0
    mrr_total = 0.0
    recall_total = 0.0

    for item in dataset["queries"]:
        expected_runtime_ids = [mapped_ids[key] for key in item["expected_doc_ids"]]
        resp, elapsed_ms = search_once(
            base_url=base_url,
            query=item["query"],
            top_k=top_k,
            mode=mode,
            candidate_k=candidate_k,
            rerank=rerank,
        )
        latencies_ms.append(elapsed_ms)
        rows = resp.get("items", [])
        ranked_doc_ids = [row.get("document_id") for row in rows]

        hit = any(doc_id in expected_runtime_ids for doc_id in ranked_doc_ids)
        if hit:
            hit_count += 1

        rank = None
        for idx, doc_id in enumerate(ranked_doc_ids, start=1):
            if doc_id in expected_runtime_ids:
                rank = idx
                break
        reciprocal_rank = 0.0 if rank is None else 1.0 / rank
        mrr_total += reciprocal_rank

        expected_set = set(expected_runtime_ids)
        got_set = set(ranked_doc_ids)
        recall_at_k = len(expected_set & got_set) / max(len(expected_set), 1)
        recall_total += recall_at_k

        query_results.append(
            {
                "query_id": item["id"],
                "query": item["query"],
                "expected_doc_ids": item["expected_doc_ids"],
                "expected_runtime_doc_ids": expected_runtime_ids,
                "returned_runtime_doc_ids": ranked_doc_ids,
                "hit_at_k": hit,
                "first_relevant_rank": rank,
                "reciprocal_rank": round(reciprocal_rank, 4),
                "recall_at_k": round(recall_at_k, 4),
                "latency_ms": round(elapsed_ms, 2),
            }
        )

    total = len(dataset["queries"])
    metrics = {
        "query_count": total,
        "hit_rate_at_k": round(hit_count / max(total, 1), 4),
        "mrr_at_k": round(mrr_total / max(total, 1), 4),
        "avg_recall_at_k": round(recall_total / max(total, 1), 4),
        "latency_avg_ms": round(statistics.mean(latencies_ms), 2) if latencies_ms else 0.0,
        "latency_p50_ms": round(percentile(latencies_ms, 50), 2),
        "latency_p90_ms": round(percentile(latencies_ms, 90), 2),
        "latency_max_ms": round(max(latencies_ms), 2) if latencies_ms else 0.0,
    }
    return {"metrics": metrics, "queries": query_results}


def write_reports(report_md_path, report_json_path, context):
    report_md_path.parent.mkdir(parents=True, exist_ok=True)
    report_json_path.parent.mkdir(parents=True, exist_ok=True)

    with report_json_path.open("w", encoding="utf-8") as f:
        json.dump(context, f, ensure_ascii=False, indent=2)

    metrics = context["results"]["metrics"]
    lines = [
        "# B04 RAG 离线评测基线报告",
        "",
        f"- generated_at: `{context['generated_at']}`",
        f"- dataset: `{context['dataset_path']}` (version: `{context['dataset_version']}`)",
        f"- base_url: `{context['base_url']}`",
        f"- search_mode: `{context['search_mode']}`",
        f"- top_k: `{context['top_k']}`",
        f"- candidate_k: `{context['candidate_k']}`",
        f"- rerank: `{str(context['rerank']).lower()}`",
        "",
        "## Metrics",
        "",
        f"- query_count: `{metrics['query_count']}`",
        f"- hit_rate@k: `{metrics['hit_rate_at_k']}`",
        f"- mrr@k: `{metrics['mrr_at_k']}`",
        f"- avg_recall@k: `{metrics['avg_recall_at_k']}`",
        f"- latency_avg_ms: `{metrics['latency_avg_ms']}`",
        f"- latency_p50_ms: `{metrics['latency_p50_ms']}`",
        f"- latency_p90_ms: `{metrics['latency_p90_ms']}`",
        f"- latency_max_ms: `{metrics['latency_max_ms']}`",
        "",
        "## Gate",
        "",
        f"- target_hit_rate@k: `{context['target_hit_rate']}`",
        f"- target_mrr@k: `{context['target_mrr']}`",
        f"- gate_result: `{context['gate_result']}`",
        "",
        "## Query Breakdown",
        "",
        "| query_id | hit@k | first_rank | recall@k | latency_ms |",
        "| --- | --- | --- | --- | --- |",
    ]

    for row in context["results"]["queries"]:
        rank = row["first_relevant_rank"] if row["first_relevant_rank"] is not None else "-"
        lines.append(
            f"| {row['query_id']} | {str(row['hit_at_k']).lower()} | {rank} | {row['recall_at_k']} | {row['latency_ms']} |"
        )

    with report_md_path.open("w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    root_dir = Path(__file__).resolve().parents[2]
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

    base_url = os.getenv("BASE_URL", "http://localhost:8091").rstrip("/")
    dataset_path = Path(os.getenv("DATASET_PATH", str(root_dir / "knowledge-workflow-service" / "eval" / "b04_eval_dataset.json")))
    report_md_path = Path(
        os.getenv(
            "REPORT_PATH",
            str(root_dir / "docs" / "reports" / f"b04_eval_{timestamp}.md"),
        )
    )
    report_json_path = Path(
        os.getenv(
            "REPORT_JSON_PATH",
            str(root_dir / "docs" / "reports" / f"b04_eval_{timestamp}.json"),
        )
    )

    top_k = int(os.getenv("TOP_K", "5"))
    candidate_k = int(os.getenv("CANDIDATE_K", "20"))
    search_mode = os.getenv("SEARCH_MODE", "hybrid").strip().lower()
    rerank = env_bool("RERANK", True)
    strict = env_bool("STRICT", False)
    target_hit_rate = float(os.getenv("TARGET_HIT_RATE", "0.85"))
    target_mrr = float(os.getenv("TARGET_MRR", "0.70"))

    if not dataset_path.exists():
        print(f"[B04] dataset not found: {dataset_path}")
        return 1

    try:
        _ = http_json("GET", f"{base_url}/health")
    except urllib.error.URLError as exc:
        print(f"[B04] health check failed: {exc}")
        return 1

    with dataset_path.open("r", encoding="utf-8") as f:
        dataset = json.load(f)

    try:
        mapped_ids = ingest_documents(base_url=base_url, dataset=dataset)
        results = evaluate(
            dataset=dataset,
            mapped_ids=mapped_ids,
            base_url=base_url,
            top_k=top_k,
            mode=search_mode,
            candidate_k=candidate_k,
            rerank=rerank,
        )
    except Exception as exc:  # noqa: BLE001
        print(f"[B04] evaluation failed: {exc}")
        return 1

    metrics = results["metrics"]
    gate_pass = metrics["hit_rate_at_k"] >= target_hit_rate and metrics["mrr_at_k"] >= target_mrr
    gate_result = "pass" if gate_pass else "warn"

    context = {
        "generated_at": datetime.now().isoformat(),
        "dataset_path": str(dataset_path),
        "dataset_version": dataset.get("version", "unknown"),
        "base_url": base_url,
        "search_mode": search_mode,
        "top_k": top_k,
        "candidate_k": candidate_k,
        "rerank": rerank,
        "target_hit_rate": target_hit_rate,
        "target_mrr": target_mrr,
        "gate_result": gate_result,
        "mapped_doc_ids": mapped_ids,
        "results": results,
    }
    write_reports(report_md_path=report_md_path, report_json_path=report_json_path, context=context)

    print(f"[B04] report_md={report_md_path}")
    print(f"[B04] report_json={report_json_path}")
    print(
        "[B04] metrics "
        f"hit_rate@k={metrics['hit_rate_at_k']}, "
        f"mrr@k={metrics['mrr_at_k']}, "
        f"avg_recall@k={metrics['avg_recall_at_k']}, "
        f"p90={metrics['latency_p90_ms']}ms"
    )
    print(f"[B04] gate={gate_result} (target_hit_rate={target_hit_rate}, target_mrr={target_mrr})")

    if strict and not gate_pass:
        print("[B04] STRICT mode enabled, gate failed.")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
