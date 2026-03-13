# B06 人工审核 Webhook 与模板流程首批说明

## 目标

在 B05 Workflow DAG MVP 基础上，交付首批生产可用的流程增强能力：

1. 人工审核节点（approval）
2. Webhook 节点（支持 dry-run 与真实回调）
3. 模板流程目录与实例化能力

## 交付内容

核心实现：

- `knowledge-workflow-service/app/services/workflow_engine.py`
- `knowledge-workflow-service/app/services/workflow_templates.py`
- `knowledge-workflow-service/app/routers/workflow.py`
- `knowledge-workflow-service/app/models/schemas.py`

脚本：

- `knowledge-workflow-service/scripts/b06_smoke.sh`
- `scripts/b06_smoke.sh`

## 新增 API

- `GET /api/v1/workflow-templates`
- `POST /api/v1/workflow-templates/{templateId}/instantiate`

## 节点能力

1. `approval` 节点：
   - 从输入读取审核结果（默认 `$input.review.approved`）
   - 路由输出：`approved` / `rejected`
   - 可控制是否强制显式审核（`require_explicit`）
2. `webhook` 节点：
   - 支持 `method/url/headers/payload`
   - 支持 `dry_run`（默认可用于开发验收）
   - 支持 `request_timeout_ms`

## 首批模板

1. `content-approval-webhook`
   - 草稿生成 -> 人工审核 -> 发布/待审分支 -> 回调通知
2. `incident-escalation-webhook`
   - 按严重级别分支 -> 升级状态 -> 回调通知

## 验收

```bash
./scripts/b06_smoke.sh
```

覆盖：

1. 模板列表查询
2. 模板实例化
3. approval 节点分支（通过/拒绝）
4. webhook 节点执行（dry-run）
5. run 幂等重放
