#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-}"

if [[ -z "${TAG}" ]]; then
  echo "usage: ./scripts/m11_release.sh <tag>"
  echo "example: ./scripts/m11_release.sh v1.0.0"
  exit 1
fi

REGISTRY="${IMAGE_REGISTRY:-ghcr.io}"
NAMESPACE="${IMAGE_NAMESPACE:-your-org}"
BACKEND_REPO="${GATEWAY_IMAGE_REPO:-jarvis-gateway-pro}"
FRONTEND_REPO="${FRONTEND_IMAGE_REPO:-jarvis-gateway-console}"
PUSH_IMAGES="${PUSH_IMAGES:-false}"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-21}"
RUNTIME_IMAGE="${RUNTIME_IMAGE:-eclipse-temurin:21-jre-jammy}"
NODE_IMAGE="${NODE_IMAGE:-node:20-alpine}"
NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.27-alpine}"

BACKEND_IMAGE="${REGISTRY}/${NAMESPACE}/${BACKEND_REPO}:${TAG}"
FRONTEND_IMAGE="${REGISTRY}/${NAMESPACE}/${FRONTEND_REPO}:${TAG}"

echo "[M11-RELEASE] building backend image: ${BACKEND_IMAGE}"
docker build \
  --build-arg MAVEN_IMAGE="${MAVEN_IMAGE}" \
  --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE}" \
  -t "${BACKEND_IMAGE}" \
  -f "${ROOT_DIR}/Dockerfile" \
  "${ROOT_DIR}"

echo "[M11-RELEASE] building frontend image: ${FRONTEND_IMAGE}"
docker build \
  --build-arg NODE_IMAGE="${NODE_IMAGE}" \
  --build-arg NGINX_IMAGE="${NGINX_IMAGE}" \
  -t "${FRONTEND_IMAGE}" \
  -f "${ROOT_DIR}/frontend/Dockerfile" \
  "${ROOT_DIR}/frontend"

if [[ "${PUSH_IMAGES}" == "true" ]]; then
  echo "[M11-RELEASE] pushing images ..."
  docker push "${BACKEND_IMAGE}"
  docker push "${FRONTEND_IMAGE}"
else
  echo "[M11-RELEASE] skip push (set PUSH_IMAGES=true to push)"
fi

echo "[M11-RELEASE] done"
echo "[M11-RELEASE] backend:  ${BACKEND_IMAGE}"
echo "[M11-RELEASE] frontend: ${FRONTEND_IMAGE}"
