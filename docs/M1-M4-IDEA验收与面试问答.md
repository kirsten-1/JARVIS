# M1-M4 IDEA验收与面试问答

## 1. 在 IDEA 中如何验收 M4（可见结果）

说明：当前代码已包含 M4.5 稳定化补丁，以下先按 M4 主线验收。

### 1.1 运行配置检查

在 IDEA 打开：
- `Run | Edit Configurations | GatewayApplication`

建议配置：
- `Active profiles`: `dev`
- `Environment variables`（至少）：
  - `MYSQL_PASSWORD=你的MySQL密码`
  - `SPRING_PROFILES_ACTIVE=dev`
  - `AI_SERVICE_BASE_URL=http://127.0.0.1:18000`（或你的 AI mock 服务地址）

预期：
- 应用可正常启动，不再出现 `Dialect` 或 `RedisConnectionFailure` 报错。

### 1.2 M1 基线能力验收

浏览器访问：
- `http://localhost:8080/actuator/health`：返回 `UP`
- `http://localhost:8080/swagger-ui.html`：可看到接口文档

### 1.3 M4 代码结构验收（Project 面板）

重点文件：
- `src/main/java/com/bones/gateway/integration/ai/AiServiceClient.java`
- `src/main/java/com/bones/gateway/integration/ai/WebClientAiServiceClient.java`
- `src/main/java/com/bones/gateway/config/AiServiceProperties.java`
- `src/main/java/com/bones/gateway/config/WebClientConfig.java`
- `src/main/java/com/bones/gateway/controller/AiDemoController.java`

### 1.4 M4 接口行为验收（Swagger 或 HTTP Client）

接口：
- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/stream`

预期：
- `chat` 返回统一结构：`code/message/data/traceId/timestamp`
- `chat/stream` 返回 `text/event-stream`，可观察到 `delta` 事件，末尾有 `done`

### 1.5 M3 能力在 M4 中是否生效

你应能看到：
- 响应体包含 `traceId`
- 响应头带 `X-Trace-Id`
- 参数错误/业务错误都被统一结构包装

关键文件：
- `src/main/java/com/bones/gateway/common/ApiResponse.java`
- `src/main/java/com/bones/gateway/config/TraceIdFilter.java`
- `src/main/java/com/bones/gateway/config/GlobalExceptionHandler.java`

### 1.6 测试验收（推荐）

在 IDEA 运行：
- `src/test/java/com/bones/gateway/integration/ai/WebClientAiServiceClientTest.java`

预期 3 个用例通过：
- chat 成功路径
- chat 下游 500 映射异常
- stream `data:` 规范化解析

---

## 2. M1-M4 面试高频问题与参考高分回答

### Q1：为什么把项目拆成 M1-M4 里程碑？
参考回答：
我按“可运行优先”拆分：M1 先解决工程可启动，M2 固化数据模型，M3 统一网关行为，M4 打通 AI 调用链。每个阶段都能独立验收，问题定位更快，也降低了并行开发的风险。

### Q2：M1 主要价值是什么？
参考回答：
M1 解决的是工程可落地问题：统一包结构、环境配置、健康检查、Swagger 文档和测试入口。保证新成员能快速启动并验证服务，不会卡在环境差异上。

### Q3：为什么用 `application.yml + application-dev.yml + .env.example`？
参考回答：
这是一种分层配置策略：默认配置、开发环境配置、敏感参数模板分离。好处是可版本化、可审计、敏感信息不入库，并且能快速切换环境。

### Q4：M2 的会话/消息模型是怎么考虑的？
参考回答：
`conversation` 管会话元数据，`message` 管消息序列，1:N 结构贴合聊天域模型。索引优先覆盖用户会话列表和消息时间序查询，外键级联删除保证一致性。

### Q5：为什么引入 Flyway？
参考回答：
为了把数据库结构变更纳入工程管理。Flyway 让建表和演进可追踪、可回放、可审计，避免开发/测试/线上 schema 漂移。

### Q6：为什么枚举用字符串落库？
参考回答：
字符串可读性更高，且避免 enum ordinal 变更带来的历史数据语义错乱。虽然空间稍大，但稳定性和可维护性收益更高。

### Q7：M3 的统一响应解决了什么？
参考回答：
它统一了协议面，前端和调用方只解析一种结构。配合错误码和 traceId，联调效率和故障排查效率会明显提升。

### Q8：业务错误码和 HTTP 状态码如何分工？
参考回答：
HTTP 状态表达传输语义，业务码表达业务语义。比如都可能是 5xx，但业务码可区分 AI 超时、AI 不可达、AI 错误响应，便于监控和策略化处理。

### Q9：为什么要做全局异常处理？
参考回答：
把异常处理从各 Controller 收敛到统一出口，保证错误结构一致，减少重复代码，并避免内部异常细节直接暴露给外部。

### Q10：TraceId 在项目里如何落地？
参考回答：
入口 Filter 生成或透传 `X-Trace-Id`，写入 MDC，并回写响应头。这样可以把请求、日志、下游调用关联到同一条链路上。

### Q11：M4 为什么选 WebClient？
参考回答：
因为 M4 需要同步调用和流式 SSE，WebClient 对响应式流式处理更自然。它也更便于接入超时、重试、错误映射和指标埋点。

### Q12：AI 调用层的抽象是怎么设计的？
参考回答：
先定义 `AiServiceClient` 接口，再用 `WebClientAiServiceClient` 实现。上层控制器只依赖接口，后续替换不同模型服务时不需要改业务层代码。

### Q13：如何验证同步和流式都打通？
参考回答：
同步接口看统一响应结构是否正确；流式接口看 `text/event-stream` 是否持续输出并正常结束。再配合 MockWebServer 单测保障可回归。

### Q14：下游 AI 失败时你怎么处理？
参考回答：
我做了故障类型映射：超时、连接不可达、下游错误响应分别映射不同业务错误码和状态语义，避免上层只看到“调用失败”这一种笼统结果。

### Q15：为什么 M4.5 默认关闭重试？
参考回答：
聊天通常是 POST，重试会引发重复生成和潜在重复计费。默认关闭更安全，后续在具备幂等机制（如 requestId）后再按场景开启。

### Q16：可观测性方面做了什么？
参考回答：
补了 chat/stream 的延迟、错误、会话指标，且区分 success/error 标签，能在业务放量前先建立观测基线。

### Q17：M1-M4 最大风险点是什么？怎么控？
参考回答：
最大风险是环境不可运行和下游不稳定。我用 profile + env 模板规范启动，用统一错误模型和 traceId 提高可观测性，用单测覆盖关键异常路径降低回归风险。

### Q18：如何演进到简历里说的多模型路由？
参考回答：
会在 `AiServiceClient` 上层增加路由编排层，根据成本、时延、可用性选择模型，并加入熔断和降级策略。现有统一 DTO/错误码可以直接复用。

### Q19：这个项目和你短链接项目的方法论共性是什么？
参考回答：
共性是先规范协议与边界，再做性能和稳定性优化。短链接侧重缓存与限流，Jarvis 网关侧重异常语义、链路追踪、可观测性。

### Q20：如何客观描述当前进度（避免夸大）？
参考回答：
我会明确说已完成 M1-M4（含 M4.5 稳定化），已经打通网关到 AI 的同步/流式链路和数据层基础；会话业务编排与缓存优化在 M5/M6。

---

## 3. 面试作答模板（建议）

每题尽量按这个结构回答，稳定拿分：
1. 结论先行（我做了什么）
2. 设计取舍（为什么这么做）
3. 验证证据（怎么证明有效）
4. 下一步优化（如果继续做会怎么迭代）

