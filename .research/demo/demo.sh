#!/usr/bin/env bash
#
# demo.sh — Bring up / tear down a minimal Feldera pipeline-manager.
#
# Usage: ./demo.sh <up|down|logs|medallion-up|medallion-down>
#
# Environment variables:
#   GIT_ROOT       — Repository root (auto-detected via git rev-parse).
#   FELDERA_IMAGE  — Pipeline-manager image (default: images.feldera.com/feldera/pipeline-manager:latest).
#   FELDERA_PORT   — Host port for the API (default: 18080).
#   RUST_LOG       — Log level for pipeline-manager (default: info).
#
set -euo pipefail

GIT_ROOT="${GIT_ROOT:-$(git rev-parse --show-toplevel)}"
COMPOSE_FILE="${GIT_ROOT}/.research/demo/docker-compose.yml"
PROJECT_NAME="feldera-demo"
PIPELINE_NAME="medallion"
SQL_FILE="${GIT_ROOT}/.research/demo/sql/medallion.sql"
POLL_INTERVAL=5

# Resolve the Feldera API base URL. In DooD (Docker-outside-of-Docker)
# devcontainers, ports are on the Docker host, not localhost.
resolve_feldera_base() {
    local port="${FELDERA_PORT:-18080}"
    local candidates=("http://localhost:${port}" "http://host.docker.internal:${port}")
    for base in "${candidates[@]}"; do
        if curl -sf --connect-timeout 2 "${base}/healthz" >/dev/null 2>&1; then
            echo "${base}"
            return 0
        fi
    done
    # Fall back to localhost even if unreachable (caller will get a clear error).
    echo "http://localhost:${port}"
}

# Lazily resolved on first API call.
FELDERA_BASE=""
ensure_feldera_base() {
    if [[ -z "${FELDERA_BASE}" ]]; then
        FELDERA_BASE="$(resolve_feldera_base)"
    fi
}

usage() {
    cat <<EOF
Usage: $0 <command>

Commands:
  up              Start pipeline-manager (force-recreate, clean state)
  down            Stop and destroy everything (volumes, orphans, immediate kill)
  logs            Tail pipeline-manager logs (last 200 lines, follow)
  medallion-up    Deploy and start the medallion SQL pipeline
  medallion-down  Stop and delete the medallion pipeline
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
    echo "Pipeline-manager is healthy (port ${FELDERA_PORT:-18080})."
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

# ---------------------------------------------------------------------------
# Feldera REST API helpers
# ---------------------------------------------------------------------------

feldera_api() {
    # Usage: feldera_api <METHOD> <path> [curl-args...]
    ensure_feldera_base
    local method="$1" path="$2"
    shift 2
    curl --fail --silent --show-error \
        -X "${method}" \
        -H "Content-Type: application/json" \
        "${FELDERA_BASE}/v0${path}" "$@"
}

get_pipeline_field() {
    # Usage: get_pipeline_field <jq-expression>
    feldera_api GET "/pipelines/${PIPELINE_NAME}" | jq -r "$1"
}

wait_for_compilation() {
    echo "Waiting for compilation..."
    local status
    while true; do
        status=$(get_pipeline_field '.program_status')
        case "${status}" in
            Success)
                echo "Compilation succeeded."
                return 0
                ;;
            Pending|CompilingSql|SqlCompiled|CompilingRust)
                echo "  program_status: ${status} — polling in ${POLL_INTERVAL}s..."
                sleep "${POLL_INTERVAL}"
                ;;
            SqlError)
                echo "ERROR: SQL compilation failed:"
                get_pipeline_field '.program_error' | jq .
                return 1
                ;;
            *)
                echo "ERROR: Unexpected program_status: ${status}"
                get_pipeline_field '.program_error' | jq .
                return 1
                ;;
        esac
    done
}

wait_for_running() {
    echo "Waiting for pipeline to start..."
    local status error
    while true; do
        status=$(get_pipeline_field '.deployment_status')
        case "${status}" in
            Running)
                echo "Pipeline '${PIPELINE_NAME}' is running."
                return 0
                ;;
            Stopped)
                error=$(get_pipeline_field '.deployment_error // empty')
                if [[ -n "${error}" ]]; then
                    echo "ERROR: Pipeline stopped with error:"
                    echo "${error}" | jq .
                    return 1
                fi
                echo "  deployment_status: Stopped — polling in ${POLL_INTERVAL}s..."
                sleep "${POLL_INTERVAL}"
                ;;
            *)
                echo "  deployment_status: ${status} — polling in ${POLL_INTERVAL}s..."
                sleep "${POLL_INTERVAL}"
                ;;
        esac
    done
}

wait_for_stopped() {
    echo "Waiting for pipeline to stop..."
    local status
    while true; do
        status=$(get_pipeline_field '.deployment_status')
        if [[ "${status}" == "Stopped" ]]; then
            echo "Pipeline '${PIPELINE_NAME}' is stopped."
            return 0
        fi
        echo "  deployment_status: ${status} — polling in ${POLL_INTERVAL}s..."
        sleep "${POLL_INTERVAL}"
    done
}

wait_for_pipeline_complete() {
    echo "Waiting for pipeline to finish ingesting..."
    local complete status input_rec processed_rec elapsed
    local start_ts
    start_ts=$(date +%s)
    while true; do
        local stats
        stats=$(feldera_api GET "/pipelines/${PIPELINE_NAME}/stats" 2>/dev/null) || {
            echo "  WARNING: stats endpoint unreachable — retrying in ${POLL_INTERVAL}s..."
            sleep "${POLL_INTERVAL}"
            continue
        }

        complete=$(echo "${stats}" | jq -r '.global_metrics.pipeline_complete // false')
        status=$(echo "${stats}" | jq -r '.global_metrics.state // "Unknown"')
        input_rec=$(echo "${stats}" | jq -r '.global_metrics.total_input_records // 0')
        processed_rec=$(echo "${stats}" | jq -r '.global_metrics.total_processed_records // 0')
        elapsed=$(( $(date +%s) - start_ts ))

        if [[ "${status}" != "Running" ]]; then
            echo "ERROR: Pipeline is no longer running (state: ${status})."
            return 1
        fi

        if [[ "${complete}" == "true" ]]; then
            printf "Pipeline complete — %s records ingested and processed (%ds elapsed).\n" \
                "${processed_rec}" "${elapsed}"
            return 0
        fi

        printf "  pipeline_complete: false — %s/%s records (%.0fs) — polling in %ss...\n" \
            "${processed_rec}" "${input_rec}" "${elapsed}" "${POLL_INTERVAL}"
        sleep "${POLL_INTERVAL}"
    done
}

# ---------------------------------------------------------------------------
# medallion-up: deploy and start the medallion pipeline
# ---------------------------------------------------------------------------

cmd_medallion_up() {
    if [[ ! -f "${SQL_FILE}" ]]; then
        echo "ERROR: SQL file not found: ${SQL_FILE}"
        exit 1
    fi

    if ! command -v jq &>/dev/null; then
        echo "ERROR: jq is required but not installed."
        exit 1
    fi

    local sql
    sql=$(cat "${SQL_FILE}")

    echo "Deploying pipeline '${PIPELINE_NAME}' to ${FELDERA_BASE}..."

    # Create or replace the pipeline with the SQL program.
    local body
    body=$(jq -n \
        --arg name "${PIPELINE_NAME}" \
        --arg code "${sql}" \
        '{name: $name, program_code: $code, runtime_config: {workers: 4}}')

    feldera_api PUT "/pipelines/${PIPELINE_NAME}" -d "${body}" >/dev/null
    echo "Pipeline created/updated."

    wait_for_compilation

    echo "Starting pipeline '${PIPELINE_NAME}'..."
    feldera_api POST "/pipelines/${PIPELINE_NAME}/start" >/dev/null

    wait_for_running
    echo "Medallion pipeline is live at ${FELDERA_BASE}"
    wait_for_pipeline_complete
}

# ---------------------------------------------------------------------------
# medallion-down: stop and delete the medallion pipeline
# ---------------------------------------------------------------------------

cmd_medallion_down() {
    if ! command -v jq &>/dev/null; then
        echo "ERROR: jq is required but not installed."
        exit 1
    fi

    echo "Stopping pipeline '${PIPELINE_NAME}'..."
    # Force stop — community edition requires force=true.
    feldera_api POST "/pipelines/${PIPELINE_NAME}/stop?force=true" >/dev/null 2>&1 || true

    # Only wait/delete if the pipeline exists.
    if feldera_api GET "/pipelines/${PIPELINE_NAME}" &>/dev/null; then
        wait_for_stopped
        echo "Clearing pipeline storage..."
        feldera_api POST "/pipelines/${PIPELINE_NAME}/clear" >/dev/null 2>&1 || true
        # Wait for storage to clear before deleting.
        sleep 3
        echo "Deleting pipeline '${PIPELINE_NAME}'..."
        feldera_api DELETE "/pipelines/${PIPELINE_NAME}"
        echo "Pipeline '${PIPELINE_NAME}' deleted."
    else
        echo "Pipeline '${PIPELINE_NAME}' does not exist — nothing to delete."
    fi
}

COMMAND="${1:-}"
case "${COMMAND}" in
    up)             cmd_up             ;;
    down)           cmd_down           ;;
    logs)           cmd_logs           ;;
    medallion-up)   cmd_medallion_up   ;;
    medallion-down) cmd_medallion_down ;;
    *)              usage              ;;
esac
