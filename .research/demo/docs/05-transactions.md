# 5 — Transactions, Commits, and Step Boundaries

> **Prerequisites:** You have run the demo pipeline and understand Z-sets
> (document 03). Now we explain *when* computation happens and what
> "consistent" means.

---

## 5.1 Three Levels of Granularity

Feldera has three distinct timing concepts. Confusing them is the most
common source of misunderstanding.

```
┌─────────────────────────────────────────────────────────┐
│                     CHECKPOINT                          │
│  Persisted snapshot of the entire circuit state.        │
│  Optional — enables fault-tolerant restart.             │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │                 TRANSACTION                       │  │
│  │  One user-visible batch of input changes.         │  │
│  │  May span multiple steps (nested circuits).       │  │
│  │  Ends when step() returns true.                   │  │
│  │                                                   │  │
│  │  ┌─────┐  ┌─────┐  ┌─────┐        ┌─────┐       │  │
│  │  │STEP │  │STEP │  │STEP │  ...   │STEP │       │  │
│  │  │  1  │  │  2  │  │  3  │        │  N  │       │  │
│  │  └─────┘  └─────┘  └─────┘        └─────┘       │  │
│  │  One clock tick of the root circuit.              │  │
│  │  Every operator fires exactly once per step.      │  │
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │              TRANSACTION  (next)                  │  │
│  │  ...                                              │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

| Concept    | Scope                          | Code entry point                              |
|------------|--------------------------------|-----------------------------------------------|
| Step       | One clock tick; all operators fire once | `circuit.step()` in the scheduler loop |
| Transaction| User-visible batch; one or more steps  | `start_commit_transaction()` → loop `step()` until complete |
| Checkpoint | Persisted state snapshot               | `Checkpointer::commit()` via `CheckpointBuilder` / `CheckpointCommitter` |

### Where these live in the code

- **`DBSPHandle`** (`crates/dbsp/src/circuit/dbsp_handle.rs` ~line 270)
  manages the circuit's command channel. Its `CircuitConfig` (line 279)
  carries `layout`, `max_rss_bytes`, `pin_cpus`, `mode`, and `storage`.

- **`Mode`** enum controls operator identity:
  - `Persistent` — operators receive stable IDs (required for checkpoints).
  - `Ephemeral` — default; operators get transient IDs.

- **`Checkpointer`** (`crates/dbsp/src/circuit/checkpointer.rs:28-38`):
  ```rust
  pub struct Checkpointer {
      backend: Arc<dyn StorageBackend>,
      checkpoint_list: VecDeque<CheckpointMetadata>,
  }
  ```
  `CheckpointBuilder` (line 1545) and `CheckpointCommitter` (line 1618)
  in `dbsp_handle.rs` handle the two-phase persist protocol.

---

## 5.2 The Transaction Lifecycle

Below is the full sequence from "data arrives" to "views are consistent."

```
  Client / Adapter                  Controller                     DBSP Circuit
  ─────────────────                 ──────────                     ────────────
        │                                │                              │
        │  push(input_batch)             │                              │
        │──────────────────────────────►│                              │
        │                                │                              │
        │                                │  input_step()                │
        │                                │─────────────────────────────►│
        │                                │                              │
        │                                │  start_commit_transaction()  │
        │                                │─────────────────────────────►│
        │                                │                              │
        │                                │         ┌──────────────┐    │
        │                                │         │  step() [1]  │    │
        │                                │         │  step() [2]  │    │
        │                                │         │  ...         │    │
        │                                │         │  step() [N]  │──► returns true
        │                                │         └──────────────┘    │
        │                                │                              │
        │                                │  push_output(records)        │
        │                                │◄─────────────────────────────│
        │                                │                              │
        │  output_batch (views updated)  │                              │
        │◄──────────────────────────────│                              │
        │                                │                              │
        │                                │  [optional] checkpoint()     │
        │                                │─────────────────────────────►│
        │                                │                              │
```

### In code: the scheduler's transaction loop

From `crates/dbsp/src/circuit/schedule.rs` (lines 179–344), the
`Scheduler` and `Executor` traits define the core loop:

```rust
// Simplified from schedule.rs
async fn transaction<C>(&self, circuit: &C) -> Result<(), Error> {
    self.start_transaction(circuit).await?;
    self.start_commit_transaction()?;
    while !self.is_commit_complete() {
        self.step(circuit).await?;       // one clock tick
    }
    Ok(())
}
```

Each call to `step()` fires every operator in the circuit exactly once.
For a flat (non-nested) circuit, a transaction completes in a single step.
Nested circuits (e.g., those implementing iterative fixed-point queries)
may require multiple steps before convergence.

### In code: the controller's step method

The `Controller` in `crates/adapters/src/controller.rs` (lines 2875–2951)
orchestrates the adapter layer:

```rust
// Simplified from controller.rs
fn step(&mut self) -> Result<bool, ControllerError> {
    self.input_step()?;           // drain queued input batches
    self.step += 1;               // increment step counter
    self.step_circuit();          // fire all operators once
    self.push_output(processed_records);  // emit to output adapters
}
```

### In code: the command channel

`DBSPHandle` communicates with the circuit thread via a command enum:

```rust
Ok(Command::CommitTransaction) => {
    let status = circuit
        .start_commit_transaction()
        .map(|_| Response::Unit);
}
Ok(Command::CommitProgress) => {
    let status = Ok(Response::CommitProgress(
        circuit.commit_progress()
    ));
}
```

`CommitProgress` (imported at line 4 of `dbsp_handle.rs`) lets the
controller poll for completion without blocking — essential for the
async adapter layer.

---

## 5.3 What "Consistent" Means

After a transaction completes, every materialized view reflects the exact
result of running the full SQL program on the entire accumulated input —
as if you had loaded all data into a batch engine and run the query from
scratch.

**This is Feldera's central guarantee:**

> Incremental computation = batch recomputation.

The formal basis is **DBSP Theorem 3.3** (§3 of the DBSP paper,
[arXiv:2203.16684](https://arxiv.org/abs/2203.16684)):

> For any query Q expressible as a composition of linear and bilinear
> operators, the incremental evaluation `I(Q)` applied to a stream of
> changes produces the same output stream as running `Q` on the
> accumulated input at each time step.

In practical terms:

- You never see a "half-updated" view. Either the transaction has
  committed and all views reflect the new data, or the old state is
  still visible.
- There is no "eventually consistent" window. Outputs are available
  as soon as `step()` returns `true`.
- The guarantee holds regardless of whether you insert one row or
  replay an entire Kafka topic — the math is the same.

---

## 5.4 Demo Walkthrough: Transactions During Initial Snapshot Load

When you start the demo pipeline with the preloaded dataset, here is
what happens at the transaction level.

### How many transactions?

The answer depends on how the input adapters batch data:

```
Demo startup
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  Transaction 1: Initial snapshot batch               │
│                                                      │
│  Input adapters drain all queued records into one     │
│  input_step(). The controller calls                  │
│  start_commit_transaction(), then step() runs once   │
│  (flat circuit → single step per transaction).       │
│                                                      │
│  Every INSERT from the preloaded data appears as a   │
│  Z-set entry with weight +1.                         │
│                                                      │
│  All views update atomically to reflect the full     │
│  dataset.                                            │
└──────────────────────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  Transaction 2..N: Streaming updates                 │
│                                                      │
│  As new records arrive (manual inserts, CDC, Kafka), │
│  each batch triggers a new transaction. The          │
│  controller accumulates input until its next step    │
│  cycle, then commits.                                │
└──────────────────────────────────────────────────────┘
```

For the demo's flat SQL program (no recursive queries), each transaction
completes in exactly **one step**. If you had a recursive query (e.g.,
transitive closure), the inner circuit would iterate multiple steps
until convergence.

### What triggers each commit?

The controller's main loop triggers a commit when:

1. **Input is available** — adapters have buffered at least one record.
2. **The step timer fires** — the controller runs on a configurable
   tick interval, draining whatever input has accumulated.

During the initial snapshot, all preloaded data is available
immediately, so the first tick consumes everything in one transaction.

### Container log lines to look for

When running `docker compose logs -f pipeline`, you will see entries
that correspond to transaction boundaries:

```
# Commit starts — the controller has called start_commit_transaction()
pipeline  | ... start_commit_transaction ...

# Step counter increments
pipeline  | ... step 1 ...

# Commit completes — step() returned true, outputs are flushed
pipeline  | ... commit complete, processed N records ...

# If checkpointing is enabled:
pipeline  | ... checkpoint saved: <uuid> ...
```

The exact log format depends on the tracing configuration and Feldera
version. Key patterns to grep for:

```bash
docker compose logs pipeline 2>&1 | grep -iE 'commit|step|checkpoint'
```

---

## 5.5 Checkpoint Deep Dive

Checkpoints are orthogonal to transactions. A checkpoint persists the
circuit's entire operator state so that it can be restored after a
crash.

```
  Transaction N completes
         │
         ▼
  ┌─────────────────────────┐
  │  CheckpointBuilder      │   Collects state from every
  │  (dbsp_handle.rs:1545)  │   persistent operator.
  └────────────┬────────────┘
               │
               ▼
  ┌─────────────────────────┐
  │  CheckpointCommitter    │   Writes to StorageBackend
  │  (dbsp_handle.rs:1618)  │   (local disk, S3, etc.)
  └────────────┬────────────┘
               │
               ▼
  ┌─────────────────────────┐
  │  Checkpointer           │   Maintains a VecDeque of
  │  (checkpointer.rs:28)   │   CheckpointMetadata for
  │                         │   garbage collection.
  └─────────────────────────┘
```

Checkpoints require `Mode::Persistent` in the `CircuitConfig`. In
`Ephemeral` mode (the default), operators do not have stable IDs and
checkpoint/restore is not available.

---

## 5.6 Summary

| Question | Answer |
|----------|--------|
| When do views update? | At transaction boundaries — after `step()` returns `true`. |
| Can I see partial results? | No. Views are atomic per transaction. |
| How many steps per transaction? | One for flat circuits. Multiple for nested/recursive circuits. |
| Is incremental correct? | Yes — DBSP Theorem 3.3 guarantees incremental = batch. |
| What if the process crashes? | Without checkpoints: replay from source. With checkpoints: restore and resume. |
| Does the demo use checkpoints? | By default, no (`Ephemeral` mode). Enable with `Mode::Persistent` + storage config. |

---

*Next: [06 — Storage and Persistence](06-storage.md) — how Feldera
manages on-disk state for large datasets.*
