# A09 AI 服务本地构建编排说明

## 目标

解决 A04 模式下对远端 `AISERVICE_IMAGE` 的强依赖，避免出现：

- `manifest unknown`
- 远端网络波动导致 `compose up` 失败

实现 `AI_AISERVICE_COMPOSE_ENABLED=true` 时，`m11_prod_up.sh` 可在本地自动补齐 ai-service 镜像。

## 交付内容

## 1) 启动脚本增强

文件：

- `scripts/m11_prod_up.sh`

新增能力：

- 当 A04 同栈模式开启时，先检查 `AISERVICE_IMAGE` 是否存在于本地。
- 若不存在且 `AISERVICE_AUTO_BUILD=true`，自动执行本地构建：
  - 构建上下文：`AISERVICE_BUILD_CONTEXT`
  - Dockerfile：`AISERVICE_DOCKERFILE`
  - 镜像名：`AISERVICE_IMAGE`
- 对构建路径和 Dockerfile 做存在性校验，失败时输出明确修复提示。

## 2) 编排默认值调整

文件：

- `docker-compose.prod.yml`

变更：

- `aiservice` 默认镜像改为 `jarvis-ai-service:local`，优先本地可用路径。

## 3) 模板变量扩展

文件：

- `.env.prod.example`

新增变量：

- `AISERVICE_AUTO_BUILD=true`
- `AISERVICE_BUILD_CONTEXT=../../ai-service`
- `AISERVICE_DOCKERFILE=Dockerfile`

## 推荐配置

```bash
AI_AISERVICE_COMPOSE_ENABLED=true
AI_DEFAULT_PROVIDER=aiservice
AI_AISERVICE_ENABLED=true
AI_AISERVICE_BASE_URL=http://aiservice:8000

AISERVICE_IMAGE=jarvis-ai-service:local
AISERVICE_AUTO_BUILD=true
AISERVICE_BUILD_CONTEXT=../../ai-service
AISERVICE_DOCKERFILE=Dockerfile
AISERVICE_PORT=18000
```

## 使用方式

```bash
./scripts/m11_prod_up.sh
./scripts/a04_smoke.sh
```

若首次本地无 ai-service 镜像，脚本会自动构建后再拉起。

## 回滚方式

如需继续使用远端镜像：

```bash
AISERVICE_IMAGE=ghcr.io/<your-org>/jarvis-ai-service:<tag>
AISERVICE_AUTO_BUILD=false
```

然后重新执行：

```bash
./scripts/m11_prod_up.sh
```
