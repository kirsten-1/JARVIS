# M17 Agent 检索调优与可运营化说明

## 1. 目标

在 M16 `keyword/vector/hybrid` 检索闭环基础上，完成“可运营调参”能力：

- 把检索核心参数从硬编码迁移为配置驱动。
- 把每次检索的生效参数回传给调用侧，方便排障和运营观测。
- 增加阈值治理，降低低质量召回干扰。

## 2. 本次交付范围

### 2.1 配置化检索参数

新增 `jarvis.knowledge.search.*`：

- `default-mode`
- `max-candidates`
- `default-limit`
- `max-limit`
- `vector-min-similarity`
- `hybrid-min-score`
- `hybrid-keyword-weight`
- `hybrid-vector-weight`

对应代码：

- `src/main/java/com/bones/gateway/config/KnowledgeSearchProperties.java`
- `src/main/resources/application-dev.yml`
- `src/main/resources/application-prod.yml`
- `.env.example`
- `.env.prod.example`

### 2.2 检索行为增强

`KnowledgeBaseService` 改为配置驱动：

- 使用配置值决定默认模式、候选数量、结果数量上限。
- `hybrid` 权重自动归一化，避免非法配置导致排序异常。
- `vector` / `hybrid` 增加阈值过滤：
  - `vector` 使用 `vectorMinSimilarity`
  - `hybrid` 使用 `hybridMinScore`

### 2.3 返回可解释参数

`KnowledgeSearchResponse` 新增字段：

- `keywordWeight`
- `vectorWeight`
- `scoreThreshold`

调用方可直接看到本次检索策略的生效参数。

## 3. 验收方式

### 3.1 单测

```bash
mvn -Dtest=KnowledgeBaseServiceTest test
```

### 3.2 冒烟

```bash
./scripts/m17_smoke.sh
```

脚本验证点：

- 检索主链路可用。
- 响应返回 `keywordWeight/vectorWeight/scoreThreshold`。
- 结果排序稳定且命中目标片段。

## 4. 价值说明

- 对业务：可在不同数据规模和语料结构下快速调优检索质量。
- 对运维：线上问题可通过响应中的生效参数快速定位（配置问题还是数据问题）。
- 对产品化：具备 A/B 调参基础，便于逐步走向商业级 RAG 检索治理。
