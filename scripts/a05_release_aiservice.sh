#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${1:-}"

if [[ -z "${TAG}" ]]; then
  echo "usage: ./scripts/a05_release_aiservice.sh <tag>"
  echo "example: ./scripts/a05_release_aiservice.sh v0.1.0"
  exit 1
fi

REGISTRY="${IMAGE_REGISTRY:-ghcr.io}"
NAMESPACE="${IMAGE_NAMESPACE:-your-org}"
AISERVICE_REPO="${AISERVICE_IMAGE_REPO:-jarvis-ai-service}"
PUSH_IMAGES="${PUSH_IMAGES:-false}"
TAG_LATEST="${TAG_LATEST:-true}"
AISERVICE_CONTEXT_DIR="${AISERVICE_CONTEXT_DIR:-${ROOT_DIR}/../../ai-service}"
AISERVICE_DOCKERFILE="${AISERVICE_DOCKERFILE:-${AISERVICE_CONTEXT_DIR}/Dockerfile}"

if [[ ! -d "${AISERVICE_CONTEXT_DIR}" ]]; then
  echo "[A05-RELEASE] ai-service context directory not found: ${AISERVICE_CONTEXT_DIR}"
  echo "[A05-RELEASE] set AISERVICE_CONTEXT_DIR to your ai-service path"
  exit 1
fi

if [[ ! -f "${AISERVICE_DOCKERFILE}" ]]; then
  echo "[A05-RELEASE] Dockerfile not found: ${AISERVICE_DOCKERFILE}"
  echo "[A05-RELEASE] set AISERVICE_DOCKERFILE or check ai-service project"
  exit 1
fi

AISERVICE_IMAGE="${REGISTRY}/${NAMESPACE}/${AISERVICE_REPO}:${TAG}"

echo "[A05-RELEASE] building ai-service image: ${AISERVICE_IMAGE}"
docker build \
  -t "${AISERVICE_IMAGE}" \
  -f "${AISERVICE_DOCKERFILE}" \
  "${AISERVICE_CONTEXT_DIR}"

if [[ "${TAG_LATEST}" == "true" ]]; then
  AISERVICE_IMAGE_LATEST="${REGISTRY}/${NAMESPACE}/${AISERVICE_REPO}:latest"
  echo "[A05-RELEASE] tagging latest: ${AISERVICE_IMAGE_LATEST}"
  docker tag "${AISERVICE_IMAGE}" "${AISERVICE_IMAGE_LATEST}"
fi

if [[ "${PUSH_IMAGES}" == "true" ]]; then
  echo "[A05-RELEASE] pushing images ..."
  docker push "${AISERVICE_IMAGE}"
  if [[ "${TAG_LATEST}" == "true" ]]; then
    docker push "${AISERVICE_IMAGE_LATEST}"
  fi
else
  echo "[A05-RELEASE] skip push (set PUSH_IMAGES=true to push)"
fi

echo "[A05-RELEASE] done"
echo "[A05-RELEASE] ai-service: ${AISERVICE_IMAGE}"
if [[ "${TAG_LATEST}" == "true" ]]; then
  echo "[A05-RELEASE] ai-service latest: ${AISERVICE_IMAGE_LATEST}"
fi
