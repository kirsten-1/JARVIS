#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M12-OBS-UP] docker compose is required"
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "[M12-OBS-UP] missing .env.prod"
  echo "[M12-OBS-UP] create one from template:"
  echo "cp .env.prod.example .env.prod"
  exit 1
fi

set -a
. "${ENV_FILE}"
set +a

PROM_PORT="${PROMETHEUS_PORT:-19090}"
ALERT_PORT="${ALERTMANAGER_PORT:-19093}"
GRAFANA_PORT_VALUE="${GRAFANA_PORT:-13000}"

echo "[M12-OBS-UP] starting gateway + alertmanager + prometheus + grafana ..."
"${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/docker-compose.observability.yml" \
  --env-file "${ENV_FILE}" \
  up -d gateway

# Force recreate observability components so updated configs are always loaded.
"${DC[@]}" \
  -f "${ROOT_DIR}/docker-compose.prod.yml" \
  -f "${ROOT_DIR}/docker-compose.observability.yml" \
  --env-file "${ENV_FILE}" \
  up -d --force-recreate alertmanager prometheus grafana

echo "[M12-OBS-UP] waiting services health ..."
for i in {1..60}; do
  if curl -fsS "http://localhost:${GATEWAY_PORT:-8080}/actuator/health" >/dev/null 2>&1 \
    && curl -fsS "http://localhost:${ALERT_PORT}/-/ready" >/dev/null 2>&1 \
    && curl -fsS "http://localhost:${PROM_PORT}/-/ready" >/dev/null 2>&1 \
    && curl -fsS "http://localhost:${GRAFANA_PORT_VALUE}/api/health" >/dev/null 2>&1; then
    echo "[M12-OBS-UP] ready"
    echo "[M12-OBS-UP] Alertmanager: http://localhost:${ALERT_PORT}"
    echo "[M12-OBS-UP] Prometheus: http://localhost:${PROM_PORT}"
    echo "[M12-OBS-UP] Grafana:    http://localhost:${GRAFANA_PORT_VALUE}"
    exit 0
  fi
  sleep 2
done

echo "[M12-OBS-UP] health check timeout"
exit 1
