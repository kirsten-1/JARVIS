# M20 Agent 检索策略治理与来源审计说明

## 1. 目标

在 M19 反馈驱动自动调优基础上，进一步补齐商业化治理能力：

- 引入 workspace 级策略中心，支持明确的策略模式治理。
- 让 autoTune 可“可控、可关、可人工指定”。
- 在检索响应中标记策略来源，提升线上审计和排障效率。

## 2. 交付内容

## 2.1 workspace 策略中心 API

新增接口：

- `GET /api/v1/knowledge/retrieval/policy`
- `PUT /api/v1/knowledge/retrieval/policy`

支持模式：

- `RECOMMEND`：使用 M19 反馈推荐策略（样本充足时生效）
- `MANUAL`：固定使用 workspace 手动配置策略
- `OFF`：关闭 autoTune 覆盖

权限边界：

- 读取：workspace member 可读
- 更新：workspace `OWNER/ADMIN` 可写

## 2.2 策略持久化

新增迁移：

- `V5__knowledge_retrieval_policy.sql`

新增表：

- `knowledge_retrieval_policy`

主要字段：

- `workspace_id`（唯一）
- `mode`
- `keyword_weight` / `vector_weight`
- `hybrid_min_score`
- `max_candidates`
- `updated_by` / `created_at` / `updated_at`

## 2.3 搜索来源审计

`KnowledgeSearchResponse` 新增字段：

- `overrideSource`

取值语义：

- `request_override`
- `workspace_manual_policy`
- `auto_recommendation`
- `none`

调用方可直接判断“这次检索参数是从哪里来的”。

## 2.4 Agent 工具联动

`workspace_knowledge_search` 工具链路接入来源透传：

- 若 tool args/metadata 显式覆盖：`request_override`
- 若 `autoTune=true` 且 workspace 为 `MANUAL`：`workspace_manual_policy`
- 若 `autoTune=true` 且 workspace 为 `RECOMMEND` 且样本充足：`auto_recommendation`
- 若 `OFF` 或样本不足：`none`

## 3. 验收方式

## 3.1 单测

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -Dtest=KnowledgeRetrievalPolicyServiceTest,KnowledgeBaseServiceTest test
```

## 3.2 冒烟

```bash
./scripts/m20_smoke.sh
```

脚本验证点：

- `MANUAL` 模式下，`autoTune=true` 搜索返回 `overrideSource=workspace_manual_policy`
- 切回 `RECOMMEND` 并提交反馈后，返回 `overrideSource=auto_recommendation`
- 切换 `OFF` 后，`overrideApplied=false` 且 `overrideSource=none`

## 4. 价值

- 产品价值：支持“自动推荐 + 人工兜底 + 一键关闭”的运营治理闭环。
- 商业价值：满足企业 workspace 级策略可控诉求，降低线上不可预期风险。
- 工程价值：策略来源可审计，缩短问题定位链路，便于后续 A/B 与策略版本化演进。
