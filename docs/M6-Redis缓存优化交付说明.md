# M6 Redis 缓存优化交付说明

## 1. 目标

在现有 M5.5 的基础上，补齐 M6 的两类缓存能力：

- 读路径缓存（会话列表、消息列表）
- 热点请求短缓存（同步聊天响应）

并保证写入后缓存可自动失效，避免脏数据。

## 2. 新增能力

## 2.1 读缓存

- 会话列表缓存：按 `userId + page + size` 维度缓存。
- 消息列表缓存：按 `conversationId + userId` 维度缓存。

命中时直接返回 Redis 数据，未命中时回源 DB 并回填缓存。

## 2.2 写后失效

以下操作会自动触发缓存失效：

- 会话创建、归档、touch、自动标题更新
- 消息追加、消息保存（聊天链路）、消息内容更新（流式增量）

失效策略采用“按前缀批量失效”。

## 2.3 热点响应缓存

- 同步聊天（`/conversations/{id}/chat`）支持热点响应短缓存。
- 缓存 key 由 `provider/model/userId/messages` 生成稳定哈希。
- 通过 metadata 可绕过缓存：`cacheBypass=true` 或 `noCache=true`。

## 2.4 指标

新增缓存指标（Micrometer Counter）：

- `jarvis.cache.read.hit`
- `jarvis.cache.read.miss`
- `jarvis.cache.read.evict`
- `jarvis.cache.hot.hit`
- `jarvis.cache.hot.miss`
- `jarvis.cache.hot.store`

## 3. 关键代码

- 配置：`config/M6CacheProperties`
- 读缓存：`service/ReadCacheService`
- 热点响应缓存：`service/HotResponseCacheService`
- 会话缓存接入：`service/ConversationService`
- 消息缓存接入：`service/MessageService`
- 热点缓存接入：`service/ChatService`

## 4. 配置项

`application-dev.yml` 与 `.env.example` 已新增：

- `JARVIS_CACHE_ENABLED`
- `JARVIS_CACHE_CONVERSATION_LIST_TTL_SECONDS`
- `JARVIS_CACHE_MESSAGE_LIST_TTL_SECONDS`
- `JARVIS_CACHE_HOT_RESPONSE_ENABLED`
- `JARVIS_CACHE_HOT_RESPONSE_TTL_SECONDS`

## 5. 验收建议

1. 先调用一次会话列表/消息列表接口，观察 DB 正常返回。
2. 再次调用同请求，观察耗时下降或 Redis key 已命中。
3. 执行会话归档/消息写入后再次读取，确认缓存已失效并返回最新数据。
4. 连续两次提交相同同步聊天请求，第二次应命中热点响应缓存。

