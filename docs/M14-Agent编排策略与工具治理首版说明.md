# M14 Agent 编排策略与工具治理首版说明

## 1. 目标

在 M13 最小闭环基础上，补齐“更接近商业 MVP”的三项关键能力：

- 规划策略可配置：默认规则规划，支持 LLM JSON 规划。
- 工具调用可治理：支持请求级工具白名单，避免越权与失控调用。
- 工具执行可控：支持工具注册中心与执行策略（超时、重试）。

## 2. 首版交付

- 新增 Agent 规划模式：
  - `rule`：关键词规则规划（默认）。
  - `llm_json`：先调用模型生成工具规划 JSON，再执行。
- 新增工具白名单：
  - 请求 `metadata.allowedTools` 可指定允许执行的工具集合。
- 新增工具注册中心（可插拔）：
  - `AgentTool` 接口 + `AgentToolRegistry`，内置工具改为组件化注册。
  - 首批内置工具：`time_now`、`workspace_metrics_overview`、`conversation_digest`。
- 新增工具执行策略：
  - 支持 `metadata.toolTimeoutMs`（默认 2000ms，最大 15000ms）。
  - 支持 `metadata.toolMaxRetries`（默认 0，最大 2）。
  - 单步超时或异常不会中断主流程，步骤会记为 `failed` 并继续生成最终回答。
- 新增安全兜底：
  - LLM 规划 JSON 解析失败时，自动回退到 `rule` 规划，不中断主流程。
- 新增测试：
  - `AgentOrchestrationServiceTest#run_shouldSupportLlmJsonPlannerModeWithToolAllowlist`
  - `AgentOrchestrationServiceTest#run_shouldRetryToolWhenConfigured`
  - `AgentOrchestrationServiceTest#run_shouldMarkStepFailedWhenToolTimeout`

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

## 4. 当前限制

- 当前仍为“轻量 Function Calling”方案（通过 JSON 规划），尚未接入原生 tool-call 协议。
- 工具执行目前仍是串行模型，尚未支持并行任务图调度。
- 尚未引入统一工具审计日志与策略中心（目前只有步骤回传与测试覆盖）。
- 幂等键、降级策略、工具配额尚未接入。

## 5. 下一步（M14 后续）

- 工具注册中心（SPI）与统一审计日志。
- 原生 Function Calling 协议接入（OpenAI-compatible tool calls）。
- 工具级 SLA 控制（超时、重试、降级、幂等键）。
