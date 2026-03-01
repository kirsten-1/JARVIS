# M10 容器化与 CI 交付说明

## 1. 目标

M10 聚焦“可交付、可复现、可持续集成”三件事：

- 提供后端与前端标准镜像构建方式。
- 提供本地一键拉起联调环境（MySQL/Redis/Gateway/Frontend）。
- 提供基础 CI 流水线，保证每次变更有自动化质量门禁。

## 2. 交付清单

后端容器化：
- `Dockerfile`
- `.dockerignore`

前端容器化：
- `frontend/Dockerfile`
- `frontend/.dockerignore`
- `frontend/nginx/default.conf`

编排与脚本：
- `docker-compose.yml`
- `scripts/m10_up.sh`
- `scripts/m10_down.sh`

CI：
- `.github/workflows/ci.yml`

## 3. 架构与原理

- `mysql`：存储会话、消息、workspace 与计费相关数据。
- `redis`：承担限流、缓存、流式状态与部分运营统计临时数据。
- `gateway`：Spring Boot 网关服务，连接 MySQL 与 Redis，对外暴露 `8080`。
- `frontend`：React 构建产物由 Nginx 托管，对外暴露 `5173`。
- 前端通过 Nginx 反向代理访问网关（`/api`、`/actuator`、`/swagger-ui`、`/v3/api-docs`），避免浏览器跨域问题。

## 4. 快速开始

前置条件：
- 已安装 Docker Desktop（或 Docker Engine + Compose）。
- 本机 `8080`、`5173`、`3306`、`6379` 端口未被占用。

建议先准备 `.env`（可由 `.env.example` 复制）：

```bash
cp .env.example .env
```

最少需要确认：
- `MYSQL_PASSWORD`
- `JARVIS_JWT_SECRET`（建议长度 >= 32，生产环境不要用默认值）

一键启动：

```bash
./scripts/m10_up.sh
```

访问地址：
- 前端联调台：`http://localhost:5173`
- 网关 Swagger：`http://localhost:8080/swagger-ui.html`
- 网关健康检查：`http://localhost:8080/actuator/health`

停止环境：

```bash
./scripts/m10_down.sh
```

停止并清理数据卷：

```bash
./scripts/m10_down.sh --volumes
```

## 5. CI 说明

工作流文件：
- `.github/workflows/ci.yml`

触发时机：
- push 到 `main/master`
- 任意 pull request

流水线任务：
- `backend-test`：JDK 21 + `mvn -B test`
- `frontend-build`：Node 20 + `npm ci` + `npm run build`

## 6. 验收步骤

1. 执行 `./scripts/m10_up.sh`，确认输出 `gateway is healthy`。
2. 浏览器打开 `http://localhost:5173`，可进入前端联调页。
3. 执行 `./scripts/m9_smoke.sh`，确认核心链路与运营指标可查询。
4. 执行 `./scripts/m10_down.sh`，确认容器可正常回收。

## 7. 备注

- `docker-compose.yml` 中默认 `AI_LOCAL_BASE_URL=http://host.docker.internal:8000`，用于连接宿主机 AI mock 服务。
- 若在 Linux 环境运行，需确保 Docker 版本支持 `host-gateway`（compose 已配置 `extra_hosts`）。
