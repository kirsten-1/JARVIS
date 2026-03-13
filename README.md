# gateway_pro 开发计划与进度

本文件作为 `gateway_pro` 子项目的唯一进度记录页。后续每完成一个里程碑，直接在本文件更新状态、完成时间和验收结果。

## 文档导航

- `docs/项目完整规划与里程碑.md`：M1-M20 完整规划与当前状态
- `docs/M1-工程骨架交付说明.md`：M1 详细交付内容
- `docs/M3-网关基础能力交付说明.md`：M3 详细交付内容
- `docs/M4-AI调用层交付说明.md`：M4 详细交付内容
- `docs/M4.5-稳定化补丁说明.md`：M4.5 稳定化补丁说明
- `docs/M4.6-多厂商路由交付说明.md`：M4.6 多厂商路由交付内容
- `docs/M5-核心业务API交付说明.md`：M5 核心业务 API 交付内容
- `docs/M5.5-安全上下文流式增强交付说明.md`：JWT/RBAC/配额限流/上下文缓存/流式落库增强
- `docs/M6-Redis缓存优化交付说明.md`：读缓存、失效策略、热点响应缓存交付内容
- `docs/M7-测试与交付说明.md`：测试矩阵、冒烟脚本、交付与验收说明
- `docs/M8-前端联调控制台交付说明.md`：React + Ant Design 联调前端交付内容
- `docs/M9-组织权限与计费观测增强规划.md`：组织权限、真实计费、运营看板规划
- `docs/M10-容器化与CI交付说明.md`：Docker/Compose 本地一键启动与 GitHub Actions CI 交付
- `docs/M11-部署与发布交付说明.md`：生产部署、镜像发布、回滚与运行手册
- `docs/M12-可观测与运维加固交付说明.md`：Prometheus/Grafana 观测栈与运维脚本
- `docs/M13-Agent编排最小闭环交付说明.md`：Agent 最小闭环（Plan/Act/Observe/Respond）
- `docs/M14-Agent编排策略与工具治理首版说明.md`：Agent 规划模式与工具治理（首版）
- `docs/M15-Agent知识检索最小闭环交付说明.md`：知识片段入库 + 检索 + Agent 知识工具首版
- `docs/M16-Agent混合检索增强交付说明.md`：keyword/vector/hybrid 检索增强与向量化首版
- `docs/M17-Agent检索调优与可运营化说明.md`：检索权重/阈值配置化与结果可解释增强
- `docs/M18-Agent检索策略请求级覆盖说明.md`：请求级检索策略覆盖与 Agent 覆盖参数透传
- `docs/M19-Agent检索反馈闭环与自动调优说明.md`：检索反馈采集、策略推荐与 autoTune 闭环
- `docs/M20-Agent检索策略治理与来源审计说明.md`：workspace 策略模式治理（RECOMMEND/MANUAL/OFF）与 `overrideSource` 审计
- `docs/A04-网关与AI服务统一编排交付说明.md`：A04（Gateway + ai-service 同栈编排与一键验收）
- `docs/A05-AI服务镜像发布脚本交付说明.md`：A05（ai-service 镜像构建/发布脚本）
- `docs/A06-阶段验收与检查点报告说明.md`：A06（阶段验收报告与关键节点留痕）
- `docs/A07-阶段版本节点与回滚清单说明.md`：A07（阶段节点提交模板 + 回滚清单）
- `docs/A08-预推送守卫与敏感信息扫描说明.md`：A08（预推送守卫 + 密钥样式扫描）
- `docs/A09-AI服务本地构建编排说明.md`：A09（ai-service 同栈本地构建与无远端镜像依赖）
- `docs/A10-CI质量门禁与预推送守卫集成说明.md`：A10（CI 质量门禁接入 A08 轻量守卫）
- `docs/A11-性能基线与回归报告说明.md`：A11（性能基线采集与回归报告）
- `docs/A12-CI夜间基线回归说明.md`：A12（A11 基线接入夜间/手动 CI 回归）
- `docs/A13-基线差异对比与回归判定说明.md`：A13（A11 报告差异比较与回归判定）
- `docs/A14-参考基线门禁与CI接入说明.md`：A14（reference baseline 门禁与 CI 接入）
- `docs/A15-基线趋势与漂移监控说明.md`：A15（多报告趋势分析与漂移监控）
- `docs/A16-趋势门禁与CI接入说明.md`：A16（趋势门禁脚本与 CI 接入）
- `docs/A17-发布就绪总览与CI编排说明.md`：A17（多报告发布就绪总览与 CI 编排）
- `docs/A18-候选发布证据包归档说明.md`：A18（候选发布证据包打包归档）
- `docs/A19-证据包审计与索引说明.md`：A19（证据包完整性审计与索引台账）
- `docs/A20-收官决策与交付快照说明.md`：A20（收官 go/hold 决策与交付快照）
- `docs/B01-知识与工作流中台子项目规划.md`：B01（下一子项目：RAG + Workflow 中台规划）
- `docs/B02-文档解析分块入库MVP交付说明.md`：B02（知识中台 ingest 解析/分块/入库 MVP）
- `docs/B03-混合检索与RerankMVP交付说明.md`：B03（BM25 + dense + rerank 检索链路）
- `docs/B04-RAG评测集与离线评测基线说明.md`：B04（固定评测集 + 离线评测基线）
- `docs/B05-WorkflowDAG执行引擎MVP说明.md`：B05（Workflow 定义/执行/幂等/查询 MVP）
- `docs/B06-人工审核Webhook与模板流程首批说明.md`：B06（approval/webhook/template 首批）
- `docs/多厂商AI使用指南.md`：多厂商接入与路由使用指南
- `docs/产品不足与增强路线.md`：当前产品不足与可增强路线
- `docs/启动问题排查记录.md`：启动问题排查沉淀

## 项目目标（当前阶段）

- 搭建 Jarvis 第一项目中的 **Spring Boot 网关**（模块一的一部分）。
- 先完成从 0 到可联调的 Java 网关，再向 Agent/RAG/Workflow/记忆模块扩展。

## 本项目在 Jarvis 中的作用

`gateway_pro` 当前是 Jarvis 的统一 AI 网关与会话编排中枢，负责：

- 对外提供统一 API（同步/流式聊天、会话、消息），作为前端与业务侧入口。
- 对内屏蔽多厂商模型协议差异，提供 provider/model 路由与 fallback。
- 提供通用治理能力：JWT/RBAC、限流、配额、计费统计与可观测指标。
- 提供会话基础设施：消息持久化、上下文拼装、Redis 缓存与流式恢复。

在全局架构中，它为后续 Agent、RAG、Workflow、Memory 模块提供可复用的模型调用底座与流量入口。

## 多厂商 AI 重点特性（M4.6）

- 支持多厂商统一接入：DeepSeek、Gemini、阿里云、腾讯云、GLM、MiniMax、local、ai-service（上游模型网关）。
- 支持请求级路由：可通过 `provider/model` 动态切换目标厂商与模型。
- 支持双协议适配：`OPENAI_COMPATIBLE` + `GEMINI`，同一网关统一对外接口。
- 支持故障回退：主 provider 异常时可自动 fallback 到备 provider。
- 支持统一错误语义：provider 未配置/禁用/配置错误有独立错误码。
- 支持统一配置模板：`application-dev.yml` + `.env.example` 已提供多厂商变量。

完整使用示例与排障说明见：
- `docs/多厂商AI使用指南.md`

## 里程碑计划

| 里程碑 | 内容 | 预计工期 | 状态 |
| --- | --- | --- | --- |
| M1 | 工程骨架（启动类、配置、健康检查、Swagger、环境模板） | 0.5 天 | `completed` |
| M2 | 数据层（Conversation/Message + Repository + 迁移） | 1 天 | `completed` |
| M3 | 网关基础能力（统一返回、校验、异常处理） | 1 天 | `completed` |
| M4 | AI 调用层（WebClient + AiServiceClient + 同步/流式） | 1.5 天 | `completed` |
| M4.5 | 稳定化补丁（错误语义/重试策略/流式规范/指标） | 0.5 天 | `completed` |
| M4.6 | 多厂商路由（DeepSeek/Gemini/阿里云/腾讯云/GLM/MiniMax） | 1 天 | `completed` |
| M5 | 核心业务 API（会话/消息/聊天） | 1.5 天 | `completed` |
| M5.5 | 安全与体验增强（JWT+RBAC+配额限流+上下文缓存+流式落库） | 1.5 天 | `completed` |
| M6 | Redis 缓存与性能优化（上下文缓存、响应缓存） | 1 天 | `completed` |
| M7 | 测试与交付（单测/集成测/压测基线/文档） | 1 天 | `completed` |
| M8 | 前端联调控制台（React + Ant Design） | 1 天 | `completed` |
| M9 | 组织权限 + 真实计费 + 运营看板 | 2 天 | `completed` |
| M10 | 生产交付（容器化 + CI） | 1 天 | `completed` |
| M11 | 部署与发布（prod 编排 + 镜像发布 + 运行手册） | 1 天 | `completed` |
| M12 | 可观测与运维加固（Prometheus + Grafana） | 1 天 | `completed` |
| M13 | Agent 编排最小闭环（Plan/Act/Observe） | 1 天 | `completed` |
| M14 | Agent 编排增强（LLM Planner + Tool Allowlist） | 1 天 | `completed` |
| M15 | Agent 编排知识增强（知识片段 + 检索工具 + RAG 首批） | 1 天 | `completed` |
| M16 | Agent 混合检索增强（keyword/vector/hybrid + embedding） | 1 天 | `completed` |
| M17 | Agent 检索调优与可运营化（权重阈值配置 + 返回生效参数） | 1 天 | `completed` |
| M18 | Agent 检索策略请求级覆盖（API/Agent override + 生效标记） | 1 天 | `completed` |
| M19 | Agent 检索反馈闭环与自动调优（feedback -> recommendation -> autoTune） | 1 天 | `completed` |
| M20 | Agent 检索策略治理与来源审计（workspace policy mode + overrideSource） | 1 天 | `completed` |

## A 阶段（ai-service 子轨）

- [x] A03：Gateway 接入 ai-service 上游 provider（`aiservice`）
- [x] A04：Gateway + ai-service 统一生产编排与一键冒烟（可选 profile）
- [x] A05：ai-service 镜像发布脚本（tag/latest/push）
- [x] A06：阶段验收与检查点报告脚本（不自动发布）
- [x] A07：阶段版本节点报告与提交模板（含回滚清单）
- [x] A08：预推送守卫脚本（冒烟+敏感信息扫描+报告）
- [x] A09：ai-service 同栈本地构建（缺失远端镜像时自动 build）
- [x] A10：CI 质量门禁（集成 A08 轻量守卫 + 报告归档）
- [x] A11：性能基线脚本（冷/热缓存 + 阈值检查 + 报告输出）
- [x] A12：CI 夜间基线回归（自动执行 A11 + artifact 归档）
- [x] A13：基线差异对比（A11 报告对比 + 回归判定）
- [x] A14：参考基线门禁（capture/check + CI gate workflow）
- [x] A15：基线趋势分析（多报告聚合 + 漂移判定 + 可选严格门禁）
- [x] A16：趋势门禁与 CI 接入（A16 gate + 定时巡检 workflow）
- [x] A17：发布就绪总览（A08/A11/A13/A15/A16 聚合 + readiness workflow）
- [x] A18：候选发布证据包（A08~A17 报告打包归档，不执行 release）
- [x] A19：证据包审计与索引（完整性校验 + bundle index）
- [x] A20：收官决策与交付快照（go/hold 决策 + closeout package）

## B 阶段（Knowledge & Workflow 子轨）

- [x] B01：知识与工作流中台子项目规划（范围、目标、里程碑、风险控制）
- [x] B02：文档解析 + 分块 + 入库管线 MVP（knowledge-workflow-service）
- [x] B03：混合检索与 rerank MVP
- [x] B04：RAG 评测集与离线评测基线
- [x] B05：Workflow DAG 执行引擎 MVP
- [x] B06：人工审核/Webhook/模板流程首批
- [ ] B07：gateway/agent 端到端集成
- [ ] B08：质量/性能/成本门禁收官

## M1 验收清单

- [x] 替换默认模板工程为 Spring Boot 启动入口
- [x] 建立后续开发包结构骨架
- [x] 增加 `application.yml` 与 `application-dev.yml`
- [x] 增加 `.env.example` 环境变量模板
- [x] 增加 Actuator 依赖（健康检查）
- [x] 执行构建验证（`mvn test`）

## M2 验收清单

- [x] 增加 `Conversation`、`Message` 实体
- [x] 增加会话状态与消息角色枚举
- [x] 增加 JPA Repository（会话/消息）
- [x] 增加 Flyway 迁移脚本 `V1__init_conversation_message.sql`
- [x] 启动验证 Flyway 在 MySQL 上成功建表

## M3 验收清单

- [x] 增加统一响应结构 `ApiResponse`
- [x] 增加错误码模型与业务异常 `ErrorCode` / `BusinessException`
- [x] 增加全局异常处理 `GlobalExceptionHandler`
- [x] 增加请求 traceId 过滤器 `TraceIdFilter`
- [x] 增加参数校验示例接口并验证成功/失败返回结构统一

## M4 验收清单

- [x] 实现 `AiServiceClient` 接口与 `WebClientAiServiceClient`
- [x] 实现 AI 配置项 `AiServiceProperties`（URL、超时、重试、路径）
- [x] 打通同步调用链：`POST /api/v1/ai/chat`
- [x] 打通流式调用链：`POST /api/v1/ai/chat/stream`
- [x] 完成下游异常映射（超时/不可达/错误响应）
- [x] 增加 AI Client 单元测试（MockWebServer）

## M4.5 验收清单

- [x] `BusinessException` 支持携带 HTTP 状态码（如 503/504/502）
- [x] 全局异常处理按异常状态码返回（不再全部固定 400）
- [x] AI 调用默认关闭重试（`retry-enabled=false`，`max-retries=0`）
- [x] AI 请求增加 `X-Request-Id`，支持上游防重
- [x] 流式返回做 `data:` 规范化解析，过滤 `[DONE]`
- [x] 增加 AI 调用基础指标（latency/errors/sessions）
- [x] 增加回归测试（异常状态映射 + stream 解析）

## M4.6 验收清单

- [x] AI 请求支持 `provider` / `model` 两级路由参数
- [x] 支持多 Provider 配置与动态选择（含本地 provider）
- [x] 支持 OpenAI-compatible 与 Gemini 两类协议适配
- [x] 支持 provider 级 fallback（主 provider 失败后切换）
- [x] 新增 provider 错误码（未配置/禁用/配置无效）
- [x] 扩展 `.env.example` 与 `application-dev.yml` 多厂商模板
- [x] 增加路由/fallback/gemini 解析单元测试

## M5 验收清单

- [x] 会话管理接口：创建、分页列表、归档
- [x] 消息管理接口：消息追加、历史查询
- [x] 聊天主链路：用户消息落库 -> AI 调用 -> 助手消息落库
- [x] 会话归属校验：按 `conversationId + userId` 校验访问权限
- [x] 归档会话禁聊：已归档会话返回业务错误
- [x] 新增 M5 服务层测试（ConversationService/ChatService）

## M5.5 验收清单

- [x] 接入 JWT 鉴权与 RBAC（USER/ADMIN）
- [x] 新增开发态发 token 接口（dev/test profile）
- [x] 会话接口从“裸 userId”升级为“认证上下文 + 管理员代操作”模型
- [x] 增加 Redis 配额与限流守卫（分钟级限流 + 日配额）
- [x] 增加 provider/model 维度配额控制（按用户日维度）
- [x] 增加会话上下文拼装与 Redis 缓存（窗口裁剪 + TTL）
- [x] 增加会话流式聊天接口并支持流式消息落库
- [x] 增加流式会话恢复查询接口（`streamId` 快照）
- [x] 增加首 token 时延指标（TTFT）
- [x] 增加用量计费统计（prompt/completion tokens + 估算成本）
- [x] 增加会话自动标题生成（默认标题首轮自动替换）
- [x] 增加安全/守卫/上下文/流式相关单元测试

## M6 验收清单

- [x] 增加会话列表读缓存（按 `userId/page/size`）
- [x] 增加消息列表读缓存（按 `conversationId/userId`）
- [x] 增加写操作缓存失效（会话更新、消息写入后自动失效）
- [x] 增加热点同步响应缓存（短 TTL，支持 `metadata.cacheBypass=true` 绕过）
- [x] 增加缓存命中/未命中/写入/失效指标计数
- [x] 增加 M6 配置项模板（`application-dev.yml` / `.env.example`）

## M7 验收清单

- [x] 补充 M6 相关测试（`ReadCacheServiceTest`、`HotResponseCacheServiceTest`）
- [x] 增加热点缓存命中场景测试（`ChatServiceTest`）
- [x] 提供本地冒烟脚本（`scripts/m7_smoke.sh`）
- [x] 提供 M7 测试与交付文档（测试矩阵、验收步骤、命令清单）
- [x] 更新里程碑文档，固化 M7 完成状态与风险说明
- [x] 本机补验 `WebClientAiServiceClientTest`（6 用例通过，`exit code 0`）

## M8 验收清单

- [x] 新建 `frontend` 子工程（Vite + React + TypeScript）
- [x] 接入 Ant Design 组件体系（表单、卡片、布局）
- [x] 支持开发态 token 签发与展示（`/api/v1/auth/dev-token`）
- [x] 支持会话创建与 `conversationId` 维护
- [x] 支持 `workspaceId` 回填与手动输入
- [x] 支持同步聊天联调（`/api/v1/conversations/{id}/chat`）
- [x] 支持流式聊天联调（`/api/v1/conversations/{id}/chat/stream` + SSE 解析）
- [x] 支持流式 meta/delta/done 展示与手动停止
- [x] 支持运营指标查询与展示（`/api/v1/metrics/overview`、`/api/v1/metrics/providers`）

## M9 规划清单（已完成）

- [x] 组织级权限模型（workspace/member/role）落地（M9.1 首批）
- [x] workspace 作用域资源隔离与鉴权链路改造（含 `VIEWER` 只读、`ADMIN/OWNER` 管理）
- [x] 多厂商 usage 实际值映射与账本口径升级（M9.2 首批：sync `actual/estimated` + workspace 账本）
- [x] 运营指标 API（成功率、fallback、TTFT、成本趋势）（M9.3 首批）
- [x] 流式 usage 对齐（stream 返回 usage 时记 `actual`，否则回落 `estimated`）
- [x] M9 文档与测试补齐（首批）
- [x] M9 验收脚本补齐（`scripts/m9_smoke.sh`）

## M10 验收清单

- [x] 新增后端容器构建文件（`Dockerfile`、`.dockerignore`）
- [x] 新增前端容器构建文件（`frontend/Dockerfile`、`frontend/.dockerignore`）
- [x] 新增 Nginx 反向代理配置（`frontend/nginx/default.conf`）
- [x] 新增一键联调编排（`docker-compose.yml`）
- [x] 新增 Compose 启停脚本（`scripts/m10_up.sh`、`scripts/m10_down.sh`）
- [x] 新增 GitHub Actions CI（`.github/workflows/ci.yml`）
- [x] 新增 M10 交付文档（`docs/M10-容器化与CI交付说明.md`）

## M11 验收清单

- [x] 新增生产 profile 配置（`application-prod.yml`）
- [x] 新增生产部署编排（`docker-compose.prod.yml` + `.env.prod.example`）
- [x] 新增生产启停与冒烟脚本（`scripts/m11_prod_up.sh`、`scripts/m11_prod_down.sh`、`scripts/m11_smoke.sh`）
- [x] 新增镜像发布脚本（`scripts/m11_release.sh`）
- [x] 新增 GHCR 发布工作流（`.github/workflows/release-images.yml`）
- [x] 优化前端 Nginx 代理参数以提升流式稳定性（关闭代理缓冲）
- [x] 新增 M11 交付文档（`docs/M11-部署与发布交付说明.md`）

## M12 验收清单

- [x] 指标端点暴露：新增 Prometheus Registry 并开放 `/actuator/prometheus`
- [x] 新增观测编排：`docker-compose.observability.yml`
- [x] 新增 Alertmanager 配置：`monitoring/alertmanager/alertmanager.yml`
- [x] 新增 Prometheus 抓取配置：`monitoring/prometheus/prometheus.yml`
- [x] 新增 Prometheus 告警规则：`monitoring/prometheus/alerts.yml`
- [x] 新增 Grafana 数据源与看板预置配置
- [x] 新增观测脚本：`m12_obs_up/down/smoke`
- [x] 新增 M12 交付文档（`docs/M12-可观测与运维加固交付说明.md`）

## M13 验收清单

- [x] 新增 Agent 编排服务：`AgentOrchestrationService`
- [x] 新增 Agent 会话接口：`POST /api/v1/conversations/{id}/agent/run`
- [x] 支持最小工具链：`time_now` / `workspace_metrics_overview` / `conversation_digest`
- [x] 支持计划-执行-观察-回答闭环（Plan/Act/Observe/Respond）
- [x] 新增 M13 冒烟脚本：`scripts/m13_smoke.sh`
- [x] 新增 M13 交付文档（`docs/M13-Agent编排最小闭环交付说明.md`）

## M14 验收清单（已完成）

- [x] Agent 增加规划模式：`rule`（默认）/`llm_json`
- [x] Agent 增加工具白名单治理：`metadata.allowedTools`
- [x] `llm_json` 规划结果增加 JSON 解析与兜底回退（回退到规则规划）
- [x] 增加 M14 首版测试覆盖（LLM planner + allowlist）
- [x] 增加工具注册中心（可插拔工具 SPI）
- [x] 增加工具级超时/重试策略（`toolTimeoutMs`/`toolMaxRetries`）
- [x] 增加原生 Function Calling 协议编排与审计
- [x] 增加工具级幂等键（`toolIdempotencyKey` + TTL）
- [x] 增加工具级策略中心（集中策略解析与执行治理）

## M15 验收清单（已完成）

- [x] 新增知识库数据模型：`knowledge_snippet`（Flyway `V3`）
- [x] 新增知识片段 API：创建/更新/删除/检索（workspace 范围）
- [x] 新增知识检索服务：关键词匹配 + 轻量打分排序
- [x] 新增 Agent 工具：`workspace_knowledge_search`
- [x] Agent 规则规划新增知识检索触发关键词
- [x] 新增 M15 冒烟脚本：`scripts/m15_smoke.sh`
- [x] 新增单测：`KnowledgeBaseServiceTest`
- [x] 新增 M15 交付文档（`docs/M15-Agent知识检索最小闭环交付说明.md`）

## M16 验收清单（已完成）

- [x] 新增向量化能力：`KnowledgeEmbeddingService`（本地 embedding 首版）
- [x] 知识片段新增 embedding 字段与迁移：`V4__knowledge_snippet_embedding.sql`
- [x] 检索新增模式参数：`searchMode=keyword/vector/hybrid`
- [x] 检索响应新增 `searchMode` 字段，便于调用侧审计
- [x] Agent 知识工具支持 `searchMode` 参数与 `knowledgeSearchMode` 元数据
- [x] 新增 M16 冒烟脚本：`scripts/m16_smoke.sh`
- [x] 补充单测覆盖：`KnowledgeBaseServiceTest`（vector/hybrid 路径）
- [x] 新增 M16 交付文档（`docs/M16-Agent混合检索增强交付说明.md`）

## M17 验收清单（已完成）

- [x] 检索参数配置化：`jarvis.knowledge.search.*`（默认模式、候选上限、limit、阈值、权重）
- [x] 混合检索权重归一化：支持非 1.0 配置自动归一化生效
- [x] 向量/混合检索阈值控制：`vectorMinSimilarity`、`hybridMinScore`
- [x] 检索响应新增可解释字段：`keywordWeight`、`vectorWeight`、`scoreThreshold`
- [x] 配置模板补齐：`application-dev.yml`、`application-prod.yml`、`.env*.example`
- [x] 新增 M17 冒烟脚本：`scripts/m17_smoke.sh`
- [x] 补充单测覆盖：`KnowledgeBaseServiceTest`（权重归一化与阈值路径）
- [x] 新增 M17 交付文档（`docs/M17-Agent检索调优与可运营化说明.md`）

## M18 验收清单（已完成）

- [x] 检索 API 增加请求级覆盖参数：`vectorMinSimilarity`、`hybridMinScore`、`hybridKeywordWeight`、`hybridVectorWeight`、`maxCandidates`
- [x] 检索服务支持请求级覆盖策略（不改全局配置即可单次调参）
- [x] 检索响应增加策略生效标记：`overrideApplied`、`maxCandidates`
- [x] Agent 知识工具支持覆盖参数透传（tool args / metadata 两种输入）
- [x] Agent 工具输出增加生效策略回显（便于审计与排障）
- [x] 新增 M18 冒烟脚本：`scripts/m18_smoke.sh`
- [x] 补充单测覆盖：`KnowledgeBaseServiceTest`（请求级 override 路径）
- [x] 新增 M18 交付文档（`docs/M18-Agent检索策略请求级覆盖说明.md`）

## M19 验收清单（已完成）

- [x] 新增检索反馈 API：`POST /api/v1/knowledge/retrieval/feedback`
- [x] 新增策略推荐 API：`GET /api/v1/knowledge/retrieval/recommendation`
- [x] 新增反馈聚合服务：`KnowledgeRetrievalPolicyService`（按 workspace 聚合反馈并产出推荐参数）
- [x] 检索接口新增 `autoTune` 开关：无显式 override 时可自动套用推荐策略
- [x] Agent 知识工具新增 `autoTune` 与 `knowledgeAutoTune`（metadata）支持
- [x] 新增 M19 冒烟脚本：`scripts/m19_smoke.sh`
- [x] 新增单测：`KnowledgeRetrievalPolicyServiceTest`
- [x] 新增 M19 交付文档（`docs/M19-Agent检索反馈闭环与自动调优说明.md`）

## M20 验收清单（已完成）

- [x] 新增 workspace 检索策略治理 API：`GET/PUT /api/v1/knowledge/retrieval/policy`
- [x] 新增策略模式：`RECOMMEND` / `MANUAL` / `OFF`
- [x] 新增持久化策略表：`knowledge_retrieval_policy`（Flyway `V5__knowledge_retrieval_policy.sql`）
- [x] 搜索响应新增来源审计字段：`overrideSource`
- [x] `autoTune` 决策链支持来源区分：`request_override` / `workspace_manual_policy` / `auto_recommendation` / `none`
- [x] Agent 知识工具输出增加 `overrideSource` 回显
- [x] 新增 M20 冒烟脚本：`scripts/m20_smoke.sh`
- [x] 补充单测：`KnowledgeRetrievalPolicyServiceTest`（策略模式与配置更新路径）
- [x] 新增 M20 交付文档（`docs/M20-Agent检索策略治理与来源审计说明.md`）

## A04 验收清单（已完成）

- [x] 生产 compose 新增可选 `aiservice` 服务（`profiles: ["aiservice"]`）
- [x] `.env.prod.example` 增加 A04 配置模板（`AI_AISERVICE_COMPOSE_ENABLED` + `AIS_*`）
- [x] `m11_prod_up/down/smoke` 支持 A04 一键拉起/下线/校验
- [x] 新增 A04 冒烟脚本：`scripts/a04_smoke.sh`
- [x] 新增 A04 交付文档：`docs/A04-网关与AI服务统一编排交付说明.md`

## A05 验收清单（已完成）

- [x] 新增 ai-service 发布脚本：`scripts/a05_release_aiservice.sh`
- [x] 支持 tag 构建 + 可选 latest 打标 + 可选 push
- [x] 支持自定义 `AISERVICE_CONTEXT_DIR` / `AISERVICE_DOCKERFILE`
- [x] 新增 A05 交付文档：`docs/A05-AI服务镜像发布脚本交付说明.md`

## A06 验收清单（已完成）

- [x] 新增检查点脚本：`scripts/a06_checkpoint.sh`
- [x] 支持验收结果自动汇总为 Markdown 报告（`docs/reports/*.md`）
- [x] 支持可选先拉起环境（`RUN_UP_FIRST=true`）
- [x] 支持阶段性提交建议输出（不自动执行 git push）
- [x] 新增 A06 交付文档：`docs/A06-阶段验收与检查点报告说明.md`

## A07 验收清单（已完成）

- [x] 新增阶段节点脚本：`scripts/a07_stage_node.sh`
- [x] 自动生成阶段报告与 commit 模板（`docs/reports/stages/`）
- [x] 报告内置回滚清单（路由回滚 + 镜像回滚）
- [x] 不自动 commit/push/release，仅提供建议命令
- [x] 新增 A07 交付文档：`docs/A07-阶段版本节点与回滚清单说明.md`

## A08 验收清单（已完成）

- [x] 新增预推送守卫脚本：`scripts/a08_prepush_guard.sh`
- [x] 增加 `.env/.env.prod` 跟踪检查
- [x] 增加密钥样式字符串扫描（预防误提交）
- [x] 支持自动汇总 smoke + 安全检查报告（`docs/reports/a08_prepush_*.md`）
- [x] 新增 A08 交付文档：`docs/A08-预推送守卫与敏感信息扫描说明.md`

## A09 验收清单（已完成）

- [x] `m11_prod_up.sh` 增加 ai-service 镜像本地检测与自动构建（可配置）
- [x] `docker-compose.prod.yml` 的 `aiservice` 默认镜像改为 `jarvis-ai-service:local`
- [x] `.env.prod.example` 增加本地构建变量（`AISERVICE_AUTO_BUILD`/`AISERVICE_BUILD_CONTEXT`/`AISERVICE_DOCKERFILE`）
- [x] 补充 A04 与多厂商文档中的本地构建示例
- [x] 新增 A09 交付文档：`docs/A09-AI服务本地构建编排说明.md`

## A10 验收清单（已完成）

- [x] 新增 CI 守卫脚本：`scripts/a10_ci_guard.sh`
- [x] `ci.yml` 增加 `prepush-guard` 任务并作为测试/构建前置门禁
- [x] CI 上传 A08 守卫报告 artifact（失败可追溯）
- [x] 新增 A10 交付文档：`docs/A10-CI质量门禁与预推送守卫集成说明.md`

## A11 验收清单（已完成）

- [x] 新增基线脚本：`scripts/a11_baseline.sh`
- [x] 采集 `health`、会话列表冷/热、消息列表冷/热指标（可选 agent）
- [x] 自动生成基线报告：`docs/reports/a11_baseline_*.md`
- [x] 支持阈值检查与 `STRICT=true` 严格模式
- [x] 新增 A11 交付文档：`docs/A11-性能基线与回归报告说明.md`

## A12 验收清单（已完成）

- [x] 新增夜间/手动基线 workflow：`.github/workflows/a12-baseline.yml`
- [x] CI 自动拉起 `mysql + redis + gateway` 后执行 A11 脚本
- [x] 自动上传 A11 报告 artifact（失败可追溯）
- [x] 失败时输出容器诊断日志，收尾自动 down
- [x] 新增 A12 交付文档：`docs/A12-CI夜间基线回归说明.md`

## A13 验收清单（已完成）

- [x] 新增差异脚本：`scripts/a13_baseline_diff.sh`
- [x] 支持自动选择最新两份 A11 报告进行 P90 对比
- [x] 支持 `pass/warn/fail` 回归判定与阈值配置
- [x] 支持 `STRICT=true` 作为门禁退出码
- [x] 自动生成差异报告：`docs/reports/a13_baseline_diff_*.md`
- [x] 新增 A13 交付文档：`docs/A13-基线差异对比与回归判定说明.md`

## A14 验收清单（已完成）

- [x] 新增门禁脚本：`scripts/a14_baseline_gate.sh`（`capture/check`）
- [x] 引入 reference baseline（`docs/baseline/a11_reference.md`）机制
- [x] 门禁结果输出：`a14_gate_*.md` + `a14_gate_diff_*.md`
- [x] 新增 CI 门禁 workflow：`.github/workflows/a14-baseline-gate.yml`
- [x] 新增 A14 交付文档：`docs/A14-参考基线门禁与CI接入说明.md`

## A15 验收清单（已完成）

- [x] 新增趋势脚本：`scripts/a15_baseline_trend.sh`
- [x] 支持聚合多份 A11 报告并输出 P90 趋势摘要
- [x] 支持 `improved/stable/regressed` 判定与 `pass/warn/fail` 结论
- [x] 支持 `STRICT` / `FAIL_ON_WARN` 门禁行为
- [x] 自动生成趋势报告：`docs/reports/a15_baseline_trend_*.md`
- [x] 新增 A15 交付文档：`docs/A15-基线趋势与漂移监控说明.md`

## A16 验收清单（已完成）

- [x] 新增趋势门禁脚本：`scripts/a16_trend_gate.sh`
- [x] 在 A15 基础上输出 gate 结论（`pass/warn/fail`）与原因
- [x] 新增 A16 CI workflow：`.github/workflows/a16-trend-gate.yml`
- [x] CI 自动生成多轮 A11 样本并执行 A16 门禁
- [x] 自动上传 A11/A15/A16 报告 artifact
- [x] 新增 A16 交付文档：`docs/A16-趋势门禁与CI接入说明.md`

## A17 验收清单（已完成）

- [x] 新增发布就绪汇总脚本：`scripts/a17_readiness_bundle.sh`
- [x] 聚合 A08/A11/A13/A15/A16（可选 A14）并输出统一结论
- [x] 支持 `REQUIRED_CHECKS` / `OPTIONAL_CHECKS` 策略配置
- [x] 支持 `FAIL_ON_WARN` / `FAIL_ON_MISSING` 门禁策略
- [x] 新增 A17 CI workflow：`.github/workflows/a17-readiness-bundle.yml`
- [x] 新增 A17 交付文档：`docs/A17-发布就绪总览与CI编排说明.md`

## A18 验收清单（已完成）

- [x] 新增证据包脚本：`scripts/a18_release_candidate_bundle.sh`
- [x] 自动收集 A08/A11/A13/A15/A16/A17（可选 A14）最新报告
- [x] 生成归档包：`docs/reports/bundles/*.tar.gz`（含 `manifest` + `checksums`）
- [x] 支持 `REQUIRE_A17_PASS` 与 `STRICT` 门禁策略
- [x] 新增 A18 CI workflow：`.github/workflows/a18-release-candidate-bundle.yml`
- [x] 新增 A18 交付文档：`docs/A18-候选发布证据包归档说明.md`

## A19 验收清单（已完成）

- [x] 新增审计脚本：`scripts/a19_bundle_audit.sh`
- [x] 校验 A18 证据包解包、manifest、checksums 完整性
- [x] 生成审计报告：`docs/reports/a19_bundle_audit_*.md`
- [x] 生成索引台账：`docs/reports/bundles/a19_bundle_index.md`
- [x] 新增 A19 CI workflow：`.github/workflows/a19-bundle-audit.yml`
- [x] 新增 A19 交付文档：`docs/A19-证据包审计与索引说明.md`

## A20 验收清单（已完成）

- [x] 新增收官脚本：`scripts/a20_final_closeout.sh`
- [x] 汇总 A17/A18/A19 并输出收官决策（`go/hold`）
- [x] 生成收官快照：`docs/reports/bundles/a20_closeout_snapshot_*.json`
- [x] 生成收官归档包：`docs/reports/bundles/a20_closeout_package_*.tar.gz`
- [x] 新增 A20 CI workflow：`.github/workflows/a20-final-closeout.yml`
- [x] 新增 A20 交付文档：`docs/A20-收官决策与交付快照说明.md`

## B01 验收清单（已完成）

- [x] 定义下一子项目范围（RAG 中台 + Workflow 中台）
- [x] 固化目标指标（质量/成功率/性能/成本）
- [x] 输出 B02~B08 里程碑与本周可执行清单
- [x] 固化风险与控制策略
- [x] 新增 B01 规划文档：`docs/B01-知识与工作流中台子项目规划.md`

## B02 验收清单（已完成）

- [x] 新建 `knowledge-workflow-service` 子目录与 FastAPI 最小工程骨架
- [x] 实现文档解析（text/markdown/html）与可配置分块（chunk size/overlap）
- [x] 实现入库链路（document/chunks JSONL 持久化）
- [x] 提供基础检索接口（词法检索）
- [x] 新增 B02 冒烟脚本：`knowledge-workflow-service/scripts/b02_smoke.sh` + `scripts/b02_smoke.sh`
- [x] 新增 B02 交付文档：`docs/B02-文档解析分块入库MVP交付说明.md`

## B03 验收清单（已完成）

- [x] 实现 BM25 检索能力（`search_mode=bm25`）
- [x] 实现 dense 近似检索能力（`search_mode=dense`）
- [x] 实现 hybrid 融合检索（`search_mode=hybrid`，权重可配置）
- [x] 实现 hybrid rerank 重排（`rerank=true`）
- [x] 扩展 search 响应调试字段（strategy、scores、candidates）
- [x] 新增 B03 冒烟脚本：`knowledge-workflow-service/scripts/b03_smoke.sh` + `scripts/b03_smoke.sh`
- [x] 新增 B03 交付文档：`docs/B03-混合检索与RerankMVP交付说明.md`

## B04 验收清单（已完成）

- [x] 新增固定评测集：`knowledge-workflow-service/eval/b04_eval_dataset.json`
- [x] 实现离线评测脚本（ingest + search + hit_rate/mrr/recall/latency）
- [x] 支持 gate 阈值与 strict 模式（`TARGET_HIT_RATE`、`TARGET_MRR`、`STRICT`）
- [x] 产出 markdown/json 双报告到 `docs/reports/`
- [x] 新增 B04 执行脚本：`knowledge-workflow-service/scripts/b04_eval.sh` + `scripts/b04_eval.sh`
- [x] 新增 B04 交付文档：`docs/B04-RAG评测集与离线评测基线说明.md`

## B05 验收清单（已完成）

- [x] 新增 Workflow API：create/list/detail/run/get-run
- [x] 实现 Workflow DAG 校验（单 start、无环、可达性、边合法）
- [x] 实现执行器能力（条件分支、节点重试、节点超时判定）
- [x] 实现运行幂等（`idempotency_key`）
- [x] 新增 B05 冒烟脚本：`knowledge-workflow-service/scripts/b05_smoke.sh` + `scripts/b05_smoke.sh`
- [x] 新增 B05 交付文档：`docs/B05-WorkflowDAG执行引擎MVP说明.md`

## B06 验收清单（已完成）

- [x] 新增 `approval` 节点（人工审核决策路由）
- [x] 新增 `webhook` 节点（dry-run / 真实回调）
- [x] 新增模板 API（list + instantiate）
- [x] 交付首批模板流程（content approval / incident escalation）
- [x] 新增 B06 冒烟脚本：`knowledge-workflow-service/scripts/b06_smoke.sh` + `scripts/b06_smoke.sh`
- [x] 新增 B06 交付文档：`docs/B06-人工审核Webhook与模板流程首批说明.md`

## 当前目录结构（M1）

```text
src/main/java/com/bones/gateway/
  GatewayApplication.java
  common/
  config/
  controller/
  dto/
  entity/
  integration/
  repository/
  service/
src/main/resources/
  application.yml
  application-dev.yml
frontend/
  src/
  package.json
  vite.config.ts
```

## 本地依赖启动（dev）

在启动 `GatewayApplication` 前，先保证 MySQL 与 Redis 可连通：

```bash
# MySQL
/usr/local/mysql/bin/mysql --protocol=TCP -hlocalhost -P3306 -uroot -p -e "SHOW DATABASES LIKE 'jarvis';"

# Redis
brew services start redis
redis-cli -h 127.0.0.1 -p 6379 ping
```

预期结果：
- MySQL 能查到 `jarvis` 库。
- Redis 返回 `PONG`。

IDEA 运行 `GatewayApplication` 时，建议在 Run Configuration 中设置环境变量：
- `MYSQL_PASSWORD=你的MySQL密码`
- `SPRING_PROFILES_ACTIVE=dev`
- `JARVIS_JWT_SECRET=一段长度>=32的密钥`

开发态获取测试 token（dev/test）：

```bash
curl -X POST http://localhost:8080/api/v1/auth/dev-token \
  -H \"Content-Type: application/json\" \
  -d '{\"userId\":1001,\"role\":\"USER\"}'
```

前端本地启动（M8）：

```bash
cd frontend
npm install
npm run dev
```

打开：
- `http://localhost:5173`

M7 冒烟脚本：

```bash
./scripts/m7_smoke.sh
```

可选 AI 冒烟：

```bash
RUN_AI_SMOKE=true PROVIDER=glm MODEL=glm-4.6v-flashx ./scripts/m7_smoke.sh
```

M9 运营指标冒烟脚本：

```bash
./scripts/m9_smoke.sh
```

M10 容器化一键启动：

```bash
./scripts/m10_up.sh
```

M10 停止容器：

```bash
./scripts/m10_down.sh
```

M10 停止并清理数据卷：

```bash
./scripts/m10_down.sh --volumes
```

M11 生产启动（镜像部署）：

```bash
cp .env.prod.example .env.prod
./scripts/m11_prod_up.sh
```

M11 生产冒烟：

```bash
./scripts/m11_smoke.sh
```

M11 生产停止：

```bash
./scripts/m11_prod_down.sh
```

M11 镜像发布（本地构建）：

```bash
./scripts/m11_release.sh v1.0.0
```

M12 观测栈启动（Alertmanager + Prometheus + Grafana）：

```bash
./scripts/m12_obs_up.sh
```

M12 观测冒烟：

```bash
./scripts/m12_obs_smoke.sh
```

M12 观测下线（仅观测组件）：

```bash
./scripts/m12_obs_down.sh
```

M13 Agent 闭环冒烟：

```bash
./scripts/m13_smoke.sh
```

M15 知识检索闭环冒烟：

```bash
./scripts/m15_smoke.sh
```

M16 混合检索闭环冒烟：

```bash
./scripts/m16_smoke.sh
```

M17 检索调优与可解释冒烟：

```bash
./scripts/m17_smoke.sh
```

M18 检索策略请求级覆盖冒烟：

```bash
./scripts/m18_smoke.sh
```

M19 检索反馈闭环与自动调优冒烟：

```bash
./scripts/m19_smoke.sh
```

M20 检索策略治理与来源审计冒烟：

```bash
./scripts/m20_smoke.sh
```

## 更新规则

- 每次里程碑状态只使用：`pending` / `in_progress` / `completed`。
- 每个里程碑完成后补充“完成说明”与“验收命令结果”。
- 未完成项必须保留在待办中，不跳过不删除。

## 完成说明

### M1（完成时间：2026-02-27）

- 完成 Spring Boot 启动骨架替换：`GatewayApplication` 已建立。
- 完成基础包结构预置：`config/controller/service/entity/repository/dto/common/integration`。
- 完成环境配置模板：`application.yml`、`application-dev.yml`、`.env.example`。
- 完成健康检查基础能力：引入 `spring-boot-starter-actuator`。
- 完成可构建验证：`mvn test` 通过（测试环境使用 `test` profile + H2 内存库）。
- 交付说明文档：`docs/M1-工程骨架交付说明.md`。

### M2（完成时间：2026-02-28）

- 完成实体：`Conversation`、`Message`。
- 完成数据访问层：`ConversationRepository`、`MessageRepository`。
- 完成数据库迁移：`src/main/resources/db/migration/V1__init_conversation_message.sql`。
- 完成依赖与配置：引入 Flyway，关闭 Redis Repository 自动识别（保留 Redis 连接能力）。
- 启动验收通过：应用启动日志显示 Flyway 将 schema 升级到 `v1` 并成功启动。

### M3（完成时间：2026-02-28）

- 完成统一响应模型：`ApiResponse`（包含 `code/message/data/traceId/timestamp`）。
- 完成统一错误模型：`ErrorCode`、`BusinessException`。
- 完成全局异常兜底：`GlobalExceptionHandler`（参数校验异常、业务异常、通用异常）。
- 完成请求 traceId 注入：`TraceIdFilter`（支持透传 `X-Trace-Id`，自动回写响应头）。
- 完成示例接口：`/api/v1/demo` 下 `ping/echo/biz-error/validate`，用于验证网关基础能力链路。

### M4（完成时间：2026-02-28）

- 完成 AI 调用配置：`AiServiceProperties` + `WebClientConfig`。
- 完成 AI 客户端封装：`AiServiceClient` + `WebClientAiServiceClient`。
- 完成同步与流式网关接口：`/api/v1/ai/chat`、`/api/v1/ai/chat/stream`。
- 完成重试与异常映射：下游超时/连接异常/HTTP错误统一映射为业务错误码。
- 完成单元测试：`WebClientAiServiceClientTest`（2个用例通过）。
- 完成联调验证：通过本地 mock AI 服务验证同步与流式调用链可用。

### M4.5（完成时间：2026-02-28）

- 完成错误语义修正：AI 调用异常返回 502/503/504 级别状态。
- 完成重试策略收敛：默认关闭重试，避免 POST 重复提交风险。
- 完成 stream 规范化：提取 `data:` 有效内容并过滤终止标记。
- 完成可观测性补丁：增加 AI chat/stream 关键指标。
- 完成测试补丁：`GlobalExceptionHandlerTest`、`WebClientAiServiceClientTest` 新增用例。

### M4.6（完成时间：2026-02-28）

- 完成多 Provider 路由：请求可按 `provider`/`model` 指定目标模型厂商。
- 完成协议适配：支持 `OPENAI_COMPATIBLE` 与 `GEMINI` 协议。
- 完成 provider fallback：主 provider 失败时可切换备 provider。
- 完成配置扩展：新增 DeepSeek/Gemini/阿里云/腾讯云/GLM/MiniMax 配置模板。
- 完成测试扩展：新增 provider 路由、fallback、Gemini 解析测试用例。

### M5（完成时间：2026-02-28）

- 完成会话 API：`创建/分页列表/归档`。
- 完成消息 API：`追加消息/查询历史`。
- 完成聊天主流程：`USER 消息落库 -> 调用 AiServiceClient -> ASSISTANT 消息落库`。
- 完成会话状态管控：归档会话禁止继续聊天。
- 完成测试补充：`ConversationServiceTest`、`ChatServiceTest`。

### M5.5（完成时间：2026-02-28）

- 完成 JWT + RBAC：新增认证过滤器、安全配置、权限解析与开发态 token 签发接口。
- 完成配额与限流：按用户实现 Redis 分钟限流 + 日配额控制。
- 完成 provider/model 配额：新增按用户+provider+model 的日维度限制。
- 完成上下文拼装：基于消息历史构建多轮 prompt，并缓存到 Redis（窗口 + TTL）。
- 完成流式落库：新增会话流式接口，流式完成后自动持久化 assistant 消息。
- 完成流式恢复：新增 `streamId` 快照查询接口，支持断线后恢复读取进度。
- 完成体验指标：新增首 token 时延指标 `jarvis.chat.stream.first_token.latency`。
- 完成成本核算：新增 tokens 估算与用量计费统计（日维度 Redis 账本）。
- 完成会话体验增强：默认“新会话”在首轮对话后自动生成标题。
- 完成测试覆盖：新增 `AccessControlServiceTest`、`RequestGuardServiceTest`、`ConversationContextServiceTest`，并扩展 `ChatServiceTest` 流式场景。

### M6（完成时间：2026-02-28）

- 完成读缓存能力：会话列表与消息列表接入 Redis 读缓存。
- 完成缓存失效策略：会话创建/归档/更新时间变更、消息写入/更新后自动按前缀失效。
- 完成热点响应缓存：同步聊天增加短期热点缓存，降低重复请求成本与延迟。
- 完成可观测性补充：新增缓存 hit/miss/write/evict 指标计数。
- 完成配置扩展：新增 `jarvis.cache.*` 配置及环境变量模板。
- 完成交付文档：`docs/M6-Redis缓存优化交付说明.md`。

### M7（完成时间：2026-02-28）

- 完成测试补强：新增 `ReadCacheServiceTest`、`HotResponseCacheServiceTest`，扩展 `ChatServiceTest` 热点缓存命中场景。
- 完成可执行冒烟脚本：`scripts/m7_smoke.sh`（health/token/会话/消息/列表，支持可选 AI 冒烟）。
- 完成测试交付文档：`docs/M7-测试与交付说明.md`（测试矩阵、命令、IDEA 验收步骤）。
- 完成里程碑归档：README 与规划文档同步更新为 M7 `completed`。

### M8（完成时间：2026-02-28）

- 新建 `frontend` 工程：`Vite + React + TypeScript`。
- UI 采用 Ant Design，形成统一的联调控制台界面。
- 打通 `dev-token -> 会话创建 -> 同步聊天 -> 流式聊天` 完整前端调试链路。
- 支持 SSE 事件解析（`meta/delta/done`）与流式中断。
- 完成前端交付文档：`docs/M8-前端联调控制台交付说明.md`。

### M9（完成时间：2026-03-01）

- 完成组织级权限模型：`workspace`/`workspace_member` 与成员角色边界校验。
- 完成计费账本口径升级：支持 `actual/estimated` 双口径与 workspace 聚合。
- 完成运营指标接口：总览、provider 维度、TTFT 分位与 fallback 命中统计。
- 完成 M9 冒烟脚本：`scripts/m9_smoke.sh`。

### M10（完成时间：2026-03-01）

- 完成后端与前端容器化构建（多阶段构建 + 轻量运行镜像）。
- 完成本地联调编排：`mysql + redis + gateway + frontend` 一键拉起。
- 完成前端容器内反向代理：统一通过 `/api` 访问网关接口。
- 完成 CI 流水线：后端 `mvn test` + 前端 `npm run build`。
- 完成 M10 交付文档：`docs/M10-容器化与CI交付说明.md`。

### M11（完成时间：2026-03-01）

- 完成 `prod` 配置与部署编排：`application-prod.yml` + `docker-compose.prod.yml`。
- 完成生产环境参数模板：`.env.prod.example`。
- 完成生产启停与冒烟脚本：`scripts/m11_prod_up.sh`、`scripts/m11_prod_down.sh`、`scripts/m11_smoke.sh`。
- 完成镜像发布脚本：`scripts/m11_release.sh`。
- 完成 tag 自动发布工作流：`.github/workflows/release-images.yml`（推送至 GHCR）。
- 完成前端 Nginx 流式代理优化（`proxy_buffering off`）。
- 完成 M11 交付文档：`docs/M11-部署与发布交付说明.md`。

### M12（完成时间：2026-03-01）

- 完成 Prometheus 指标暴露链路（依赖 + 配置 + 安全放行）。
- 完成观测编排：`Alertmanager + Prometheus + Grafana`（`docker-compose.observability.yml`）。
- 完成 Prometheus 抓取与告警规则（可用性/错误率/时延/JVM 内存）。
- 完成 Grafana 数据源与看板预置。
- 完成观测运维脚本：`scripts/m12_obs_up.sh`、`scripts/m12_obs_down.sh`、`scripts/m12_obs_smoke.sh`。
- 完成 M12 交付文档：`docs/M12-可观测与运维加固交付说明.md`。

### M13（完成时间：2026-03-01）

- 完成 Agent 最小编排闭环（Plan/Act/Observe/Respond）。
- 完成最小工具集：时间查询、工作区运营指标摘要、会话历史摘要。
- 完成会话内 Agent API：`POST /api/v1/conversations/{id}/agent/run`。
- 完成 M13 冒烟脚本：`scripts/m13_smoke.sh`。
- 完成 M13 交付文档：`docs/M13-Agent编排最小闭环交付说明.md`。

### M14（完成时间：2026-03-02）

- 完成 Agent 规划模式增强：`rule / llm_json / function_calling`。
- 完成工具治理增强：`allowedTools`、工具执行策略、工具幂等键与策略中心。
- 完成 Function Calling 协议适配与步骤审计聚合。
- 完成 M14 交付文档：`docs/M14-Agent编排策略与工具治理首版说明.md`。

### M15（完成时间：2026-03-03）

- 完成知识片段最小数据层：`knowledge_snippet` 表与索引（Flyway `V3__knowledge_snippet_init.sql`）。
- 完成知识片段 API：`POST/PUT/DELETE/GET /api/v1/knowledge/snippets`。
- 完成知识检索服务：workspace 范围检索、关键词打分排序、结果裁剪。
- 完成 Agent 知识工具：`workspace_knowledge_search`，支持 query/limit 参数。
- 完成编排规则补充：用户问题命中知识关键词时自动规划知识检索工具。
- 完成 M15 冒烟脚本：`scripts/m15_smoke.sh`（已验证通过）。
- 完成 M15 交付文档：`docs/M15-Agent知识检索最小闭环交付说明.md`。

### M16（完成时间：2026-03-03）

- 完成检索模式增强：支持 `keyword/vector/hybrid` 三模式检索。
- 完成向量化首版：新增 `KnowledgeEmbeddingService`，对知识片段进行 embedding 存储与余弦相似度检索。
- 完成数据库演进：新增 `V4__knowledge_snippet_embedding.sql`（知识片段 embedding 字段）。
- 完成知识检索响应增强：返回 `searchMode`，便于前端与 Agent 审计。
- 完成 Agent 工具增强：`workspace_knowledge_search` 支持 `searchMode` 参数与 `knowledgeSearchMode` 元数据回退。
- 完成 M16 冒烟脚本：`scripts/m16_smoke.sh`（已验证通过）。
- 完成 M16 交付文档：`docs/M16-Agent混合检索增强交付说明.md`。

### M17（完成时间：2026-03-03）

- 完成知识检索参数配置化：新增 `KnowledgeSearchProperties`，支持权重、阈值、候选数量、默认模式等在线可调。
- 完成混合检索权重治理：`hybrid-keyword-weight` / `hybrid-vector-weight` 自动归一化，避免异常配置导致排序漂移。
- 完成检索返回可解释增强：接口返回 `keywordWeight` / `vectorWeight` / `scoreThreshold`，便于运营与排障。
- 完成配置模板补齐：`application-dev.yml`、`application-prod.yml`、`.env.example`、`.env.prod.example`。
- 完成 M17 冒烟脚本：`scripts/m17_smoke.sh`（已验证通过）。
- 完成 M17 交付文档：`docs/M17-Agent检索调优与可运营化说明.md`。

### M18（完成时间：2026-03-03）

- 完成检索 API 请求级策略覆盖：支持按请求传入权重/阈值/候选数，无需修改全局配置即可调参。
- 完成检索响应可审计增强：新增 `overrideApplied` 与 `maxCandidates`，可直接判断本次请求是否触发覆盖策略。
- 完成 Agent 知识工具策略透传：支持通过 tool args 与 `metadata.knowledgeSearchOverrides` 下发覆盖参数。
- 完成 Agent 工具输出增强：回显 `keywordWeight/vectorWeight/scoreThreshold/overrideApplied` 便于编排日志排障。
- 完成 M18 冒烟脚本：`scripts/m18_smoke.sh`（已验证通过）。
- 完成 M18 交付文档：`docs/M18-Agent检索策略请求级覆盖说明.md`。

### M19（完成时间：2026-03-03）

- 完成检索反馈数据面：支持记录 helpful 反馈、检索模式与策略参数样本。
- 完成策略推荐能力：按 workspace 聚合近 7 天反馈并输出建议 `keyword/vector` 权重、阈值与候选规模。
- 完成 autoTune 闭环：检索 API 与 Agent 工具可自动应用推荐策略（无显式 override 时生效）。
- 完成 M19 冒烟脚本：`scripts/m19_smoke.sh`（已验证通过）。
- 完成 M19 单测：`KnowledgeRetrievalPolicyServiceTest`。
- 完成 M19 交付文档：`docs/M19-Agent检索反馈闭环与自动调优说明.md`。

### M20（完成时间：2026-03-03）

- 完成 workspace 检索策略治理：新增 `RECOMMEND/MANUAL/OFF` 三种模式，支持 workspace 管理员持久化策略配置。
- 完成来源审计能力：检索响应新增 `overrideSource`，可区分请求覆盖、手动策略、自动推荐与未覆盖。
- 完成 autoTune 决策升级：支持优先读取 workspace 手动策略，其次反馈推荐策略，支持全局关闭（OFF）。
- 完成数据库迁移：`V5__knowledge_retrieval_policy.sql`。
- 完成 M20 冒烟脚本：`scripts/m20_smoke.sh`（已验证通过）。
- 完成 M20 单测：`KnowledgeRetrievalPolicyServiceTest`（新增策略模式相关用例）。
- 完成 M20 交付文档：`docs/M20-Agent检索策略治理与来源审计说明.md`。
