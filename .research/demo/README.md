# Feldera Demo — Minimal Pipeline Manager

A lightweight wrapper for running the Feldera pipeline-manager in Docker.

## Prerequisites

- Docker with Compose v2 (`docker compose` CLI plugin)
- Git (for automatic repository-root detection)
- `jq` (for `medallion-up` / `medallion-down` commands)

## Quick Start

```bash
GIT_ROOT="$(git rev-parse --show-toplevel)"

# Start pipeline-manager
"${GIT_ROOT}/.research/demo/demo.sh" up

# Deploy and start the medallion pipeline
"${GIT_ROOT}/.research/demo/demo.sh" medallion-up
```

## Commands

| Command          | Description                                                                                                          |
| ---------------- | -------------------------------------------------------------------------------------------------------------------- |
| `up`             | Start pipeline-manager with `--force-recreate --remove-orphans`. Waits for the healthcheck to pass before returning. |
| `down`           | Stop and destroy all containers, volumes, and orphans with `--timeout 0` (immediate kill).                           |
| `logs`           | Tail the last 200 lines of pipeline-manager logs and follow new output.                                              |
| `medallion-up`   | Deploy `sql/medallion.sql` to Feldera, compile, start, and poll until all data is ingested (`pipeline_complete`).    |
| `medallion-down` | Stop and delete the medallion pipeline from Feldera.                                                                 |

## Delta Lake Output

The six gold-layer materialized views write Delta Lake tables to `/var/feldera/delta/`
inside the pipeline-manager container. The data is stored on a named Docker volume
(`feldera-demo_delta-output`).

Each table includes two metadata columns added by Feldera:
- `__feldera_op` — Operation type: `i` (insert), `d` (delete), `u` (update)
- `__feldera_ts` — Logical timestamp for ordering updates

**Inspect from the host:**

```bash
# List Delta tables
docker exec feldera-demo-pipeline-manager-1 ls /var/feldera/delta/

# Check a specific table's Delta log
docker exec feldera-demo-pipeline-manager-1 ls /var/feldera/delta/gold_order_status_summary/_delta_log/
```

Logs:

```bash
docker logs -f feldera-demo-pipeline-manager-1
```

## DuckDB UI

A DuckDB container starts alongside the pipeline-manager and mounts the Delta Lake
volume read-only at `/delta/`. It exposes a web UI for interactive SQL analysis.

**Access the UI:** <http://localhost:4213> (or your custom `DUCKDB_PORT`).

The Delta Lake extension is pre-installed on startup. Open the UI, then paste
(or run block-by-block) the reference queries from
[sql/duckdb-read-medallion.sql](sql/duckdb-read-medallion.sql).

> **Note:** The DuckDB UI extension binds to `localhost` inside its container.
> A `socat` forwarder bridges external access — this is why a custom
> [Dockerfile.duckdb](Dockerfile.duckdb) is used instead of the stock image.

## Environment Variables

| Variable        | Default                                              | Description                                                       |
| --------------- | ---------------------------------------------------- | ----------------------------------------------------------------- |
| `GIT_ROOT`      | Auto-detected via `git rev-parse --show-toplevel`    | Repository root. Override only if running outside a git checkout. |
| `FELDERA_IMAGE` | `images.feldera.com/feldera/pipeline-manager:latest` | Pipeline-manager Docker image.                                    |
| `FELDERA_PORT`  | `18080`                                              | Host port for the Feldera API.                                    |
| `DUCKDB_PORT`   | `4213`                                               | Host port for the DuckDB web UI.                                  |
| `RUST_LOG`      | `info`                                               | Rust log level for the pipeline-manager process.                  |

> **DooD note:** In Docker-outside-of-Docker devcontainers, the script
> auto-detects whether the API is reachable at `localhost` or
> `host.docker.internal` and uses whichever responds.

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