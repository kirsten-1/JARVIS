# M19 Agent 检索反馈闭环与自动调优说明

## 1. 目标

在 M18 请求级策略覆盖基础上，新增“反馈驱动”闭环：

- 采集检索结果是否有帮助（helpful）反馈样本。
- 聚合样本并产出检索策略推荐（权重、阈值、候选数）。
- 在不改全局配置的情况下，通过 `autoTune` 自动应用推荐策略。

## 2. 交付内容

## 2.1 反馈采集 API

`POST /api/v1/knowledge/retrieval/feedback`

关键字段：

- `helpful`
- `searchMode`
- `keywordWeight`
- `vectorWeight`
- `scoreThreshold`
- `maxCandidates`

服务将按 `workspace + date` 聚合反馈，数据存 Redis（30 天 TTL）。

## 2.2 策略推荐 API

`GET /api/v1/knowledge/retrieval/recommendation`

返回：

- `feedbackCount`
- `helpfulRate`
- `decision`
- `suggestedKeywordWeight`
- `suggestedVectorWeight`
- `suggestedHybridMinScore`
- `suggestedMaxCandidates`

推荐逻辑：

- 样本不足：返回默认策略并标记 `insufficient_samples`。
- 样本充足：优先按 `vector/keyword` 偏向样本帮助率判定方向。
- 无明显偏向时：使用 helpful 样本平均权重与阈值。

## 2.3 autoTune 闭环

`GET /api/v1/knowledge/snippets` 新增参数：

- `autoTune=true|false`

行为：

- 若存在显式 override，优先使用显式参数。
- 若无显式 override 且 `autoTune=true`，自动读取推荐策略并应用。
- 响应中 `overrideApplied=true` 表示本次请求已应用覆盖策略。

## 2.4 Agent 工具增强

`workspace_knowledge_search` 新增：

- tool args: `autoTune`
- metadata: `knowledgeAutoTune`

当 `autoTune=true` 且未显式传覆盖参数时，工具自动应用推荐策略。

## 3. 验收方式

## 3.1 单测

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -Dtest=KnowledgeBaseServiceTest,KnowledgeRetrievalPolicyServiceTest test
```

## 3.2 冒烟

```bash
./scripts/m19_smoke.sh
```

脚本验证点：

- baseline 搜索 `overrideApplied=false`。
- 提交反馈后推荐结果出现向量偏向建议。
- `autoTune=true` 搜索 `overrideApplied=true` 且策略已切换。

## 4. 价值

- 运营价值：把“检索好不好”从感知变成可采集、可量化、可推荐。
- 产品价值：支持按 workspace 自适应检索策略，提升真实业务问答稳定性。
- 工程价值：形成 `feedback -> recommendation -> autoTune` 的最小自进化闭环。
