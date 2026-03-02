# M14 Agent 编排策略与工具治理首版说明

## 1. 目标

在 M13 最小闭环基础上，补齐“更接近商业 MVP”的四项关键能力：

- 规划策略可配置：默认规则规划，支持 LLM JSON 规划。
- 原生函数调用：支持 OpenAI-compatible `tool_calls` 编排闭环。
- 工具调用可治理：支持请求级工具白名单，避免越权与失控调用。
- 工具执行可控：支持工具注册中心与执行策略（超时、重试）。

## 2. 首版交付

- 新增 Agent 规划模式：
  - `rule`：关键词规则规划（默认）。
  - `llm_json`：先调用模型生成工具规划 JSON，再执行。
  - `function_calling`：通过原生 `tool_calls` 规划并执行工具，再汇总最终回答。
- 新增工具白名单：
  - 请求 `metadata.allowedTools` 可指定允许执行的工具集合。
- 新增工具注册中心（可插拔）：
  - `AgentTool` 接口 + `AgentToolRegistry`，内置工具改为组件化注册。
  - 首批内置工具：`time_now`、`workspace_metrics_overview`、`conversation_digest`。
- 新增工具执行策略：
  - 支持 `metadata.toolTimeoutMs`（默认 2000ms，最大 15000ms）。
  - 支持 `metadata.toolMaxRetries`（默认 0，最大 2）。
  - 单步超时或异常不会中断主流程，步骤会记为 `failed` 并继续生成最终回答。
- 新增工具策略中心（首版）：
  - 支持统一策略解析：默认策略 -> 工具级策略 -> 请求级覆盖（`metadata.toolPolicies`）。
  - 支持工具开关（`enabled`），禁用时步骤状态记为 `skipped`。
  - 支持统一管理超时、重试、幂等开关与幂等 TTL。
- 新增工具幂等键：
  - 支持 `metadata.toolIdempotencyKey`（同 key + 同工具 + 同输入时复用执行结果）。
  - 支持 `metadata.toolIdempotencyTtlSeconds`（默认 1800，最大 86400）。
  - 命中缓存时步骤状态记为 `deduplicated`，避免重复副作用调用。
- 新增安全兜底：
  - LLM 规划 JSON 解析失败时，自动回退到 `rule` 规划，不中断主流程。
  - Function Calling 未返回工具调用时，自动回退到规则规划，不中断主流程。
- 新增协议层支持：
  - `AiChatResponse` 新增 `toolCalls` 字段，支持统一承载函数调用结果。
  - OpenAI-compatible 请求支持 `tools/tool_choice/parallel_tool_calls` 注入。
  - OpenAI-compatible 响应支持 `message.tool_calls` 与 `message.function_call` 解析。
- 新增审计信息：
  - Agent 最终请求 metadata 增加 `agentAuditSteps`（步序、工具名、状态、耗时、输入/输出摘要）。
- 新增测试：
  - `AgentOrchestrationServiceTest#run_shouldSupportLlmJsonPlannerModeWithToolAllowlist`
  - `AgentOrchestrationServiceTest#run_shouldSupportNativeFunctionCallingPlannerMode`
  - `AgentOrchestrationServiceTest#run_shouldRetryToolWhenConfigured`
  - `AgentOrchestrationServiceTest#run_shouldMarkStepFailedWhenToolTimeout`
  - `WebClientAiServiceClientTest#chat_shouldParseToolCalls_whenOpenAiResponseContainsToolCalls`
  - `WebClientAiServiceClientTest#chat_shouldSendOpenAiToolsAndFilterControlMetadata`

## 3. 使用方式

请求示例（启用 LLM planner + allowlist）：

```json
{
  "userId": 1001,
  "message": "请先看时间和运营指标，然后给结论",
  "provider": "glm",
  "model": "glm-4.6v-flashx",
  "maxSteps": 3,
  "metadata": {
    "plannerMode": "llm_json",
    "allowedTools": ["time_now", "workspace_metrics_overview"],
    "toolTimeoutMs": 3000,
    "toolMaxRetries": 1
  }
}
```

请求示例（启用原生 Function Calling）：

```json
{
  "userId": 1001,
  "message": "先获取当前时间和最近会话摘要，再给结论",
  "provider": "openai",
  "model": "gpt-4o-mini",
  "maxSteps": 3,
  "metadata": {
    "plannerMode": "function_calling",
    "allowedTools": ["time_now", "conversation_digest"],
    "toolTimeoutMs": 3000,
    "toolMaxRetries": 1,
    "toolIdempotencyKey": "order-20260303-1001",
    "toolIdempotencyTtlSeconds": 1800,
    "functionCallingMaxRounds": 2
  }
}
```

## 4. 当前限制

- 工具执行目前仍是串行模型，尚未支持并行任务图调度。
- 审计仍为请求级 metadata 聚合，尚未下沉到独立审计中心与持久化检索。
- 降级策略与工具配额尚未接入。

## 5. 下一步（M14 后续）

- 工具审计中心（独立存储 + 查询接口 + 风险告警）。
- 工具级降级策略与配额治理。
