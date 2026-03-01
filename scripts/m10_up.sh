#!/usr/bin/env bash
set -euo pipefail

if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
else
  echo "[M10-UP] docker compose is required"
  exit 1
fi

echo "[M10-UP] starting mysql/redis/gateway/frontend ..."
"${DC[@]}" up -d --build

echo "[M10-UP] waiting for gateway health ..."
for i in {1..40}; do
  if curl -fsS "http://localhost:8080/actuator/health" >/dev/null 2>&1; then
    echo "[M10-UP] gateway is healthy"
    echo "[M10-UP] console: http://localhost:5173"
    echo "[M10-UP] swagger: http://localhost:8080/swagger-ui.html"
    exit 0
  fi
  sleep 2
done

echo "[M10-UP] gateway health check timeout"
exit 1
