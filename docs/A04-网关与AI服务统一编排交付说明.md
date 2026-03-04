# A04 网关与 AI 服务统一编排交付说明

## 目标

将 `gateway_pro` 与 `ai-service` 纳入同一套生产编排流程，做到：

- 一键拉起：Gateway + Frontend + MySQL + Redis +（可选）ai-service
- 一键验收：网关基础健康 + Agent 通过 `aiservice` 路由完成闭环
- 保持兼容：不破坏原有“Gateway 直连厂商 provider”的能力

## 交付内容

## 1. 生产编排新增可选 ai-service 服务

文件：

- `docker-compose.prod.yml`

新增服务：

- `aiservice`（`profiles: ["aiservice"]`）
- 端口：`${AISERVICE_PORT:-18000}:8000`
- 镜像：`${AISERVICE_IMAGE}`
- 支持 MiniMax 相关环境变量透传（`AIS_MINIMAX_*`）

说明：

- 默认不启用；开启 `AI_AISERVICE_COMPOSE_ENABLED=true` 后由脚本自动带 `--profile aiservice`。

## 2. 生产脚本增强

文件：

- `scripts/m11_prod_up.sh`
- `scripts/m11_prod_down.sh`
- `scripts/m11_smoke.sh`

增强点：

- `m11_prod_up.sh`
  - 支持 `AI_AISERVICE_COMPOSE_ENABLED=true` 时自动启用 `aiservice` profile。
  - 自动将 `AI_AISERVICE_BASE_URL` 默认纠正为 `http://aiservice:8000`（compose 内部地址）。
  - 复用 `AI_MINIMAX_*` 到 `AIS_MINIMAX_*`（当 A04 专用变量未显式设置时）。
  - 增加 ai-service 健康等待。
- `m11_prod_down.sh`
  - 支持在 A04 模式下按同样 profile 收敛下线。
- `m11_smoke.sh`
  - A04 模式下增加 ai-service 健康检查。

## 3. A04 专用冒烟脚本

文件：

- `scripts/a04_smoke.sh`

校验范围：

1. `m11_smoke.sh`（网关/前端/鉴权守卫）
2. ai-service 健康（A04 模式）
3. `m13_smoke.sh` 走 `provider=aiservice` 的 Agent 闭环

## 4. 生产环境模板扩展

文件：

- `.env.prod.example`

新增变量：

- `AI_AISERVICE_COMPOSE_ENABLED`
- `AISERVICE_IMAGE`
- `AISERVICE_PORT`
- `AIS_AI_DEFAULT_PROVIDER`
- `AIS_AI_FALLBACK_PROVIDER`
- `AIS_MINIMAX_*`

## 快速使用

## 1) 准备 `.env.prod`

最小推荐（A04 模式）：

```bash
AI_AISERVICE_COMPOSE_ENABLED=true
AI_DEFAULT_PROVIDER=aiservice
AI_AISERVICE_ENABLED=true
AI_AISERVICE_BASE_URL=http://aiservice:8000
AI_AISERVICE_CHAT_PATH=/api/v1/chat
AI_AISERVICE_STREAM_PATH=/api/v1/chat/stream

AISERVICE_IMAGE=ghcr.io/your-org/jarvis-ai-service:latest
AISERVICE_PORT=18000
AIS_MINIMAX_ENABLED=true
AIS_MINIMAX_API_KEY=your_key_here
AIS_MINIMAX_MODEL=MiniMax-M2.5
AIS_MINIMAX_API_KEY_HEADER=Authorization
AIS_MINIMAX_API_KEY_PREFIX=Bearer
```

## 2) 启动与验收

```bash
./scripts/m11_prod_up.sh
./scripts/a04_smoke.sh
```

预期：

- `m11` 冒烟通过
- `a04` 冒烟通过
- `m13` 在 `provider=aiservice` 下返回成功

## 回滚方案

若需回退到“Gateway 直连厂商”：

```bash
AI_AISERVICE_COMPOSE_ENABLED=false
AI_DEFAULT_PROVIDER=minimax   # 或 deepseek/glm 等直连 provider
AI_AISERVICE_ENABLED=false
```

然后重新执行：

```bash
./scripts/m11_prod_up.sh
./scripts/m13_smoke.sh
```
