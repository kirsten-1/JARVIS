# A05 AI 服务镜像发布脚本交付说明

## 目标

为 `ai-service` 提供可复用的镜像发布脚本，统一本地发布动作，减少手工构建/打 tag/推送出错。

## 交付内容

- 新增脚本：`scripts/a05_release_aiservice.sh`

能力：

- 按 tag 构建 `ai-service` 镜像
- 可选自动打 `latest`
- 可选推送到 GHCR（或自定义 registry）
- 支持自定义 ai-service 项目路径与 Dockerfile 路径

## 使用方式

## 1) 仅构建

```bash
./scripts/a05_release_aiservice.sh v0.0.1
```

## 2) 构建并推送 GHCR

```bash
IMAGE_NAMESPACE=<your_github_user_or_org> \
PUSH_IMAGES=true \
./scripts/a05_release_aiservice.sh v0.0.1
```

## 3) 指定 ai-service 目录（默认是 `../../ai-service`）

```bash
AISERVICE_CONTEXT_DIR=/abs/path/to/ai-service \
AISERVICE_DOCKERFILE=/abs/path/to/ai-service/Dockerfile \
./scripts/a05_release_aiservice.sh v0.0.1
```

## 环境变量

- `IMAGE_REGISTRY`：默认 `ghcr.io`
- `IMAGE_NAMESPACE`：默认 `your-org`
- `AISERVICE_IMAGE_REPO`：默认 `jarvis-ai-service`
- `PUSH_IMAGES`：默认 `false`
- `TAG_LATEST`：默认 `true`
- `AISERVICE_CONTEXT_DIR`：默认 `${ROOT_DIR}/../../ai-service`
- `AISERVICE_DOCKERFILE`：默认 `${AISERVICE_CONTEXT_DIR}/Dockerfile`

## 注意事项

- 本仓库当前仅包含 `gateway_pro`，`ai-service` 为同级目录项目，CI 工作流无法直接访问同级目录。
- 因此 A05 先交付“本地可执行发布脚本”；若后续把 `ai-service` 迁入同一 Git 仓库，可再补充 CI 自动发布工作流。
