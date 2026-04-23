# Feldera Demo — Minimal Pipeline Manager

A lightweight wrapper for running the Feldera pipeline-manager in Docker.

## Prerequisites

- Docker with Compose v2 (`docker compose` CLI plugin)
- Git (for automatic repository-root detection)

## Quick Start

```bash
GIT_ROOT="$(git rev-parse --show-toplevel)"

# Start pipeline-manager
"${GIT_ROOT}/.research/demo/demo.sh" up

# Tail logs
"${GIT_ROOT}/.research/demo/demo.sh" logs

# Tear down everything (volumes, orphans, immediate kill)
"${GIT_ROOT}/.research/demo/demo.sh" down
```

## Commands

| Command | Description                                                                                                          |
| ------- | -------------------------------------------------------------------------------------------------------------------- |
| `up`    | Start pipeline-manager with `--force-recreate --remove-orphans`. Waits for the healthcheck to pass before returning. |
| `down`  | Stop and destroy all containers, volumes, and orphans with `--timeout 0` (immediate kill).                           |
| `logs`  | Tail the last 200 lines of pipeline-manager logs and follow new output.                                              |

## Environment Variables

| Variable        | Default                                              | Description                                                       |
| --------------- | ---------------------------------------------------- | ----------------------------------------------------------------- |
| `GIT_ROOT`      | Auto-detected via `git rev-parse --show-toplevel`    | Repository root. Override only if running outside a git checkout. |
| `FELDERA_IMAGE` | `images.feldera.com/feldera/pipeline-manager:latest` | Pipeline-manager Docker image.                                    |
| `FELDERA_PORT`  | `18080`                                              | Host port for the Feldera API.                                    |
| `RUST_LOG`      | `info`                                               | Rust log level for the pipeline-manager process.                  |

## Example: Custom Image and Port

```bash
FELDERA_IMAGE=my-registry/pipeline-manager:dev \
FELDERA_PORT=9090 \
"${GIT_ROOT}/.research/demo/demo.sh" up
```

The API is then available at `http://localhost:9090`.

By default (no overrides), the API is at `http://localhost:18080`.

## Setup

1. Browse to `http://localhost:18080`
2. Take [medallion.sql](sql/medallion.sql), run it. It will clear the backlog fairly quickly by ingesting the data
3. Query -> insert to BRONZE -> Query:

   ```sql
   SELECT count(*) FROM gold_realtime_inventory_alerts;
   -- 234

   INSERT INTO bronze_suppliers VALUES (7, 'Blake and Sons', 'DE', 10000, now())

   SELECT count(*) FROM gold_realtime_inventory_alerts;
   -- 239
   ```