# 9. Fault Tolerance: Checkpointing and Recovery

> **Prerequisites:** You understand traces (Section 5) and how stateful operators
> accumulate history. This section explains how Feldera persists that state so a
> crashed pipeline can resume without duplicates or gaps.

Fault tolerance in Feldera rests on a single idea: **checkpoint everything the
pipeline needs to resume exactly where it left off.** The "everything" is small
and precise—traces, input positions, output bookmarks, and a step counter—which
keeps checkpoints fast and recovery deterministic.

---

## 9.1 What Gets Checkpointed

A checkpoint is a consistent snapshot of four things:

```
┌─────────────────────────────────────────────────────────────┐
│                      CHECKPOINT  (step N)                   │
│                                                             │
│  ┌───────────────────┐   ┌──────────────────────────────┐  │
│  │   Trace State      │   │   Input Resume Metadata      │  │
│  │                    │   │                              │  │
│  │  Accumulated       │   │  Delta table version: 3      │  │
│  │  history for       │   │  Kafka partition offsets:     │  │
│  │  every stateful    │   │    p0=1042, p1=872           │  │
│  │  operator (join,   │   │  Snapshot timestamp:         │  │
│  │  aggregate, etc.)  │   │    2024-01-15T09:30:00Z      │  │
│  └───────────────────┘   └──────────────────────────────┘  │
│                                                             │
│  ┌───────────────────┐   ┌──────────────────────────────┐  │
│  │   Output Stats     │   │   Step Number                │  │
│  │                    │   │                              │  │
│  │  Records written   │   │  N = transaction boundary    │  │
│  │  per output        │   │  marker that ties inputs,    │  │
│  │  endpoint (used    │   │  processing, and outputs     │  │
│  │  for dedup on      │   │  into a single atomic unit   │  │
│  │  replay)           │   │                              │  │
│  └───────────────────┘   └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Trace State

Recall from Section 5 that every stateful operator maintains a *trace*—the full
history of Z-set updates across all steps. A checkpoint serializes these traces
to durable storage. On recovery, the operator reconstructs its accumulated state
from the checkpoint rather than replaying every input since the beginning of time.

This connects directly to the DBSP paper's formalization: the `integrate` (∫)
operator accumulates a running sum of deltas. A checkpoint captures the value of
that running sum at step N, so recovery can start from the integral's value
instead of recomputing the sum from step 0.

### Input Resume Metadata

Each input connector records the position it has read up to. The format is
connector-specific:

| Connector    | Resume metadata                              |
|-------------|----------------------------------------------|
| Delta Lake  | Table version + optional snapshot timestamp   |
| Kafka       | Per-partition offsets                         |
| HTTP push   | *None — cannot replay*                       |

For Delta Lake, the resume info has three shapes depending on where the
connector was interrupted:

```
DeltaResumeInfo { version: Some(v), snapshot_timestamp: Some(ts) }
    → interrupted mid-snapshot; resume from timestamp within version v

DeltaResumeInfo { version: Some(v), snapshot_timestamp: None }
    → follow mode; resume from version v+1

DeltaResumeInfo { version: None, snapshot_timestamp: None }
    → clean state; start from the beginning
```

The Delta Lake input endpoint implements `FtModel::AtLeastOnce`, meaning
the connector may re-deliver some records on recovery. Feldera's output
deduplication layer (using step numbers and output stats) ensures the
end-to-end guarantee is effectively exactly-once.

### Output Statistics

The checkpoint records how many records each output endpoint has committed at
step N. On recovery, output endpoints use these counts to skip records that
were already written before the crash, preventing duplicate output.

### Step Number

The step number is the transaction boundary marker. It ties together "inputs
read," "traces updated," and "outputs written" into a single logical
transaction. A checkpoint at step N means: all effects of steps 0 through N are
durable and consistent.

---

## 9.2 The Checkpointer

The `Checkpointer` struct manages the lifecycle of checkpoints:

```rust
// crates/dbsp/src/circuit/checkpointer.rs:28-38
pub struct Checkpointer {
    backend: Arc<dyn StorageBackend>,
    checkpoint_list: VecDeque<CheckpointMetadata>,
}
```

The `backend` is a trait object abstracting over storage implementations:

- **LocalFS** (`crates/storage/src/backend/local_fs.rs`): writes checkpoint
  data to a local directory. Simple and fast; suitable for single-node
  deployments.
- **Cloud backends**: same trait, different backing store (S3, Azure Blob
  Storage, etc.).

The `checkpoint_list` is a bounded deque of recent checkpoints. Older
checkpoints beyond the retention window are garbage-collected.

Two companion types coordinate the checkpoint process:

- **`CheckpointBuilder`** (dbsp_handle.rs:1545): collects trace snapshots and
  input metadata from all operators during the checkpoint phase.
- **`CheckpointCommitter`** (dbsp_handle.rs:1618): atomically commits the
  assembled checkpoint to the storage backend once all components have
  contributed their state.

---

## 9.3 Persistent vs. Ephemeral Mode

Feldera's `CircuitConfig` offers two operator-identity modes that determine how
flexibly you can restore from a checkpoint:

```
┌─────────────────────────────────────────────────────────────┐
│                    CircuitConfig Mode                        │
├─────────────────────────┬───────────────────────────────────┤
│      Ephemeral          │         Persistent                │
│      (default)          │                                   │
├─────────────────────────┼───────────────────────────────────┤
│ Operators get IDs based │ Operators get stable IDs that     │
│ on circuit construction │ survive circuit modifications     │
│ order                   │ (e.g., adding a new view)         │
│                         │                                   │
│ Checkpoint restore      │ Checkpoint restore works even     │
│ requires the circuit    │ after the SQL program changes,    │
│ to be identical to when │ as long as existing operators     │
│ the checkpoint was      │ retain their persistent IDs       │
│ taken                   │                                   │
├─────────────────────────┼───────────────────────────────────┤
│ Use when: development,  │ Use when: production pipelines    │
│ testing, pipelines that │ that evolve over time, schema     │
│ never change            │ migrations, live upgrades         │
└─────────────────────────┴───────────────────────────────────┘
```

**When does this matter?** Suppose you have a running pipeline checkpointing
every 30 seconds. You add a new materialized view to the SQL program and
redeploy. In **Ephemeral** mode, the operator IDs shift because the circuit
structure changed—the checkpoint is now incompatible. In **Persistent** mode,
existing operators keep their stable IDs, so the checkpoint restores correctly
and only the new operator starts with empty state.

---

## 9.4 Recovery Flow

When a pipeline restarts after a crash, the recovery sequence is:

```
         CRASH at step N
              │
              ▼
  ┌───────────────────────┐
  │ 1. Load latest        │   Read checkpoint from storage backend
  │    checkpoint          │   (step N metadata + serialized traces)
  └──────────┬────────────┘
             │
             ▼
  ┌───────────────────────┐
  │ 2. Restore Trace      │   Deserialize accumulated history into
  │    state               │   every stateful operator (joins,
  │                        │   aggregates, etc.)
  └──────────┬────────────┘
             │
             ▼
  ┌───────────────────────┐
  │ 3. Resume inputs      │   Each input connector reads its stored
  │    from metadata       │   position and seeks to that point:
  │                        │     Delta → version N
  │                        │     Kafka → partition offsets
  └──────────┬────────────┘
             │
             ▼
  ┌───────────────────────┐
  │ 4. Skip committed     │   Output endpoints compare current step
  │    outputs             │   with checkpoint's output stats;
  │                        │   suppress records already written
  └──────────┬────────────┘
             │
             ▼
  ┌───────────────────────┐
  │ 5. Resume processing  │   Pipeline continues from step N+1
  │                        │   No duplicates, no gaps
  └───────────────────────┘
```

The key invariant is: **after recovery, the pipeline's observable behavior is
indistinguishable from one that never crashed.** Downstream consumers see each
record exactly once.

---

## 9.5 How Input Resume Metadata Enables Exactly-Once

The interaction between at-least-once input replay and output deduplication
produces an effectively exactly-once guarantee:

```
  Input Source              Feldera Pipeline              Output Sink
  (Delta Lake)                                           (e.g., Kafka)
      │                          │                            │
      │   version 1 ────────►   │                            │
      │   version 2 ────────►   │  step 1: process v1+v2     │
      │                          │ ──────────────────────►    │  output A
      │                          │                            │
      │   version 3 ────────►   │  step 2: process v3        │
      │                          │                            │
      │                     ╔════╧════╗                       │
      │                     ║  CRASH  ║  (output for step 2   │
      │                     ╚════╤════╝   may be partial)     │
      │                          │                            │
      │              ┌───────────┴──────────┐                 │
      │              │  RECOVERY            │                 │
      │              │  Checkpoint says:    │                 │
      │              │   step=1             │                 │
      │              │   delta_version=2    │                 │
      │              │   output_count=|A|   │                 │
      │              └───────────┬──────────┘                 │
      │                          │                            │
      │   version 3 ────────►   │  step 2 (replayed):        │
      │   (re-read from v3)     │  process v3 again          │
      │                          │                            │
      │                          │  Output dedup: skip |A|    │
      │                          │  records already written,  │
      │                          │  then emit remaining ──►   │  output B
      │                          │                            │
      │   version 4 ────────►   │  step 3: normal processing │
      │                          │ ──────────────────────►    │  output C
```

The three pieces work together:

1. **Input resume metadata** tells the connector where to start re-reading
   (version 3), so it does not reprocess versions 1 and 2.
2. **Trace state** restores the accumulated integral from the checkpoint, so
   operators do not need to see versions 1 and 2 to produce correct deltas for
   version 3.
3. **Output stats** tell the output endpoint how many records from step 2 were
   already committed, so it skips those and writes only the remainder.

---

## 9.6 Concrete Example: Demo Pipeline Crash Recovery

Consider the demo pipeline reading `bronze_orders` from a Delta Lake table.
The pipeline has been running and has processed versions 1 through 3:

**Before crash:**
```
Step 1: read bronze_orders version 1 → checkpoint taken
Step 2: read bronze_orders version 2 → checkpoint taken
Step 3: read bronze_orders version 3 → CRASH mid-step (checkpoint NOT taken)
```

**Checkpoint on disk (from step 2):**
```json
{
  "step": 2,
  "traces": { "join_orders_items": "<serialized>", "agg_revenue": "<serialized>" },
  "input_resume": { "bronze_orders": { "version": 2, "snapshot_timestamp": null } },
  "output_stats": { "silver_enriched_orders": { "records_written": 4200 } }
}
```

**Recovery sequence:**

1. **Load checkpoint:** step 2 restored from storage backend.

2. **Restore traces:** the `join_orders_items` and `agg_revenue` operators now
   hold the accumulated state as of step 2. They "remember" every order and
   item from versions 1 and 2 without re-reading them.

3. **Resume Delta input:** the `DeltaTableInputEndpoint` sees
   `DeltaResumeInfo { version: Some(2), snapshot_timestamp: None }` and begins
   reading from version 3 (the next version after the checkpoint).

4. **Output dedup:** the `silver_enriched_orders` endpoint knows 4,200 records
   were already committed. If the crash happened after writing 3,000 of 5,000
   records for step 3, the endpoint skips those 3,000 on replay and writes
   only the remaining 2,000.

5. **Resume:** step 3 processes version 3 again, produces correct output (since
   traces hold the right accumulated state), and the pipeline continues to
   step 4 as though nothing happened.

**Net effect:** downstream consumers of `silver_enriched_orders` see every
record exactly once. No version 1 or 2 data is re-emitted. No version 3 data
is duplicated.

---

## 9.7 DBSP Paper Tie-In: Why Checkpointing the Integral Works

The DBSP paper defines the `integrate` operator (∫) as:

```
∫(s)[t] = Σ_{i=0}^{t} s[i]
```

That is, the integral at step *t* equals the sum of all deltas from step 0
through step *t*. A checkpoint captures `∫(s)[t]` directly. On recovery:

- The operator loads `∫(s)[t]` from the checkpoint.
- New deltas arrive starting from step *t+1*.
- The operator computes `∫(s)[t+1] = ∫(s)[t] + s[t+1]`.

This is correct by definition—no need to replay steps 0 through *t*. The
checkpoint *is* the integral, and the integral *is* all the state the operator
needs.

This property holds for every stateful operator in the DBSP framework because
they are all defined in terms of `integrate` and `differentiate`. The trace
(which stores the integral's history) is the single source of truth. Checkpoint
the traces, and you checkpoint the entire computation.

---

## 9.8 Limitations and Requirements

Fault tolerance is a **preview feature**. Not all connector combinations
support it:

| Requirement              | Supported                      | Not Supported          |
|--------------------------|-------------------------------|------------------------|
| Input replay             | Delta Lake, Kafka             | HTTP push, WebSocket   |
| Output deduplication     | Kafka (with keys), Delta Lake | Connectors without     |
|                          |                               | idempotent writes      |
| Checkpoint storage       | Local disk, cloud backends    |                        |

**Constraints:**

- **Input connectors must support replay.** The connector needs a seekable
  position (version number, offset) to resume from. Stateless push-based
  inputs like HTTP cannot replay missed data.

- **Output connectors should support idempotent writes or deduplication.**
  Without this, duplicate output is possible if the crash occurs after partial
  output but before the next checkpoint.

- **Checkpoint storage must be durable.** If the checkpoint itself is lost
  (e.g., local disk failure without replication), recovery falls back to a
  full recomputation from the earliest available input.

- **Ephemeral mode restricts schema evolution.** If you anticipate changing
  your SQL program while preserving state, use Persistent mode (Section 9.3).

---

## 9.9 Summary

| Concept                  | Role in Fault Tolerance                                      |
|--------------------------|--------------------------------------------------------------|
| Trace checkpoint         | Persists the integral (∫) so operators skip full replay      |
| Input resume metadata    | Tells connectors where to re-read from (version, offset)     |
| Output statistics        | Prevents duplicate writes on recovery                        |
| Step number              | Transaction boundary tying inputs, state, and outputs        |
| Persistent mode          | Stable operator IDs surviving circuit modifications          |
| Checkpointer             | Manages checkpoint lifecycle and garbage collection          |
| CheckpointBuilder        | Collects state from all operators during checkpoint phase    |
| CheckpointCommitter      | Atomically commits the assembled checkpoint to storage       |

**The core insight:** because DBSP operators are defined in terms of integrals
over Z-set streams, checkpointing reduces to saving the value of each integral.
Recovery is loading those values and continuing the summation. The mathematical
framework makes fault tolerance a natural consequence of the computation model,
not a bolted-on afterthought.
