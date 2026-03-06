# B05 Workflow DAG 执行引擎 MVP 说明

## 目标

在 B04 知识检索基础上，补齐 Workflow 中台的最小执行闭环：

1. 工作流定义（节点/边）
2. DAG 合法性校验
3. 运行执行（分支/重试/超时）
4. 幂等执行（idempotency key）
5. 运行记录查询

## 交付内容

核心文件：

- `knowledge-workflow-service/app/routers/workflow.py`
- `knowledge-workflow-service/app/services/workflow_store.py`
- `knowledge-workflow-service/app/services/workflow_engine.py`
- `knowledge-workflow-service/app/models/schemas.py`
- `knowledge-workflow-service/scripts/b05_smoke.sh`
- `scripts/b05_smoke.sh`

## API

- `POST /api/v1/workflows`
- `GET /api/v1/workflows`
- `GET /api/v1/workflows/{workflowId}`
- `POST /api/v1/workflows/{workflowId}/runs`
- `GET /api/v1/workflow-runs/{runId}`

## 执行能力（MVP）

1. 节点类型：
   - `start`
   - `task`（`echo` / `set` / `add` / `sleep` / `fail`）
   - `condition`
   - `end`
2. DAG 校验：
   - 单一 `start`
   - 边引用合法
   - 无环
   - 节点从 `start` 可达
3. 可靠性：
   - 节点级 `retry_max`
   - 节点级 `timeout_ms`
   - 运行级 `idempotency_key` 幂等重放

## 冒烟验收

```bash
./scripts/b05_smoke.sh
```

覆盖：

1. 创建 workflow
2. `approve=true` 执行命中发布分支
3. 相同 idempotency key 命中幂等重放
4. `approve=false` 命中人工审核分支
5. 查询 run 详情
