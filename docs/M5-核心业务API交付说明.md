# M5 核心业务 API 交付说明

## 1. 目标

- 从“演示级 AI 调用接口”升级为“可用于业务流转的会话 API”。
- 打通会话、消息、聊天三条主路径，形成最小可用聊天后端闭环。

## 2. 交付内容

## 2.1 新增接口

基路径：`/api/v1/conversations`

- `POST /api/v1/conversations`：创建会话
- `GET /api/v1/conversations?userId=&page=&size=`：分页查询会话
- `POST /api/v1/conversations/{conversationId}/archive?userId=`：归档会话
- `GET /api/v1/conversations/{conversationId}/messages?userId=`：查询消息历史
- `POST /api/v1/conversations/{conversationId}/messages`：手动追加消息
- `POST /api/v1/conversations/{conversationId}/chat`：聊天主链路（落库 + 调 AI + 回写）

控制器文件：
- `src/main/java/com/bones/gateway/controller/ConversationController.java`

## 2.2 服务层编排

- `ConversationService`
  - 创建会话
  - 分页列表
  - 会话归属校验（`conversationId + userId`）
  - 归档操作
- `MessageService`
  - 消息查询
  - 消息追加
- `ChatService`
  - 归档校验
  - USER 消息落库
  - 调用 `AiServiceClient`
  - ASSISTANT 消息落库
  - 回写会话更新时间

服务文件：
- `src/main/java/com/bones/gateway/service/ConversationService.java`
- `src/main/java/com/bones/gateway/service/MessageService.java`
- `src/main/java/com/bones/gateway/service/ChatService.java`

## 2.3 DTO 扩展

新增 DTO：
- `CreateConversationRequest`
- `ConversationItemResponse`
- `AppendMessageRequest`
- `MessageItemResponse`
- `ConversationChatRequest`
- `ConversationChatResponse`
- `PagedResult<T>`

目录：
- `src/main/java/com/bones/gateway/dto/`

## 2.4 错误码扩展

新增：
- `CONVERSATION_NOT_FOUND`
- `CONVERSATION_ARCHIVED`

文件：
- `src/main/java/com/bones/gateway/common/ErrorCode.java`

## 3. 与 M4.6 的衔接

`POST /api/v1/conversations/{id}/chat` 支持传：
- `provider`
- `model`
- `metadata`

会直接透传给 M4.6 的多厂商路由层，做到业务 API 与模型路由解耦。

## 4. 验收结果

- `mvn -DskipTests compile` 通过
- `mvn test` 通过

新增测试：
- `src/test/java/com/bones/gateway/service/ConversationServiceTest.java`
- `src/test/java/com/bones/gateway/service/ChatServiceTest.java`

## 5. 已知边界

- 当前鉴权仍用 `userId` 入参模拟，未接入 JWT/RBAC。
- 聊天 API 目前是同步回包，不含流式聊天落库链路（可在后续 M6/M7 补齐）。
- 消息 token 统计尚未与模型响应 usage 对齐。
