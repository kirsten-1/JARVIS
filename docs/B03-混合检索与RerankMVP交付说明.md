# B03 混合检索与 Rerank MVP 交付说明

## 目标

在 B02 文档解析/分块/入库闭环基础上，补齐 B03 检索能力：

- BM25 粗排召回
- Dense（TF-IDF 余弦）召回
- Hybrid 融合（可配置权重）
- Rerank 重排序（hybrid 模式）

## 交付内容

关键变更：

- `knowledge-workflow-service/app/services/search.py`
- `knowledge-workflow-service/app/models/schemas.py`
- `knowledge-workflow-service/app/routers/knowledge.py`
- `knowledge-workflow-service/scripts/b03_smoke.sh`
- `scripts/b03_smoke.sh`

## 接口变更

`POST /api/v1/knowledge/search` 新增请求字段：

- `search_mode`: `hybrid` / `bm25` / `dense`（默认 `hybrid`）
- `candidate_k`: 召回候选数（默认 20）
- `rerank`: 是否重排（默认 true，且仅 hybrid 生效）
- `bm25_weight`、`dense_weight`: 融合权重（默认 0.6 / 0.4）

响应新增字段：

- `strategy`
- `rerank_applied`
- `retrieved_candidates`
- `items[].bm25_score`
- `items[].dense_score`
- `items[].retrieval_score`
- `items[].rerank_score`

## 冒烟验收

```bash
./scripts/b03_smoke.sh
```

验收覆盖：

1. `hybrid + rerank` 成功返回
2. `bm25` 模式成功返回且 `rerank_applied=false`
3. `dense` 模式成功返回

## 已知边界（MVP）

1. Dense 目前为轻量 TF-IDF 近似，不是 embedding 向量库（B04/B05 后续演进）
2. Rerank 目前为启发式重排，后续可替换 Cross-Encoder
