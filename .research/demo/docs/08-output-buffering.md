# 8. Output Buffering and `pipeline_complete`

> **Context:** You have run the medallion demo and watched `demo.sh` poll until
> `pipeline_complete` flipped to `true`. This document explains what happened
> under the hood—how Feldera accumulates output, when it flushes, and how the
> controller decides the pipeline is "done."

---

## 8.1 Why Buffer at All?

Every evaluation step the DBSP circuit produces Z-set deltas for each output
view. Writing each micro-delta straight to a Delta Lake table would create
thousands of tiny Parquet files—one per step, per view. Delta Lake (and
downstream query engines) perform far better with fewer, larger files.

The **output buffer** sits between the circuit and the transport encoder. It
accumulates deltas and flushes them in bulk, trading a small amount of latency
for dramatically better write efficiency.

---

## 8.2 Gold-View Buffer Configuration

The medallion demo's gold views each carry:

```json
"enable_output_buffer": true,
"max_output_buffer_time_millis": 10000
```

| Parameter                       | Value  | Meaning                                           |
|---------------------------------|--------|---------------------------------------------------|
| `enable_output_buffer`          | `true` | Deltas are held in memory instead of pushed immediately |
| `max_output_buffer_time_millis` | `10000`| Buffer is flushed at least every **10 seconds**    |

Bronze and silver views in the demo do *not* enable buffering—they write
through immediately. Only the gold layer, whose output lands in Delta Lake
tables under `/var/feldera/delta/`, uses buffered writes.

---

## 8.3 Data Flow Through the Output Buffer

```
                          ┌──────────────────────────────────────────────┐
                          │          Feldera Controller                  │
                          │                                              │
  ┌─────────┐   Z-set    │  ┌──────────────┐        ┌───────────────┐  │
  │  DBSP   │  deltas    │  │   Output      │        │  Delta Lake   │  │
  │ Circuit  ├───────────►│  │   Buffer      │───────►│   Writer      │  │
  │ (step N) │            │  │               │  flush │  (encoder)    │  │
  └─────────┘            │  │ accumulated   │        │               │  │
                          │  │ inserts /     │        │  ┌──────────┐ │  │
                          │  │ deletes /     │        │  │ Parquet  │ │  │
         Triggers:        │  │ updates       │        │  │  file    │ │  │
         ─────────        │  └──────┬───────┘        │  │ (with    │ │  │
                          │         │                 │  │ __feldera│ │  │
  1. Timer expires ───────┼────────►│                 │  │ _op, _ts)│ │  │
     (10 s)               │         │                 │  └─────┬────┘ │  │
                          │         │                 │        │      │  │
  2. Commit completes ────┼────────►│                 │        ▼      │  │
                          │         │                 │  ┌──────────┐ │  │
  3. Buffer full ─────────┼────────►│                 │  │  Delta   │ │  │
                          │                           │  │  commit  │ │  │
                          │                           │  └──────────┘ │  │
                          │                           └───────────────┘  │
                          └──────────────────────────────────────────────┘
```

### Three flush triggers

| #  | Trigger             | When it fires                                      |
|----|---------------------|----------------------------------------------------|
| 1  | **Timer expiry**    | Wall-clock time since last flush ≥ `max_output_buffer_time_millis` (10 s) |
| 2  | **Commit boundary** | The circuit completes a commit (all steps in a batch are done)           |
| 3  | **Buffer full**     | In-memory buffer reaches its capacity limit                              |

In the demo's initial snapshot load the timer is the dominant trigger: the
circuit churns through input records much faster than 10 seconds, so the buffer
fills and the timer fires several times before the final commit.

---

## 8.4 Inside `controller.rs` — Buffered vs. Unbuffered Path

The decision lives in the output-batch handler
(`crates/adapters/src/controller.rs`, around line 6470–6515):

```rust
// Simplified view of the branching logic
if output_buffer_config.enable_output_buffer {
    // Accumulate into the buffer; flush later on timer / commit / full.
    output_buffer.insert(data, step, processed_records);
} else {
    // Unbuffered: push straight to the encoder transport.
    Self::push_batch_to_encoder(data, ...);
    controller.status.output_batch(...);
}
```

When `enable_output_buffer` is **false** (bronze / silver views), every Z-set
delta is serialized and pushed to the transport the moment it is produced.

When `enable_output_buffer` is **true** (gold views), deltas accumulate in
`output_buffer`. The controller's timer thread and commit-completion path both
check whether a flush is due.

---

## 8.5 What the Delta Writer Produces

Each flush results in:

1. **A Parquet file** written under the table's Delta directory
   (e.g., `/var/feldera/delta/gold_customer_orders/`).
2. Two Feldera-specific columns appended to every row:

   | Column          | Type   | Values                                  |
   |-----------------|--------|-----------------------------------------|
   | `__feldera_op`  | string | `i` (insert), `d` (delete), `u` (update)|
   | `__feldera_ts`  | int64  | Logical timestamp of the change          |

3. **An atomic Delta commit** (a new JSON entry in `_delta_log/`) that makes
   the Parquet file visible to readers.

Because the buffer coalesces many small steps into one write, a single Parquet
file may contain thousands of rows instead of dozens—exactly the file-size
profile Delta Lake is designed for.

---

## 8.6 `pipeline_complete` — How Feldera Knows It Is Done

### The flag

```rust
// crates/adapters/src/controller.rs  (lines ~1277-1292)
pub fn pipeline_complete(&self) -> bool {
    self.inner.status.pipeline_complete()
}
```

`pipeline_complete()` returns **true** when *all three* of the following hold:

```
 ┌─────────────────────────────────────────────────────────────┐
 │                 pipeline_complete == true                    │
 │                                                             │
 │  1. Every input endpoint has signaled "end of input."       │
 │     (For snapshot-mode Delta tables this means the full     │
 │      snapshot has been read.)                               │
 │                                                             │
 │  2. All buffered data has been processed through the        │
 │     circuit—no pending steps remain.                        │
 │                                                             │
 │  3. All output buffers have been flushed to their           │
 │     transports—nothing is sitting in memory.                │
 │                                                             │
 └─────────────────────────────────────────────────────────────┘
```

For **follow-mode** connectors (e.g., a Kafka topic or a Delta table in
`follow` mode), condition 1 never becomes true—there is always more data to
listen for—so `pipeline_complete` stays **false** indefinitely.

### Snapshot mode in the demo

The demo's input connector reads from a static Delta table in **snapshot**
mode. Once every Parquet file in the snapshot has been ingested, the input
endpoint signals completion, the circuit drains, the gold-view buffers flush,
and `pipeline_complete` transitions to `true`.

---

## 8.7 The `demo.sh` Polling Loop

`demo.sh` function `wait_for_pipeline_complete()` (lines 179–213):

```
  demo.sh                               Feldera API
  ────────                               ──────────
      │                                       │
      │  GET /v0/pipelines/medallion/stats    │
      ├──────────────────────────────────────►│
      │                                       │
      │  { "global_metrics": {                │
      │      "pipeline_complete": false,      │
      │      "total_input_records": 48000,    │
      │      "total_processed_records": 42000 │
      │    } }                                │
      │◄──────────────────────────────────────┤
      │                                       │
      │  (sleep 5 seconds)                    │
      │                                       │
      │  GET /v0/pipelines/medallion/stats    │
      ├──────────────────────────────────────►│
      │                                       │
      │  { "global_metrics": {                │
      │      "pipeline_complete": false,      │
      │      "total_input_records": 48000,    │
      │      "total_processed_records": 48000 │
      │    } }                                │
      │◄──────────────────────────────────────┤
      │                                       │
      │  (sleep 5 seconds)                    │
      │                                       │
      │  GET /v0/pipelines/medallion/stats    │
      ├──────────────────────────────────────►│
      │                                       │
      │  { "global_metrics": {                │
      │      "pipeline_complete": true,  ◄─── flag transitions
      │      "total_input_records": 48000,    │
      │      "total_processed_records": 48000 │
      │    } }                                │
      │◄──────────────────────────────────────┤
      │                                       │
      │  ✓ "Pipeline complete after 35s"      │
      │                                       │
```

Key details of the loop:

1. **Poll interval:** 5 seconds.
2. **Primary check:** `pipeline_complete == true`.
3. **Secondary telemetry:** `total_input_records` and
   `total_processed_records` are logged so you can watch progress even before
   completion.
4. **Elapsed time:** The function records the wall-clock time between the first
   poll and the `true` response, printing it for benchmarking.

---

## 8.8 Concrete Example: When Do Gold Tables Get Written?

Consider the initial snapshot load of ~48 000 records.

```
Time (s)   Event
────────   ──────────────────────────────────────────────────────
  0        Pipeline starts. Input connector begins reading the
           Delta snapshot. Bronze/silver views write through
           immediately (no buffering).

  0–10     Circuit processes steps rapidly. Gold output buffers
           accumulate Z-set deltas in memory.

 10        Timer fires (10 s elapsed). Gold buffers flush.
           → Delta writer creates Parquet file #1 for each gold
             view, commits a Delta version.
           Buffer resets.

 10–20     More steps processed. Gold buffers accumulate again.

 20        Timer fires again. Flush → Parquet file #2 per gold
           view, new Delta version.

 ~25       Input connector finishes reading the full snapshot.
           Signals "end of input."

 25–30     Circuit drains remaining buffered input. Final steps
           complete. Commit boundary reached.
           → Commit trigger flushes gold buffers one last time.
           → Parquet file #3, final Delta version.

 ~30       All three conditions met → pipeline_complete = true.

 ~30–35    Next demo.sh poll sees pipeline_complete == true.
           Prints "Pipeline complete after 35s."
```

**Result:** Each gold table ends up with roughly 3 Parquet files instead of
thousands—exactly the efficiency gain the output buffer provides.

---

## 8.9 Why 10 Seconds?

The `max_output_buffer_time_millis: 10000` value is a trade-off:

| Shorter timer (e.g., 1 s)              | Longer timer (e.g., 60 s)                |
|-----------------------------------------|-------------------------------------------|
| Lower end-to-end latency               | Higher end-to-end latency                 |
| More Parquet files, smaller each        | Fewer Parquet files, larger each          |
| More Delta commits → more log entries   | Fewer Delta commits → cleaner log         |
| Higher write overhead per record        | Better write amortization                 |

For the demo's batch-style workload (read snapshot, transform, write), **10
seconds** is a good middle ground: the pipeline runs for ~30 seconds, producing
only 2–4 Parquet files per gold table while keeping the wait reasonable.

In a streaming workload with tighter latency requirements, you might reduce
the timer or disable buffering entirely. In a nightly-batch scenario writing
very large tables, you might increase it to 30–60 seconds.

---

## 8.10 Summary

| Concept                 | Role                                                         |
|-------------------------|--------------------------------------------------------------|
| **Output buffer**       | Accumulates Z-set deltas between flushes                     |
| **Flush triggers**      | Timer expiry, commit boundary, buffer full                   |
| **Delta writer**        | Serializes buffered rows to Parquet with `__feldera_op`/`_ts`|
| **Delta commit**        | Atomic log entry making the Parquet file visible             |
| **`pipeline_complete`** | True when inputs exhausted + circuit drained + buffers flushed|
| **`demo.sh` poll loop** | Checks stats API every 5 s until `pipeline_complete == true` |

The output buffer is what turns Feldera's fine-grained incremental computation
into coarse-grained, efficient Delta Lake writes—bridging the impedance
mismatch between a streaming engine and a batch-oriented table format.
