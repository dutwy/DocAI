#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${DOCAI_IMAGE_REGISTRY:?请设置 DOCAI_IMAGE_REGISTRY，例如 registry.example.com/docai}"
DOCAI_IMAGE_TAG="${DOCAI_IMAGE_TAG:-1.0.0}"
PUSH_IMAGES="${PUSH_IMAGES:-false}"
JAVA_RUNTIME_IMAGE="${JAVA_RUNTIME_IMAGE:-eclipse-temurin:17-jre-jammy}"
export DOCAI_IMAGE_REGISTRY DOCAI_IMAGE_TAG

echo "[1/2] 运行完整构建与测试"
./mvnw clean verify

services=(user-service file-service gateway-service ai-service document-service)
echo "[2/2] 构建统一标签的服务镜像"
for service in "${services[@]}"; do
  image="${DOCAI_IMAGE_REGISTRY}/${service}:${DOCAI_IMAGE_TAG}"
  docker build \
    --build-arg "JAVA_RUNTIME_IMAGE=${JAVA_RUNTIME_IMAGE}" \
    --tag "$image" \
    "$service"
  if [[ "$PUSH_IMAGES" == "true" ]]; then
    docker push "$image"
  fi
done

echo "镜像构建完成：${DOCAI_IMAGE_REGISTRY}/*:${DOCAI_IMAGE_TAG}"
