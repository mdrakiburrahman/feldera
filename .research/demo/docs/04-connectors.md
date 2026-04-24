# 04 — Delta Lake Connector Internals

> **Audience**: You have run the e-commerce CDC demo and watched rows flow from
> Azure-hosted Delta tables into local Gold-tier Delta outputs. This document
> explains *how* Feldera's connector layer makes that happen.

---

## 1. Transport + Format Decoupling

Feldera separates **how** bytes move (transport) from **how** bytes are parsed
(format). Every connector is one of two kinds:

| Kind | Trait | Format handled by… |
|------|-------|--------------------|
| **Transport** connector | `TransportInputEndpoint` | A separate `Parser` / `InputFormat` |
| **Integrated** connector | `IntegratedInputEndpoint` | The connector itself |

Delta Lake is an **integrated** connector — it reads Parquet natively and pushes
structured records straight into the pipeline, bypassing the generic parser
layer.

```
 ┌─────────────────────────────────────────────────────────┐
 │                    Controller                           │
 │                                                         │
 │  ┌──────────────┐        ┌──────────────┐               │
 │  │ InputEndpoint │        │ InputEndpoint │              │
 │  │  (Transport)  │        │ (Integrated)  │              │
 │  └──────┬───────┘        └──────┬───────┘               │
 │         │                       │                       │
 │         ▼                       │                       │
 │  ┌─────────────┐                │  (no parser needed)   │
 │  │ InputFormat  │                │                       │
 │  │  + Parser    │                │                       │
 │  └──────┬──────┘                │                       │
 │         │                       │                       │
 │         ▼                       ▼                       │
 │  ┌──────────────────────────────────────┐               │
 │  │           InputHandle (Z-set)        │               │
 │  └──────────────────────────────────────┘               │
 └─────────────────────────────────────────────────────────┘

 Transport connectors (Kafka, HTTP, …) need a Format (JSON, CSV, …).
 Integrated connectors (Delta Lake, Iceberg, …) own the full path.
```

### Key traits (crates/adapterlib/src/transport.rs:36-84)

```rust
// Common base — every input endpoint reports its fault-tolerance model.
pub trait InputEndpoint: Send {
    fn fault_tolerance(&self) -> Option<FtModel>;
}

// Transport variant: the Controller supplies a Parser.
pub trait TransportInputEndpoint: InputEndpoint {
    fn open(
        &self, consumer, parser, schema, resume_info,
    ) -> AnyResult<Box<dyn InputReader>>;
}

// Integrated variant: receives an InputHandle directly.
pub trait IntegratedInputEndpoint: InputEndpoint {
    fn open(
        self: Box<Self>, input_handle, resume_info,
    ) -> AnyResult<Box<dyn InputReader>>;
}
```

### Format side (crates/adapterlib/src/format.rs:27-138)

```rust
pub trait InputFormat: Send + Sync {
    fn name(&self) -> Cow<'static, str>;
    fn new_parser(...) -> Result<Box<dyn Parser>, ControllerError>;
}

pub trait InputBuffer: Any + Send {
    fn flush(&mut self);   // Push buffered records into the Z-set.
    fn len(&self) -> BufferSize;
}
```

Delta Lake never touches `InputFormat` — it implements `IntegratedInputEndpoint`
and speaks `InputHandle` directly.

---

## 2. Delta Lake Input Pipeline

### 2.1 High-level data flow

```
  Azure Blob / S3 / Local FS
  ┌──────────────────────┐
  │     Delta Log        │  (JSON transaction log: _delta_log/)
  │  ┌────────┐          │
  │  │ v0.json│─────┐    │
  │  │ v1.json│──┐  │    │
  │  │ v2.json│  │  │    │
  │  └────────┘  │  │    │
  │              ▼  ▼    │
  │  ┌────────────────┐  │
  │  │ Parquet files   │  │  (columnar data)
  │  │  part-000.snappy│  │
  │  │  part-001.snappy│  │
  │  └────────────────┘  │
  └──────────┬───────────┘
             │
             ▼
  ┌──────────────────────────────────────────┐
  │  DeltaTableInputReader                   │
  │                                          │
  │  1. Snapshot read  (mode: "snapshot")     │
  │     Read ALL Parquet files at version V   │
  │     Emit every row as weight +1           │
  │                                          │
  │  2. Follow loop    (mode: "follow")       │
  │     Poll _delta_log/ for new versions     │
  │     AddFile  actions → weight +1          │
  │     RemoveFile actions → weight -1        │
  │                                          │
  └──────────────┬───────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │         InputHandle (Z-set deltas)       │
  │  Each record carries a weight:           │
  │    +1 = insert    -1 = delete            │
  └──────────────────────────────────────────┘
                 │
                 ▼
          Feldera pipeline
```

### 2.2 DeltaTableInputEndpoint

Defined at `crates/adapters/src/integrated/delta_table/input.rs`, line 103:

```rust
pub struct DeltaTableInputEndpoint {
    endpoint_name: String,
    config: DeltaTableInputConfig,
    consumer: Box<dyn InputConsumer>,
}
```

Construction (line 110) registers any required storage handlers (Azure, S3)
before the Delta library tries to open URIs.

The endpoint implements `IntegratedInputEndpoint`; its `open()` (line 131)
creates a `DeltaTableInputReader`, which owns the background I/O loop:

```rust
impl IntegratedInputEndpoint for DeltaTableInputEndpoint {
    fn open(
        self: Box<Self>,
        input_handle: &InputHandle,
        resume_info: ...,
    ) -> AnyResult<Box<dyn InputReader>> {
        Ok(Box::new(DeltaTableInputReader::new(self, input_handle, resume_info)?))
    }
}
```

Fault tolerance is `FtModel::AtLeastOnce` (line 127) — the connector guarantees
every record is delivered at least once after a restart.

### 2.3 Resume semantics

The reader persists a `DeltaResumeInfo` struct so it can pick up where it left
off:

```rust
DeltaResumeInfo {
    version: Some(v),
    snapshot_timestamp: Some(ts),
}
// → Resuming mid-snapshot: re-read version v, skip files already consumed
//   before timestamp ts.

DeltaResumeInfo {
    version: Some(v),
    snapshot_timestamp: None,
}
// → Snapshot complete; resume follow-mode from version v + 1.

DeltaResumeInfo {
    version: None,
    snapshot_timestamp: None,
}
// → Clean state — start from scratch.
```

### 2.4 Snapshot read (input.rs:787-850)

During a snapshot read the reader:

1. Opens the Delta table at the requested version (or latest).
2. Enumerates every Parquet data file belonging to that version.
3. Reads each file into Arrow `RecordBatch`es.
4. Pushes every row into the `InputHandle` with weight **+1** (insert).

The entire snapshot is treated as a single Feldera transaction — downstream SQL
views see an atomic jump from empty to fully populated.

### 2.5 Follow loop (input.rs:1130-1295)

After the snapshot (or when `mode: "follow"` is configured) the reader enters a
polling loop:

```
  while running {
      new_version = poll_delta_log(current_version + 1)
      for action in new_version.actions() {
          match action {
              AddFile(path)    => read parquet(path) → emit rows with weight +1
              RemoveFile(path) => read parquet(path) → emit rows with weight -1
          }
      }
      current_version = new_version
      sleep(poll_interval)
  }
```

Each Delta version becomes one Feldera step.  `AddFile` actions produce
insertions; `RemoveFile` actions produce deletions — the Z-set algebra handles
the rest.

### 2.6 Demo configuration (snapshot from Azure)

The e-commerce CDC demo reads Bronze tables in **snapshot** mode from a public
Azure storage account:

```jsonc
{
    "transport": {
        "name": "delta_table_input",
        "config": {
            "uri": "abfss://public@rakirahman.dfs.core.windows.net/feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_orders",
            "mode": "snapshot",
            "azure_skip_signature": "true"   // public container, no auth needed
        }
    },
    "transaction_mode": "snapshot"           // entire table = one Feldera step
}
```

What happens at runtime:

1. `DeltaTableInputEndpoint` registers the Azure storage handler.
2. `open()` creates `DeltaTableInputReader` with empty resume info (clean
   state).
3. The reader opens the Delta log at the `abfss://` URI, discovers the latest
   version, and lists all Parquet part files.
4. Each part file is streamed, decoded from Parquet into Arrow batches, and fed
   into the `InputHandle` as +1 insertions.
5. Because `mode` is `"snapshot"`, the reader stops after consuming the full
   table — it does **not** enter the follow loop.

---

## 3. Delta Lake Output Pipeline

### 3.1 High-level data flow

```
          Feldera pipeline
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │       OutputHandle (Z-set deltas)        │
  │  Each record carries:                    │
  │    weight +1 = insert                    │
  │    weight -1 = delete                    │
  │    (update = delete old + insert new)    │
  └──────────────┬───────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │       DeltaTableWriter                   │
  │                                          │
  │  1. Map Z-set records → Arrow batches    │
  │  2. Append metadata columns:             │
  │       __feldera_op  ("i"/"d"/"u")        │
  │       __feldera_ts  (step timestamp)     │
  │  3. Encode batches as Parquet            │
  │  4. Commit to Delta log                  │
  └──────────────┬───────────────────────────┘
                 │
                 ▼
  ┌──────────────────────────────────────────┐
  │  Delta Table on disk / object store      │
  │                                          │
  │  _delta_log/                             │
  │    00000000000000000000.json              │
  │    00000000000000000001.json  ← new      │
  │  part-00000-….snappy.parquet ← new       │
  │                                          │
  └──────────────────────────────────────────┘
                 │
                 ▼
        DuckDB sidecar reads via
        delta_scan('/delta/gold_*')
```

### 3.2 Schema setup (output.rs:88-190)

When the writer opens, it:

1. Receives the SQL-level schema from the Feldera catalog.
2. Converts each SQL column to an Arrow field.
3. Appends two **metadata columns** (output.rs:769-787):

```rust
arrow_fields.push(ArrowField::new(
    "__feldera_op",
    ArrowDataType::Utf8,
    true,
));
arrow_fields.push(ArrowField::new(
    "__feldera_ts",
    ArrowDataType::Int64,
    true,
));
```

The metadata struct logically looks like:

```rust
struct Meta {
    __feldera_op: &str,  // "i" = insert, "d" = delete, "u" = update
    __feldera_ts: i64,   // Feldera pipeline step number
}
```

These columns let downstream consumers (DuckDB, Spark, etc.) distinguish
inserts from deletes without relying on Delta's own change-data-feed.

### 3.3 Encoding and writing (output.rs:532-697)

For each Feldera step the writer:

1. Collects the output Z-set records.
2. Converts them to Arrow `RecordBatch`es, tagging each row with the
   appropriate `__feldera_op` and `__feldera_ts`.
3. Writes the batches as Parquet files into the Delta table's data directory.
4. Appends a new commit (JSON action log entry) to `_delta_log/`.

In **truncate** mode the writer replaces the entire table contents on each step
— previous data files are logically removed before the new ones are added.

### 3.4 Demo configuration (Gold views → local Delta)

The demo's Gold-tier views write to local Delta tables:

```jsonc
{
    "transport": {
        "name": "delta_table_output",
        "config": {
            "uri": "file:///var/feldera/delta/gold_customer_lifetime_value",
            "mode": "truncate"
        }
    }
}
```

Docker Compose mounts a named volume (`delta-output`) at `/var/feldera/delta/`,
so the Parquet files are visible to the DuckDB sidecar container at
`/delta/gold_customer_lifetime_value/`.

DuckDB reads the output with:

```sql
SELECT * FROM delta_scan('/delta/gold_customer_lifetime_value');
```

Because the writer includes `__feldera_op` and `__feldera_ts`, the sidecar can
filter or order by pipeline step:

```sql
SELECT *
FROM   delta_scan('/delta/gold_customer_lifetime_value')
WHERE  __feldera_op = 'i'
ORDER  BY __feldera_ts DESC;
```

---

## 4. End-to-End: Bronze Snapshot → Gold Delta Table

Putting it all together for one table (`bronze_orders`):

```
  Azure Blob Storage
  abfss://public@rakirahman.dfs.core.windows.net/
  └─ feldera-demos/ecommerce-cdc-0-01/snapshot/bronze_orders/
     ├─ _delta_log/
     │   └─ 00000000000000000000.json
     ├─ part-00000-….snappy.parquet
     └─ part-00001-….snappy.parquet
         │
         │  DeltaTableInputReader (snapshot mode)
         │  Reads all Parquet → emits rows as +1 Z-set entries
         ▼
  ┌──────────────────────────────┐
  │  Feldera SQL Pipeline        │
  │                              │
  │  bronze_orders (input view)  │
  │       │                      │
  │       ▼                      │
  │  silver_orders (cleaned)     │
  │       │                      │
  │       ▼                      │
  │  gold_customer_lifetime_value│
  │  (aggregated view)           │
  └──────────────┬───────────────┘
                 │
                 │  DeltaTableWriter (truncate mode)
                 │  Converts Z-set → Parquet + metadata columns
                 ▼
  Local filesystem (Docker volume)
  /var/feldera/delta/gold_customer_lifetime_value/
  ├─ _delta_log/
  │   └─ 00000000000000000000.json
  ├─ part-00000-….snappy.parquet   ← contains __feldera_op, __feldera_ts
  └─ ...
         │
         │  DuckDB sidecar
         │  delta_scan('/delta/gold_customer_lifetime_value')
         ▼
  Query results served to dashboards / notebooks
```

---

## 5. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Integrated** (not Transport) | Delta Lake is columnar Parquet — there is no byte-stream to hand to a generic parser. The connector reads Arrow batches natively. |
| **AtLeastOnce** fault tolerance | The Delta log is immutable and versioned, so replaying from a known version is cheap and safe. Exactly-once would require two-phase commit with the downstream, which Delta's protocol does not support natively. |
| **Metadata columns** (`__feldera_op`, `__feldera_ts`) | Consumers can reconstruct change history without Delta's own Change Data Feed, which not all readers support. |
| **Truncate mode** for Gold outputs | Gold views are fully materialized aggregates — rewriting them each step is simpler and avoids compaction issues from accumulating deletes. |
| **`azure_skip_signature`** | The demo's source data lives in a public Azure container, so no credentials are needed. Production deployments would use managed identity or SAS tokens instead. |

---

## 6. Source File Reference

| File | Purpose |
|------|---------|
| `crates/adapterlib/src/transport.rs:36-84` | `InputEndpoint`, `TransportInputEndpoint`, `IntegratedInputEndpoint` trait definitions |
| `crates/adapterlib/src/format.rs:27-138` | `InputFormat`, `InputBuffer`, `Parser` trait definitions |
| `crates/adapters/src/controller.rs` | Controller that wires endpoints to the pipeline |
| `crates/adapters/src/integrated/delta_table/input.rs` | `DeltaTableInputEndpoint`, `DeltaTableInputReader`, snapshot + follow logic |
| `crates/adapters/src/integrated/delta_table/output.rs` | `DeltaTableWriter`, schema setup, metadata columns, Parquet encoding |
