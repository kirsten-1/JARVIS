# knowledge-workflow-service (B02/B04 MVP)

B02/B04 最小闭环：文档解析 + 分块 + 入库 + 混合检索（BM25 + dense）+ rerank + 离线评测。

## 提供接口

- `GET /health`
- `POST /api/v1/knowledge/ingest`
- `GET /api/v1/knowledge/documents`
- `GET /api/v1/knowledge/documents/{documentId}/chunks`
- `POST /api/v1/knowledge/search`

`POST /api/v1/knowledge/search` 支持参数：

- `search_mode`: `hybrid` / `bm25` / `dense`
- `candidate_k`: 召回候选数（hybrid 模式）
- `rerank`: 是否执行重排序（仅 hybrid 生效）
- `bm25_weight` / `dense_weight`: hybrid 融合权重

## 本地启动

```bash
cd knowledge-workflow-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --host 0.0.0.0 --port 8091 --reload
```

## 冒烟

```bash
./scripts/b02_smoke.sh
./scripts/b03_smoke.sh
./scripts/b04_eval.sh
```

## B04 离线评测

- 评测集：`eval/b04_eval_dataset.json`
- 输出报告：`docs/reports/b04_eval_*.md` + `docs/reports/b04_eval_*.json`

可选参数：

- `TOP_K`（默认 5）
- `SEARCH_MODE`（默认 `hybrid`）
- `CANDIDATE_K`（默认 20）
- `RERANK`（默认 true）
- `TARGET_HIT_RATE`（默认 0.85）
- `TARGET_MRR`（默认 0.70）
- `STRICT`（默认 false，true 时不达标返回非 0）

## 数据存储

MVP 使用本地 JSONL 文件：

- `data/documents.jsonl`
- `data/chunks.jsonl`

后续 B05 进入 Workflow DAG 执行引擎 MVP。
