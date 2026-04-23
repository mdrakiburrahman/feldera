#!/usr/bin/env bash
#
# demo.sh — Bring up / tear down a minimal Feldera pipeline-manager.
#
# Usage: ./demo.sh <up|down|logs>
#
# Environment variables:
#   GIT_ROOT       — Repository root (auto-detected via git rev-parse).
#   FELDERA_IMAGE  — Pipeline-manager image (default: images.feldera.com/feldera/pipeline-manager:latest).
#   FELDERA_PORT   — Host port for the API (default: 8080).
#   RUST_LOG       — Log level for pipeline-manager (default: info).
#
set -euo pipefail

GIT_ROOT="${GIT_ROOT:-$(git rev-parse --show-toplevel)}"
COMPOSE_FILE="${GIT_ROOT}/.research/demo/docker-compose.yml"
PROJECT_NAME="feldera-demo"

usage() {
    cat <<EOF
Usage: $0 <command>

Commands:
  up     Start pipeline-manager (force-recreate, clean state)
  down   Stop and destroy everything (volumes, orphans, immediate kill)
  logs   Tail pipeline-manager logs (last 200 lines, follow)
EOF
    exit 1
}

compose() {
    docker compose -f "${COMPOSE_FILE}" -p "${PROJECT_NAME}" "$@"
}

cmd_up() {
    local image="${FELDERA_IMAGE:-images.feldera.com/feldera/pipeline-manager:latest}"
    echo "Pulling ${image} (layer-by-layer progress)..."
    docker pull "${image}"
    echo "Starting pipeline-manager..."
    compose up -d \
        --force-recreate \
        --remove-orphans \
        --wait \
        --wait-timeout 300
    echo "Pipeline-manager is healthy at http://localhost:${FELDERA_PORT:-18080}"
}

cmd_down() {
    echo "Tearing down all containers, volumes, and orphans..."
    compose down \
        --volumes \
        --remove-orphans \
        --timeout 0
    echo "Done — all state destroyed."
}

cmd_logs() {
    compose logs --tail=200 --follow
}

COMMAND="${1:-}"
case "${COMMAND}" in
    up)   cmd_up   ;;
    down) cmd_down ;;
    logs) cmd_logs ;;
    *)    usage    ;;
esac
