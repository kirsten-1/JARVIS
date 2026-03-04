#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"
STAGE_NAME="${1:-}"

if [[ -z "${STAGE_NAME}" ]]; then
  echo "usage: ./scripts/a07_stage_node.sh <stage-name>"
  echo "example: ./scripts/a07_stage_node.sh A07-gateway-aiservice"
  exit 1
fi

REPORT_DIR="${ROOT_DIR}/docs/reports/stages"
mkdir -p "${REPORT_DIR}"

TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
STAGE_SLUG="$(printf '%s' "${STAGE_NAME}" | tr '[:space:]/' '--' | tr -cd '[:alnum:]_-.')"
REPORT_FILE="${REPORT_DIR}/a07_stage_${TIMESTAMP}_${STAGE_SLUG}.md"
COMMIT_FILE="${REPORT_DIR}/a07_stage_${TIMESTAMP}_${STAGE_SLUG}.commitmsg.txt"

git_branch="$(git -C "${ROOT_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")"
git_head="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo "N/A")"

find_latest_a06() {
  local latest
  latest="$(find "${ROOT_DIR}/docs/reports" -maxdepth 1 -type f -name 'a06_checkpoint_*.md' | sort | tail -n 1 || true)"
  if [[ -n "${latest}" ]]; then
    echo "${latest#${ROOT_DIR}/}"
  else
    echo "N/A"
  fi
}

read_env_value() {
  local key="$1"
  local default_value="${2:-}"
  if [[ ! -f "${ENV_FILE}" ]]; then
    echo "${default_value}"
    return 0
  fi
  local line
  line="$(grep -E "^${key}=" "${ENV_FILE}" | tail -n 1 || true)"
  if [[ -z "${line}" ]]; then
    echo "${default_value}"
  else
    echo "${line#*=}"
  fi
}

compose_ps_output="N/A"
if command -v docker >/dev/null 2>&1; then
  compose_ps_output="$(docker compose -f "${ROOT_DIR}/docker-compose.prod.yml" --env-file "${ENV_FILE}" ps 2>&1 || true)"
fi

latest_a06_report="$(find_latest_a06)"
gateway_image="$(read_env_value "GATEWAY_IMAGE" "N/A")"
frontend_image="$(read_env_value "FRONTEND_IMAGE" "N/A")"
aiservice_image="$(read_env_value "AISERVICE_IMAGE" "N/A")"
ai_default_provider="$(read_env_value "AI_DEFAULT_PROVIDER" "N/A")"
ai_aiservice_enabled="$(read_env_value "AI_AISERVICE_ENABLED" "N/A")"
ai_aiservice_compose_enabled="$(read_env_value "AI_AISERVICE_COMPOSE_ENABLED" "N/A")"
ai_aiservice_base_url="$(read_env_value "AI_AISERVICE_BASE_URL" "N/A")"

git_status_text="$(git -C "${ROOT_DIR}" status --short 2>/dev/null || true)"

cat > "${REPORT_FILE}" <<EOF
# A07 阶段节点报告

- 节点：${STAGE_NAME}
- 时间：$(date '+%Y-%m-%d %H:%M:%S %Z')
- 分支：${git_branch}
- 提交：${git_head}
- 最近 A06 报告：${latest_a06_report}

## 当前部署配置快照（脱敏）

- GATEWAY_IMAGE=${gateway_image}
- FRONTEND_IMAGE=${frontend_image}
- AISERVICE_IMAGE=${aiservice_image}
- AI_DEFAULT_PROVIDER=${ai_default_provider}
- AI_AISERVICE_ENABLED=${ai_aiservice_enabled}
- AI_AISERVICE_COMPOSE_ENABLED=${ai_aiservice_compose_enabled}
- AI_AISERVICE_BASE_URL=${ai_aiservice_base_url}

## 节点验收清单

- [ ] ./scripts/m11_smoke.sh
- [ ] ./scripts/a04_smoke.sh
- [ ] ./scripts/a06_checkpoint.sh
- [ ] 关键 API 手工复核（agent steps + assistant content + model）

## 回滚清单（不执行，仅记录）

### 1) 路由回滚到直连 provider（示例：minimax）

\`\`\`bash
sed -i '' 's#^AI_DEFAULT_PROVIDER=.*#AI_DEFAULT_PROVIDER=minimax#' .env.prod
sed -i '' 's#^AI_AISERVICE_ENABLED=.*#AI_AISERVICE_ENABLED=false#' .env.prod
sed -i '' 's#^AI_AISERVICE_COMPOSE_ENABLED=.*#AI_AISERVICE_COMPOSE_ENABLED=false#' .env.prod
./scripts/m11_prod_up.sh
./scripts/m11_smoke.sh
\`\`\`

### 2) 镜像回滚（示例）

\`\`\`bash
sed -i '' 's#^GATEWAY_IMAGE=.*#GATEWAY_IMAGE=<previous-gateway-tag>#' .env.prod
sed -i '' 's#^FRONTEND_IMAGE=.*#FRONTEND_IMAGE=<previous-frontend-tag>#' .env.prod
./scripts/m11_prod_up.sh
./scripts/m11_smoke.sh
\`\`\`

## Compose 状态快照

\`\`\`text
${compose_ps_output}
\`\`\`

## Git 工作区快照

\`\`\`text
${git_status_text}
\`\`\`
EOF

cat > "${COMMIT_FILE}" <<EOF
chore(stage): ${STAGE_NAME} checkpoint ${TIMESTAMP}

- add stage report: ${REPORT_FILE#${ROOT_DIR}/}
- latest a06: ${latest_a06_report}
- keep release deferred until full JARVIS closeout
- include rollback checklist for gateway/ai-service route and image fallback
EOF

echo "[A07] stage report: ${REPORT_FILE}"
echo "[A07] commit template: ${COMMIT_FILE}"
echo "[A07] suggested next:"
echo "  git add ${REPORT_FILE#${ROOT_DIR}/} ${COMMIT_FILE#${ROOT_DIR}/}"
echo "  git commit -F ${COMMIT_FILE#${ROOT_DIR}/}"
