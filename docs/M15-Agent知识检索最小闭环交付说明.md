# M15 Agent 知识检索最小闭环交付说明

## 1. 目标

在 M14 的工具治理能力上，补齐 RAG 首批最小能力，形成“知识入库 -> 检索 -> Agent 调用”闭环：

- 可写入 workspace 级知识片段。
- 可按 query 检索并返回最相关片段。
- Agent 可通过工具调用知识检索结果，再生成最终回答。

## 2. 本次交付

- 数据层：
  - 新增 Flyway 迁移：`V3__knowledge_snippet_init.sql`。
  - 新增表：`knowledge_snippet`（workspace_id、title、content、tags、审计时间字段）。
- 后端能力：
  - 新增实体与仓储：`KnowledgeSnippet`、`KnowledgeSnippetRepository`。
  - 新增服务：`KnowledgeBaseService`（关键词匹配 + 轻量打分排序）。
  - 新增 API：`KnowledgeController`（创建/更新/删除/检索）。
- Agent 编排：
  - 新增工具：`workspace_knowledge_search`。
  - 新增规则触发词：`知识库/文档/资料/kb/rag/manual/wiki/检索`。
  - 将用户原始问题透传到工具元数据，支持无显式参数时回退使用用户输入作为检索词。
- 测试与回归：
  - 新增单测：`KnowledgeBaseServiceTest`。
  - 新增冒烟：`scripts/m15_smoke.sh`。

## 3. API 说明

- 创建知识片段：
  - `POST /api/v1/knowledge/snippets`
  - 请求体字段：`userId`、`workspaceId`（可空）、`title`、`content`、`tags`
- 更新知识片段：
  - `PUT /api/v1/knowledge/snippets/{snippetId}`
  - 请求体字段：`userId`、`title`、`content`、`tags`
- 删除知识片段：
  - `DELETE /api/v1/knowledge/snippets/{snippetId}?userId=1001`
- 检索知识片段：
  - `GET /api/v1/knowledge/snippets?userId=1001&workspaceId=1&query=RAG&limit=5`

## 4. 冒烟验证

基础闭环（健康检查 + token + 入库 + 更新 + 检索 + 删除）：

```bash
./scripts/m15_smoke.sh
```

可选 Agent 联调（会触发模型调用）：

```bash
RUN_AGENT=true ./scripts/m15_smoke.sh
```

## 5. 当前限制

- 当前检索为轻量关键词打分，尚未接入向量召回与重排。
- 片段写入仅支持手工 API 入库，尚未接入文档解析管线（PDF/Markdown/Web）。
- Agent 工具执行仍是串行编排，尚未引入并行任务图。

## 6. 下一步建议（M15 后续）

- 接入 embedding + 向量索引（如 FAISS）与混合检索（BM25 + 向量）。
- 增加 reranker 提升召回精度与排序稳定性。
- 补齐知识片段管理能力（更新、删除、批量导入、权限细粒度控制）。
