# M16 Agent 混合检索增强交付说明

## 1. 目标

在 M15 “知识片段入库+检索+Agent 工具”闭环上，补齐更贴近商业 MVP 的检索能力分层：

- 保留关键词检索（稳定、可解释）。
- 增加向量检索（语义相似度）。
- 增加混合检索（keyword + vector 融合排序）。

## 2. 本次交付

- 向量化能力（首版）：
  - 新增 `KnowledgeEmbeddingService`。
  - 提供 embedding 生成、序列化、反序列化、余弦相似度计算能力。
- 数据层升级：
  - 新增迁移：`V4__knowledge_snippet_embedding.sql`。
  - `knowledge_snippet` 新增 `embedding` 字段。
- 检索能力升级：
  - `KnowledgeBaseService` 支持 `searchMode`：
    - `keyword`
    - `vector`
    - `hybrid`（默认）
  - 响应新增 `searchMode` 字段，便于调用侧审计。
- Agent 工具升级：
  - `workspace_knowledge_search` 支持 `searchMode` 参数。
  - 支持 metadata 回退字段：`knowledgeSearchMode`。
- 回归与冒烟：
  - `KnowledgeBaseServiceTest` 增加 vector 路径覆盖。
  - 新增脚本：`scripts/m16_smoke.sh`。

## 3. API 变更

检索接口新增参数：

```http
GET /api/v1/knowledge/snippets?userId=1001&workspaceId=1&query=vector%20retrieval&searchMode=hybrid&limit=5
```

`searchMode` 取值：

- `keyword`
- `vector`
- `hybrid`（默认）

响应新增字段：

- `data.searchMode`

## 4. Agent 工具使用

工具名：`workspace_knowledge_search`

新增参数：

- `searchMode`: `keyword` / `vector` / `hybrid`

若未显式传参，可通过 metadata 传：

- `knowledgeSearchMode`

## 5. 冒烟验证

```bash
./scripts/m16_smoke.sh
```

可选 Agent 路径：

```bash
RUN_AGENT=true ./scripts/m16_smoke.sh
```

## 6. 当前限制

- 当前 embedding 采用本地轻量向量化算法，未接入外部高精度 embedding 模型。
- 向量召回仍为单阶段，未接入 reranker。
- 未实现知识分片批量导入/增量更新流水线。

## 7. 下一步建议（M16 后续）

- 引入外部 embedding provider 与模型配置化管理。
- 增加混合召回参数可配置（融合权重、阈值、top-k 分段）。
- 引入 reranker 提升复杂问题排序精度。
