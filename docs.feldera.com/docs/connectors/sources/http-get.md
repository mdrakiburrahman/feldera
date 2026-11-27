# HTTP GET (URL) input connector

Feldera can ingest data from a user-provided URL into a SQL table.

* The file is fetched using HTTP with the GET method.

The HTTP GET input connector supports [fault
tolerance](/pipelines/fault-tolerance).  Fault tolerance only makes sense for
URLs with static, unchanging data.

## HTTP GET input connector configuration

Use transport name `url_input`.

| Property        | Type    | Default | Description |
|-----------------|---------|---------|-------------|
| `path`\*        | string  |         | The URL to fetch, e.g. `https://example.com/tools-data.json`. |
| `pause_linger` | duration | `60s`   | How long a paused connector holds its connection to the server open, for example `60s`. The controller pauses the connector while the pipeline is paused, and whenever the connector reads faster than the pipeline processes. A connector paused for longer than this drops the connection, and reconnects when it starts running again. Replaces the deprecated `pause_timeout`. |

[*]: Required fields

## Example usage

We will create a pipeline with an HTTP GET connector.

The file is hosted at `https://example.com/tools-data.json`,
and is in [newline-delimited JSON (NDJSON) format](/formats/json#encoding-multiple-changes)
with one row per line. For example:

```text
{"insert": {"pid": 0, "name": "hammer", "price": 5}}
{"insert": {"pid": 1, "name": "nail", "price": 0.02}}
{"delete": {"pid": 0}}
```

### SQL example file

Create a file named **program.sql** with the following content:
```
CREATE TABLE price (
    pid BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR NOT NULL,
    price DECIMAL
)
WITH ('connectors' = '[{
    "transport": {
        "name": "url_input",
        "config": {"path": "https://example.com/tools-data.json"}
    },
    "format": {
        "name": "json",
        "config": {
            "update_format": "insert_delete",
            "array": false
        }
    }
}]');
```

### curl

```bash
curl -i -X PUT 'http://127.0.0.1:8080/v0/pipelines/workshop' \
-H 'Content-Type: application/json' \
-d "$(jq -Rsn \
  --rawfile code program.sql \
  '{
    name: "workshop",
    description: "Workshop inventory",
    runtime_config: {
      workers: 4
    },
    program_config: {},
    program_code: $code
  }')"
```

### Python (direct API calls)

```python
import requests

api_url = "http://127.0.0.1:8080"
headers = { "authorization": f"Bearer <API-KEY>" }
requests.put(
    f"{api_url}/v0/pipelines/workshop",
    headers=headers,
    json={
      "name": "workshop",
      "description": "Workshop inventory",
      "runtime_config": {
        "workers": 4
      },
      "program_config": {},
      "program_code": open("program.sql").read()
    }
).raise_for_status()
```

## OneLake Files (ABFSS)

With the Delta/Azure feature enabled (as in the standard builds), `url_input`
also accepts a OneLake **directory** URI:

```text
abfss://<workspace>@onelake.dfs.fabric.microsoft.com/<lakehouse>/Files/<directory>
```

`msit-onelake.dfs.fabric.microsoft.com` is also supported. HTTP(S) URLs retain
their existing single-file behavior. Using the existing `url_input` configuration
allows the OneLake handler to work with the Enterprise 0.391.0 control plane;
the generated pipeline must use a compiler image containing this handler.
The Azure client maps the DFS URI to the corresponding `*.blob.fabric.microsoft.com`
endpoint for Blob List/GET operations, preserving the `msit-onelake` account name.

The connector recursively lists files, takes a snapshot, and optionally polls for
new paths. It uses managed identity through IMDS. `IDENTITY_ENDPOINT` (including
an AKS IMDS sidecar) is supported, as are `AZURE_MSI_ENDPOINT` and
`AZURE_IDENTITY_ENDPOINT`, which take precedence. `IDENTITY_HEADER` is handled by
the Azure object-store client. No account keys, SAS tokens or Azure CLI fallback
are used.

Directory and file components beginning with `_` or `.`, and components ending
in `.tmp`, are always excluded. The connector only lists and reads: it never
moves, renames, archives, tags or deletes source files.

Options are URI query parameters; percent-encode their values:

| Parameter | Default | Meaning |
|---|---|---|
| `glob` | All eligible files | Case-sensitive glob relative to the requested directory. `*` matches one path component; `**` includes subdirectories. |
| `modified_since` | No time filter | Include files whose last-modified time is on or after this RFC 3339 timestamp. |
| `follow` | `false` | After the initial listing, keep polling for new file paths instead of ending the input. |
| `poll_interval_secs` | `10` | Follow polling interval, from 1 to 86400 seconds. |
| `client_id` | System-assigned identity | Optional user-assigned managed-identity client ID. |

For example, ingest finalized JSON files, using Feldera's existing JSON parser:

```sql
CREATE TABLE events (id BIGINT, message VARCHAR)
WITH ('connectors' = '[{
    "name": "files",
    "transport": {
        "name": "url_input",
        "config": {
            "path": "abfss://workspace@onelake.dfs.fabric.microsoft.com/lakehouse/Files/events?glob=**%2F*.json&follow=true"
        }
    },
    "format": {
        "name": "json",
        "config": {"update_format": "raw"}
    }
}]');
```

Use `array: true` for JSON arrays instead of individual/line-delimited records.
Each file ends the parser's input fragment, so the final record does not need
a trailing newline.

**Source contract:** publish complete, immutable files at unique final paths.
Files listed but not yet read must remain available with the same ETag for
checkpoint replay. An ETag change fails the read rather than silently mixing
versions. With `follow=true`, rewriting an already discovered path does not
ingest it again; additions at new paths do. Files removed before they can be read
or replayed cause an error. A directory listing is not a transactional snapshot
of concurrent writers.

Checkpoint metadata preserves the initial manifest and consumed byte offsets.
Recovery continues that manifest rather than including later additions in a
snapshot-only input; following discovers those additions normally. Metadata and
in-memory path tracking grow with the number of discovered files. File bodies
are read in bounded ranges, but a single parser record may be larger than a range.
The connector is assigned to one host, not sharded across hosts.

## Additional resources

For more information, see:

* [Tutorial section](/tutorials/basics/part3#step-1-configure-https-get-connectors) which involves
  creating an HTTP GET connector.

* Data formats such as [JSON](/formats/json),
  [CSV](/formats/csv), and [Parquet](/formats/parquet)
