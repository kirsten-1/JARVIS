# 多厂商 AI 使用指南

## 1. 适用范围

本指南对应当前 `gateway_pro` 的 M4.6 能力，支持以下厂商路由：

- DeepSeek
- Gemini
- 阿里云（DashScope compatible mode）
- 腾讯云（按你的兼容网关配置）
- 智谱 GLM
- MiniMax
- local（本地 mock/自建服务）

说明：
- 从 M5.5 开始，业务接口默认受 JWT 保护。调用接口前需先获取 Bearer token（开发环境可用 `/api/v1/auth/dev-token`）。

## 2. 核心机制

网关会按以下优先级选择 provider/model：

1. 请求体 `provider` / `model`
2. 请求体 `metadata.provider` / `metadata.model`
3. 配置中的 `jarvis.ai.default-provider` 与 provider 默认 `model`

支持可选 fallback：

- `fallback-enabled=true` 时，主 provider 失败会尝试切到 `fallback-provider`

## 3. 配置入口

主要配置文件：

- `src/main/resources/application-dev.yml`
- `.env.example`

建议在 IDEA Run Configuration 里用环境变量覆盖，不要把真实 Key 写进仓库文件。

## 4. 最小可用配置（先跑通）

先用 local 跑通：

```bash
AI_DEFAULT_PROVIDER=local
AI_LOCAL_ENABLED=true
AI_LOCAL_BASE_URL=http://localhost:8000
AI_LOCAL_CHAT_PATH=/v1/chat
AI_LOCAL_STREAM_PATH=/v1/chat/stream
```

然后启动应用，调用 `/api/v1/ai/chat` 验证链路。

## 5. 各厂商配置示例

## 5.1 DeepSeek

```bash
AI_DEEPSEEK_ENABLED=true
AI_DEEPSEEK_BASE_URL=https://api.deepseek.com
AI_DEEPSEEK_CHAT_PATH=/v1/chat/completions
AI_DEEPSEEK_STREAM_PATH=/v1/chat/completions
AI_DEEPSEEK_API_KEY=你的Key
AI_DEEPSEEK_MODEL=deepseek-chat
AI_DEFAULT_PROVIDER=deepseek
```

## 5.2 Gemini

```bash
AI_GEMINI_ENABLED=true
AI_GEMINI_BASE_URL=https://generativelanguage.googleapis.com
AI_GEMINI_CHAT_PATH=/v1beta/models/{model}:generateContent
AI_GEMINI_STREAM_PATH=/v1beta/models/{model}:streamGenerateContent?alt=sse
AI_GEMINI_API_KEY=你的Key
AI_GEMINI_MODEL=gemini-2.0-flash
AI_DEFAULT_PROVIDER=gemini
```

说明：Gemini 默认通过 query 参数传 key（`key=...`）。

## 5.3 阿里云（DashScope compatible mode）

```bash
AI_ALIYUN_ENABLED=true
AI_ALIYUN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_ALIYUN_CHAT_PATH=/chat/completions
AI_ALIYUN_STREAM_PATH=/chat/completions
AI_ALIYUN_API_KEY=你的Key
AI_ALIYUN_MODEL=qwen-plus
AI_DEFAULT_PROVIDER=aliyun
```

## 5.4 腾讯云（按实际网关地址）

```bash
AI_TENCENT_ENABLED=true
AI_TENCENT_BASE_URL=你的腾讯云兼容网关地址
AI_TENCENT_CHAT_PATH=/v1/chat/completions
AI_TENCENT_STREAM_PATH=/v1/chat/completions
AI_TENCENT_API_KEY=你的Key
AI_TENCENT_MODEL=hunyuan-standard
AI_DEFAULT_PROVIDER=tencent
```

说明：腾讯云不同账号/区域网关可能不同，请以控制台文档为准。

## 5.5 智谱 GLM

```bash
AI_GLM_ENABLED=true
AI_GLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4
AI_GLM_CHAT_PATH=/chat/completions
AI_GLM_STREAM_PATH=/chat/completions
AI_GLM_API_KEY=你的Key
AI_GLM_MODEL=glm-4-plus
AI_DEFAULT_PROVIDER=glm
```

## 5.6 MiniMax

```bash
AI_MINIMAX_ENABLED=true
AI_MINIMAX_PROTOCOL=ANTHROPIC
AI_MINIMAX_BASE_URL=https://api.minimaxi.com/anthropic
AI_MINIMAX_CHAT_PATH=/v1/messages
AI_MINIMAX_STREAM_PATH=/v1/messages
AI_MINIMAX_API_KEY=你的Key
AI_MINIMAX_MODEL=MiniMax-M1
AI_MINIMAX_API_KEY_HEADER=x-api-key
AI_MINIMAX_API_KEY_PREFIX=
AI_MINIMAX_ANTHROPIC_VERSION=2023-06-01
AI_DEFAULT_PROVIDER=minimax
```

说明：上述为 MiniMax Anthropic 兼容网关配置；如你账户网关不同，可调整 `BASE_URL/PATH`。

## 6. 请求时显式指定厂商

同步调用示例：

```json
{
  "message": "你好，介绍一下你自己",
  "conversationId": 1,
  "userId": 1001,
  "provider": "deepseek",
  "model": "deepseek-chat",
  "metadata": {
    "requestId": "req-20260228-001"
  }
}
```

流式调用也是同一请求体，接口为：

- `POST /api/v1/ai/chat/stream`

## 7. fallback 使用示例

主路由 DeepSeek，失败切 GLM：

```bash
AI_DEFAULT_PROVIDER=deepseek
AI_FALLBACK_ENABLED=true
AI_FALLBACK_PROVIDER=glm
```

当主 provider 超时/不可达/坏响应时会自动尝试 fallback。

## 7.1 provider/model 配额与成本统计

配置：

```bash
JARVIS_GUARD_PER_DAY_PROVIDER_MODEL_QUOTA=500
JARVIS_BILLING_ENABLED=true
JARVIS_BILLING_DEFAULT_INPUT_COST_PER1K=0.001
JARVIS_BILLING_DEFAULT_OUTPUT_COST_PER1K=0.002
```

查询每日账本：

`GET /api/v1/billing/daily?provider=deepseek&model=deepseek-chat`

## 8. IDEA 启动与验证

在 `Run | Edit Configurations | GatewayApplication`：

- `Active profiles`: `dev`
- `Environment variables` 至少包含：
  - `SPRING_PROFILES_ACTIVE=dev`
  - `MYSQL_PASSWORD=你的MySQL密码`
  - 你的 provider 变量（如 `AI_DEEPSEEK_*`）

验证顺序：

1. `http://localhost:8080/actuator/health` 返回 `UP`
2. `POST /api/v1/ai/chat` 成功返回统一结构
3. `POST /api/v1/ai/chat/stream` 可看到持续 `delta` + `done`
4. `POST /api/v1/conversations/{id}/chat/stream` 的首个 `meta` 事件返回 `streamId`
5. 若断线，调用 `GET /api/v1/conversations/streams/{streamId}` 查看恢复快照

## 9. 常见问题排查

`AI_PROVIDER_NOT_FOUND`：
- 请求写了不存在的 `provider`
- 或配置里该 provider 没定义

`AI_PROVIDER_DISABLED`：
- 对应 provider 的 `*_ENABLED=false`

`AI_PROVIDER_CONFIG_INVALID`：
- `base-url` 空
- Gemini path 用了 `{model}` 但没有可用 model

`AI_SERVICE_UNAVAILABLE`：
- provider 网关不可达、DNS/网络问题

`AI_SERVICE_TIMEOUT`：
- 下游响应慢，调大 `AI_SERVICE_READ_TIMEOUT_MS`

## 10. 安全建议

- API Key 只放环境变量，不写入 Git。
- 生产环境按 provider 分开管理密钥，定期轮换。
- 对外暴露网关前，建议叠加鉴权与配额控制。
