# A07 阶段节点报告

- 节点：A07-gateway-aiservice
- 时间：2026-03-04 17:49:39 CST
- 分支：main
- 提交：8bfd9cd
- 最近 A06 报告：docs/reports/a06_checkpoint_20260304_174619.md

## 当前部署配置快照（脱敏）

- GATEWAY_IMAGE=jarvis-gateway-pro:local
- FRONTEND_IMAGE=ghcr.io/kirsten-1/jarvis-gateway-console:v0.0.6
- AISERVICE_IMAGE=N/A
- AI_DEFAULT_PROVIDER=aiservice
- AI_AISERVICE_ENABLED=true
- AI_AISERVICE_COMPOSE_ENABLED=N/A
- AI_AISERVICE_BASE_URL=http://host.docker.internal:8000

## 节点验收清单

- [ ] ./scripts/m11_smoke.sh
- [ ] ./scripts/a04_smoke.sh
- [ ] ./scripts/a06_checkpoint.sh
- [ ] 关键 API 手工复核（agent steps + assistant content + model）

## 回滚清单（不执行，仅记录）

### 1) 路由回滚到直连 provider（示例：minimax）

```bash
sed -i '' 's#^AI_DEFAULT_PROVIDER=.*#AI_DEFAULT_PROVIDER=minimax#' .env.prod
sed -i '' 's#^AI_AISERVICE_ENABLED=.*#AI_AISERVICE_ENABLED=false#' .env.prod
sed -i '' 's#^AI_AISERVICE_COMPOSE_ENABLED=.*#AI_AISERVICE_COMPOSE_ENABLED=false#' .env.prod
./scripts/m11_prod_up.sh
./scripts/m11_smoke.sh
```

### 2) 镜像回滚（示例）

```bash
sed -i '' 's#^GATEWAY_IMAGE=.*#GATEWAY_IMAGE=<previous-gateway-tag>#' .env.prod
sed -i '' 's#^FRONTEND_IMAGE=.*#FRONTEND_IMAGE=<previous-frontend-tag>#' .env.prod
./scripts/m11_prod_up.sh
./scripts/m11_smoke.sh
```

## Compose 状态快照

```text
permission denied while trying to connect to the Docker daemon socket at unix:///Users/apple/.docker/run/docker.sock: Get "http://%2FUsers%2Fapple%2F.docker%2Frun%2Fdocker.sock/v1.47/containers/json?filters=%7B%22label%22%3A%7B%22com.docker.compose.config-hash%22%3Atrue%2C%22com.docker.compose.oneoff%3DFalse%22%3Atrue%2C%22com.docker.compose.project%3Dgateway_pro%22%3Atrue%7D%7D": dial unix /Users/apple/.docker/run/docker.sock: connect: operation not permitted
```

## Git 工作区快照

```text
 M .env.example
 M .env.prod.example
 M README.md
 M docker-compose.prod.yml
 M "docs/\345\244\232\345\216\202\345\225\206AI\344\275\277\347\224\250\346\214\207\345\215\227.md"
 M scripts/m11_prod_down.sh
 M scripts/m11_prod_up.sh
 M scripts/m11_smoke.sh
 M src/main/java/com/bones/gateway/integration/ai/WebClientAiServiceClient.java
 M src/main/resources/application-dev.yml
 M src/main/resources/application-prod.yml
 M src/test/java/com/bones/gateway/integration/ai/WebClientAiServiceClientTest.java
?? "docs/A04-\347\275\221\345\205\263\344\270\216AI\346\234\215\345\212\241\347\273\237\344\270\200\347\274\226\346\216\222\344\272\244\344\273\230\350\257\264\346\230\216.md"
?? "docs/A05-AI\346\234\215\345\212\241\351\225\234\345\203\217\345\217\221\345\270\203\350\204\232\346\234\254\344\272\244\344\273\230\350\257\264\346\230\216.md"
?? "docs/A06-\351\230\266\346\256\265\351\252\214\346\224\266\344\270\216\346\243\200\346\237\245\347\202\271\346\212\245\345\221\212\350\257\264\346\230\216.md"
?? "docs/A07-\351\230\266\346\256\265\347\211\210\346\234\254\350\212\202\347\202\271\344\270\216\345\233\236\346\273\232\346\270\205\345\215\225\350\257\264\346\230\216.md"
?? docs/reports/
?? scripts/a04_smoke.sh
?? scripts/a05_release_aiservice.sh
?? scripts/a06_checkpoint.sh
?? scripts/a07_stage_node.sh
?? test.py
```
