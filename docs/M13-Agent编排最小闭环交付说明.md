# M13 Agent 编排最小闭环交付说明

## 1. 目标

M13 聚焦“先跑通最小 Agent 闭环”：

- 具备会话内 Agent 编排入口。
- 具备最小工具调用能力（时间/运营指标/上下文摘要）。
- 具备可回归的冒烟脚本。

## 2. 交付清单

- API 与 DTO：
  - `src/main/java/com/bones/gateway/dto/AgentRunRequest.java`
  - `src/main/java/com/bones/gateway/dto/AgentRunResponse.java`
  - `src/main/java/com/bones/gateway/dto/AgentStepResponse.java`
  - `POST /api/v1/conversations/{conversationId}/agent/run`
- 核心编排：
  - `src/main/java/com/bones/gateway/service/AgentOrchestrationService.java`
- 测试：
  - `src/test/java/com/bones/gateway/service/AgentOrchestrationServiceTest.java`
- 冒烟脚本：
  - `scripts/m13_smoke.sh`

## 3. 编排原理（最小闭环）

本次采用规则规划 + 工具执行 + LLM 汇总的最小闭环：

1. Plan：
   - 根据用户问题关键词决定是否执行工具步骤（最多 `maxSteps`）。
2. Act：
   - 顺序执行工具，并记录每步输入/输出/耗时/状态。
3. Observe：
   - 将工具观察结果拼接为“工具执行结果”上下文。
4. Respond：
   - 调用现有 AI 路由层生成最终回答，并落库到会话消息。

## 4. 当前支持工具

- `time_now`：返回当前时间（`Asia/Shanghai`）。
- `workspace_metrics_overview`：返回工作区近 7 天核心运营指标摘要。
- `conversation_digest`：返回当前会话最近若干条消息摘要。

## 5. 使用方式

接口：

```http
POST /api/v1/conversations/{conversationId}/agent/run
Authorization: Bearer <token>
Content-Type: application/json
```

请求体示例：

```json
{
  "userId": 1001,
  "message": "请告诉我现在时间，并给出当前工作区运营指标摘要。",
  "provider": "glm",
  "model": "glm-4.6v-flashx",
  "maxSteps": 3
}
```

返回体关键字段：

- `assistantContent`：最终回答。
- `steps`：每一步工具执行详情（`tool/status/input/output/durationMs`）。

## 6. 冒烟

```bash
./scripts/m13_smoke.sh
```

预期：输出 `[M13-SMOKE] SUCCESS`。

生产环境（`prod` profile）下 `dev-token` 端点默认不可用：

```bash
TOKEN=<your_jwt> ./scripts/m13_smoke.sh
```

也可在本机设置 `JARVIS_JWT_SECRET`（或放在 `.env.prod`）后由脚本自动本地签发测试 JWT。

## 7. 注意事项

- 当前 M13 为最小闭环，规划器采用规则策略，不依赖复杂 Planner 模型。
- 复杂多 Agent 协同（Supervisor/并行/任务图）和 Function Calling 编排将在后续里程碑扩展。
