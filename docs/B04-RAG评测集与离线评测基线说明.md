# B04 RAG 评测集与离线评测基线说明

## 目标

建立可复用的离线评测闭环，沉淀固定评测集与基线指标，支持后续 B05+ 迭代时做回归对比。

## 交付内容

- 评测集：`knowledge-workflow-service/eval/b04_eval_dataset.json`
- 评测执行脚本：
  - `knowledge-workflow-service/scripts/b04_eval.py`
  - `knowledge-workflow-service/scripts/b04_eval.sh`
  - `scripts/b04_eval.sh`
- 报告输出（运行生成）：
  - `docs/reports/b04_eval_*.md`
  - `docs/reports/b04_eval_*.json`

## 评测方法

1. 将评测集文档批量 `ingest` 到知识服务
2. 对评测查询执行 `search`
3. 计算指标：
   - `hit_rate@k`
   - `mrr@k`
   - `avg_recall@k`
   - latency（avg/p50/p90/max）
4. 按阈值进行 gate 判定：
   - `TARGET_HIT_RATE`（默认 `0.85`）
   - `TARGET_MRR`（默认 `0.70`）

## 使用方式

```bash
./scripts/b04_eval.sh
```

可选：

```bash
TOP_K=5 SEARCH_MODE=hybrid CANDIDATE_K=20 RERANK=true \
TARGET_HIT_RATE=0.85 TARGET_MRR=0.70 STRICT=true \
./scripts/b04_eval.sh
```

## 说明

1. 该评测为离线基线，不依赖对话状态，适合作为持续集成回归输入。
2. 当前结果用于 B04 基线建立，后续可扩展更多真实业务问答与负样本。
