# M18 Agent 检索策略请求级覆盖说明

## 1. 目标

在 M17 检索参数配置化基础上，补齐“请求级策略覆盖”能力：

- 调用方可按请求覆盖检索权重、阈值与候选数，无需修改全局配置。
- Agent 可在单次工具调用中下发检索策略，支持任务级调参。
- 检索响应回传策略生效标记，提升线上排障与审计效率。

## 2. 交付内容

## 2.1 检索 API 请求级覆盖参数

`GET /api/v1/knowledge/snippets` 新增可选参数：

- `vectorMinSimilarity`
- `hybridMinScore`
- `hybridKeywordWeight`
- `hybridVectorWeight`
- `maxCandidates`

上述参数只影响当前请求，不改变全局配置。

## 2.2 检索服务覆盖策略融合

`KnowledgeBaseService` 新增 `SearchOverrides`：

- 合并全局配置与请求级覆盖。
- 对权重做归一化，对阈值做边界夹取。
- 将最终运行时策略用于排序和过滤。

## 2.3 检索响应增强

`KnowledgeSearchResponse` 新增：

- `maxCandidates`
- `overrideApplied`

调用方可直接识别“本次请求是否触发覆盖策略”。

## 2.4 Agent 工具策略透传

`workspace_knowledge_search` 工具新增参数：

- `vectorMinSimilarity`
- `hybridMinScore`
- `hybridKeywordWeight`
- `hybridVectorWeight`
- `maxCandidates`

支持来源：

- tool arguments
- `metadata.knowledgeSearchOverrides`（对象）

工具输出新增生效策略回显，便于编排日志调试。

## 3. 验收方式

## 3.1 单测

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -Dtest=KnowledgeBaseServiceTest test
```

## 3.2 冒烟

```bash
./scripts/m18_smoke.sh
```

脚本验证点：

- 默认请求 `overrideApplied=false`。
- 覆盖请求 `overrideApplied=true`。
- `maxCandidates` 与 `keywordWeight/vectorWeight/scoreThreshold` 按覆盖值生效。

## 4. 价值

- 产品价值：支持按业务场景动态调参，快速提升不同 query 类型的召回质量。
- 运营价值：接口响应可直接审计策略生效情况，缩短故障定位链路。
- 工程价值：打通 API 与 Agent 的统一策略入口，为后续 A/B 与自动调参奠定基础。
