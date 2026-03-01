#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.prod"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  . "${ENV_FILE}"
  set +a
fi

GATEWAY_URL="${GATEWAY_URL:-http://localhost:${GATEWAY_PORT:-8080}}"
PROM_URL="${PROM_URL:-http://localhost:${PROMETHEUS_PORT:-19090}}"
ALERT_URL="${ALERT_URL:-http://localhost:${ALERTMANAGER_PORT:-19093}}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:${GRAFANA_PORT:-13000}}"

echo "[M12-OBS-SMOKE] GATEWAY_URL=${GATEWAY_URL}"
echo "[M12-OBS-SMOKE] PROM_URL=${PROM_URL}"
echo "[M12-OBS-SMOKE] ALERT_URL=${ALERT_URL}"
echo "[M12-OBS-SMOKE] GRAFANA_URL=${GRAFANA_URL}"

echo "[1/6] gateway health"
curl -fsS "${GATEWAY_URL}/actuator/health" >/dev/null

echo "[2/6] alertmanager ready"
curl -fsS "${ALERT_URL}/-/ready" >/dev/null

echo "[3/6] prometheus ready"
curl -fsS "${PROM_URL}/-/ready" >/dev/null

echo "[4/6] grafana health"
curl -fsS "${GRAFANA_URL}/api/health" >/dev/null

echo "[5/6] prometheus scrape target check"
UP_RESPONSE="$(curl -fsS "${PROM_URL}/api/v1/query?query=up%7Bjob%3D%22jarvis-gateway%22%7D")"
if [[ "${UP_RESPONSE}" == *'"result":[]'* ]]; then
  echo "[M12-OBS-SMOKE] missing gateway target in prometheus query result"
  exit 1
fi

UP_VALUE="$(echo "${UP_RESPONSE}" | sed -n 's/.*"value":\[[0-9.]*,"\([^"]*\)".*/\1/p' | head -n 1)"
if [[ "${UP_VALUE}" != "1" ]]; then
  TARGET_ERROR="$(curl -fsS "${PROM_URL}/api/v1/targets?state=active" | grep -o '"lastError":"[^"]*"' | head -n 1 | sed 's/"lastError":"\(.*\)"/\1/')"
  echo "[M12-OBS-SMOKE] gateway scrape is down (up=${UP_VALUE:-N/A})"
  if [[ -n "${TARGET_ERROR}" ]]; then
    echo "[M12-OBS-SMOKE] target lastError=${TARGET_ERROR}"
  fi
  exit 1
fi

echo "[M12-OBS-SMOKE] gateway target up=${UP_VALUE}"

echo "[6/6] prometheus alerting/rules check"
ALERTMANAGER_RESPONSE="$(curl -fsS "${PROM_URL}/api/v1/alertmanagers")"
if [[ "${ALERTMANAGER_RESPONSE}" != *'alertmanager:9093'* ]]; then
  echo "[M12-OBS-SMOKE] prometheus has no active alertmanager target"
  exit 1
fi

RULES_RESPONSE="$(curl -fsS "${PROM_URL}/api/v1/rules")"
if [[ "${RULES_RESPONSE}" != *'"name":"jarvis-gateway.rules"'* ]]; then
  echo "[M12-OBS-SMOKE] missing alert group: jarvis-gateway.rules"
  exit 1
fi

if [[ "${RULES_RESPONSE}" != *'"name":"JarvisGatewayDown"'* ]]; then
  echo "[M12-OBS-SMOKE] missing key alert rule: JarvisGatewayDown"
  exit 1
fi

echo "[M12-OBS-SMOKE] alertmanager connected and alert rules loaded"
echo "[M12-OBS-SMOKE] SUCCESS"
