# knowledge-workflow-service (B02/B06 MVP)

B02/B06 最小闭环：文档解析 + 分块 + 入库 + 混合检索（BM25 + dense）+ rerank + 离线评测 + Workflow DAG 执行 + approval/webhook/template。

## 提供接口

- `GET /health`
- `POST /api/v1/knowledge/ingest`
- `GET /api/v1/knowledge/documents`
- `GET /api/v1/knowledge/documents/{documentId}/chunks`
- `POST /api/v1/knowledge/search`
- `POST /api/v1/workflows`
- `GET /api/v1/workflows`
- `GET /api/v1/workflows/{workflowId}`
- `POST /api/v1/workflows/{workflowId}/runs`
- `GET /api/v1/workflow-runs/{runId}`
- `GET /api/v1/workflow-templates`
- `POST /api/v1/workflow-templates/{templateId}/instantiate`

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
./scripts/b05_smoke.sh
./scripts/b06_smoke.sh
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

后续 B07 进入 gateway/agent 端到端集成。

## B05 Workflow 执行能力

- 支持节点类型：`start` / `task` / `condition` / `end`
- 支持 DAG 校验：单 start、无环、节点可达性、边引用合法性
- 支持执行器能力：条件分支、节点重试、节点超时判定、run 幂等（idempotency_key）
- 支持运行记录查询：`GET /api/v1/workflow-runs/{runId}`

## B06 增强能力

- 新增节点类型：`approval`、`webhook`
- 支持人工审核决策路由（`approved` / `rejected`）
- 支持 webhook dry-run 与真实回调（method/url/headers/payload）
- 新增模板流程能力（list + instantiate）
- 首批模板：
  - `content-approval-webhook`
  - `incident-escalation-webhook`
