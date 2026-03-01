# M4 AI调用层交付说明

## 1. M4 目标

- 建立 AI 服务调用抽象层，避免业务层直接拼接 HTTP 请求。
- 提供同步与流式两类调用能力。
- 增加超时、重试、错误映射，确保调用失败可控且可观测。

## 2. 交付内容

## 2.1 配置与 WebClient

- `src/main/java/com/bones/gateway/config/AiServiceProperties.java`
- `src/main/java/com/bones/gateway/config/WebClientConfig.java`

配置项（`jarvis.ai.*`）：
- `base-url`
- `chat-path`
- `stream-path`
- `connect-timeout-ms`
- `read-timeout-ms`
- `max-retries`
- `retry-backoff-ms`

## 2.2 AI 客户端抽象与实现

- `src/main/java/com/bones/gateway/integration/ai/AiServiceClient.java`
- `src/main/java/com/bones/gateway/integration/ai/WebClientAiServiceClient.java`
- `src/main/java/com/bones/gateway/integration/ai/model/AiChatRequest.java`
- `src/main/java/com/bones/gateway/integration/ai/model/AiChatResponse.java`

能力：
- `chat()`：同步问答（Mono）
- `chatStream()`：流式问答（Flux）
- 错误映射：
  - 超时 -> `AI_SERVICE_TIMEOUT`
  - 下游不可达 -> `AI_SERVICE_UNAVAILABLE`
  - 下游返回错误状态 -> `AI_SERVICE_BAD_RESPONSE`

## 2.3 对外验证接口

- `src/main/java/com/bones/gateway/controller/AiDemoController.java`
- `src/main/java/com/bones/gateway/dto/AiChatRequestDto.java`

接口：
- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/stream`（`text/event-stream`）

## 2.4 配置模板更新

- `src/main/resources/application-dev.yml`
- `.env.example`

新增 AI 路径与重试参数配置项，支持本地/测试环境灵活切换。

## 3. 验收结果

1. 编译通过：
- `mvn -DskipTests compile`

2. 测试通过：
- `mvn test`
- `WebClientAiServiceClientTest`：2个用例通过

3. 联调验证通过：
- 使用本地 mock AI 服务（`/v1/chat`、`/v1/chat/stream`）进行端到端验证
- `POST /api/v1/ai/chat` 返回统一结构 `ApiResponse`
- `POST /api/v1/ai/chat/stream` 返回 SSE 数据流

## 4. IDEA 快速验证

1. 运行 `GatewayApplication`（dev profile + MySQL/Redis可用）。
2. 在 Run Configuration 加环境变量：

```text
AI_SERVICE_BASE_URL=http://127.0.0.1:18000
AI_SERVICE_CHAT_PATH=/v1/chat
AI_SERVICE_STREAM_PATH=/v1/chat/stream
AI_SERVICE_MAX_RETRIES=1
AI_SERVICE_RETRY_BACKOFF_MS=300
```

3. 调用验证：

```bash
curl -i -H "Content-Type: application/json" \
  -d '{"message":"hello-ai","conversationId":1,"userId":7}' \
  http://localhost:8080/api/v1/ai/chat

curl -i -N -H "Content-Type: application/json" \
  -d '{"message":"hello-stream","conversationId":1,"userId":7}' \
  http://localhost:8080/api/v1/ai/chat/stream
```
