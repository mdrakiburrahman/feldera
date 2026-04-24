# 6 — State Management, Traces, and Storage

Stateless operators (MAP, FILTER, arithmetic) consume their input and produce
output with no memory of previous steps. Stateful operators — GROUP BY, JOIN,
DISTINCT, window functions — must remember what they have seen. This document
explains *where* that state lives, how it is structured, and what happens when
it outgrows memory.

---

## 6.1 Traces and Spines

Every stateful operator maintains a **Trace**: the accumulated history of
Z-set updates it has processed. The Trace answers one question efficiently:
*given a key, what is its current collection of (value, weight) pairs across
all times?*

The `Trace` trait (`crates/dbsp/src/trace.rs:210–260`) defines the interface:

```
pub trait Trace: BatchReader {
    type Batch: Batch<...>;

    fn new(factories: &Self::Factories) -> Self;
    fn set_frontier(&mut self, frontier: &Self::Time);   // advance logical time
    fn exert(&mut self, effort: &mut isize);              // incremental merge work
    fn consolidate(self) -> Option<Self::Batch>;          // compact into one batch
    fn insert(&mut self, batch: Self::Batch);             // append new data
}
```

`set_frontier` advances the logical clock, enabling the Trace to discard
update records whose timestamps are no longer distinguishable. `exert`
performs bounded merge work so that compaction does not block the critical
path.

### The Spine: A Tiered Merge Structure

A Trace is backed by a **Spine** — a vector of sorted batches organized into
levels, similar to an LSM-tree. The implementation lives at
`crates/dbsp/src/trace/spine_async.rs`.

From the module doc:

> "This is a 'spine', a Trace that internally consists of a vector of batches.
> Inserting a new batch appends to the vector, and iterating or searching
> iterates all batches."

```
┌─────────────────────────────────────────────────────────────────┐
│                          Spine                                  │
│                                                                 │
│  Level 0  ┌───────┐ ┌───────┐ ┌───────┐    ← small, hot       │
│  (merge-0)│ batch │ │ batch │ │ batch │      recently ingested  │
│           └───────┘ └───────┘ └───────┘                         │
│                  ╲       │       ╱                               │
│                   ╲  merge when  ╱                               │
│                    ╲  too many  ╱                                │
│                     ▼         ▼                                  │
│  Level 1       ┌──────────────────┐   ← merged pairs from L0   │
│  (merge-1)     │   larger batch   │                             │
│                └──────────────────┘                              │
│                         │                                       │
│                    merge down                                   │
│                         ▼                                       │
│  Level 2       ┌────────────────────────┐                       │
│  (merge-2)     │    even larger batch   │                       │
│                └────────────────────────┘                        │
│                         │                                       │
│                        ...                                      │
│                         ▼                                       │
│  Level 8       ┌────────────────────────────────────────┐       │
│  (merge-8)     │         largest compacted batch        │       │
│                └────────────────────────────────────────┘       │
│                                                                 │
│  MAX_LEVELS = 9   (levels 0 through 8)                          │
│  LEVEL_NAMES: "merge-0" … "merge-8"                             │
└─────────────────────────────────────────────────────────────────┘
```

**Key properties:**

| Aspect | Detail |
|---|---|
| Levels | `MAX_LEVELS = 9` (line 88) |
| Level names | `"merge-0"` through `"merge-8"` (line 90) |
| L0 batch cap | `DEFAULT_MAX_LEVEL0_BATCH_SIZE` — must exceed `SPLITTER_OUTPUT_CHUNK_SIZE` and `DEFAULT_MAX_WORKER_BATCH_SIZE` (lines 95–100) |
| Merge strategy | Batches at a level are merged into the next level when a threshold is crossed |
| Merge modules | `list_merger`, `push_merger`, `snapshot` (imported at top of file) |

Reads scan across all batches at all levels and merge results on the fly.
Writes append a new batch at Level 0. Background merge tasks (`exert`) compact
batches downward so reads do not degrade over time.

---

## 6.2 Batch Types and the Fallback Pattern

Batches — the sorted containers within a Spine — come in two flavors:

| Type | Backing | When used |
|---|---|---|
| In-memory (`OrdIndexedZSet` variants) | Heap-allocated vectors | Data fits in memory |
| File-backed (`FileIndexedWSet`) | On-disk sorted files | Data spilled to disk |

The bridge between them is the **Fallback** type, defined in
`crates/dbsp/src/trace/ord.rs`:

```
pub use fallback::{
    indexed_wset::{FallbackIndexedWSet, FallbackIndexedWSet as OrdIndexedZSet, ...},
};
pub use file::{ FileIndexedWSet, ... };
```

`FallbackIndexedWSet` (aliased as `OrdIndexedZSet`) is the type that
operators actually use. It auto-switches between in-memory and file-backed
representation based on memory pressure:

```
                    ┌──────────────────────────────────────────┐
                    │        FallbackIndexedWSet               │
                    │        (aliased: OrdIndexedZSet)         │
                    │                                          │
                    │   ┌─────────────┐  ┌─────────────────┐   │
                    │   │  In-Memory  │  │  File-Backed    │   │
                    │   │  (vectors)  │  │  (sorted file)  │   │
                    │   └──────┬──────┘  └────────┬────────┘   │
                    │          │                   │            │
                    │          ▼                   ▼            │
                    │   Fast random         Disk I/O with      │
                    │   access              buffer cache        │
                    │                                          │
                    └──────────────────────────────────────────┘

  Decision logic:

  ┌────────────────────┐     memory     ┌──────────────────────┐
  │  New batch arrives  │───pressure────▶│  Spill to file       │
  │  (in-memory)        │    high?       │  (FileIndexedWSet)   │
  └────────────────────┘     no ↓        └──────────────────────┘
                        ┌────────────┐
                        │ Keep in    │
                        │ memory     │
                        └────────────┘
```

The Fallback type presents a uniform `BatchReader` interface regardless of
backing. Operators never need to know whether they are reading memory or disk.

---

## 6.3 Spill-to-Disk and Storage Backends

When a batch is spilled, it is written to a **storage backend**. The backend
abstraction lives under `crates/storage/src/backend/`:

| Backend | File | Purpose |
|---|---|---|
| **LocalFS** | `local_fs.rs` | Writes sorted batch files to the local filesystem |
| Others | Same directory | Pluggable backends (e.g., cloud object stores) |

### Memory Pressure Flow

```
  Step clock fires
        │
        ▼
  ┌──────────────────┐
  │  Operator runs    │
  │  on input Z-set   │
  └────────┬─────────┘
           │ produces new batch
           ▼
  ┌──────────────────┐       under         ┌───────────────────┐
  │  Insert batch    │──────budget?───YES──▶│  Keep in-memory   │
  │  into Spine L0   │                      │  (heap vectors)   │
  └────────┬─────────┘                      └───────────────────┘
           │ NO — memory pressure high
           ▼
  ┌──────────────────────────────────────┐
  │  Serialize batch → sorted file       │
  │  via storage backend (LocalFS)       │
  └────────┬─────────────────────────────┘
           │
           ▼
  ┌──────────────────────────────────────┐
  │  Batch becomes FileIndexedWSet       │
  │  Pages read on demand via            │
  │  buffer cache (§6.4)                 │
  └──────────────────────────────────────┘
```

Once on disk, the batch participates in the same Spine merge hierarchy. Merges
between file-backed batches produce new file-backed batches; the merge output
streams through memory without loading both inputs fully.

### What Gets Written

A file-backed batch is a sorted run of `(key, value, weight)` tuples, grouped
by key and then by value. The on-disk layout supports binary search on keys and
sequential scan within a key's value set — the same access patterns the Spine's
merge iterators use.

---

## 6.4 The Buffer Cache — S3-FIFO

File-backed batches do not read from disk on every access. A shared
**buffer cache** sits between operators and the storage backend. The
implementation at `crates/buffer-cache/src/s3_fifo.rs` uses the **S3-FIFO**
algorithm from the SOSP'23 paper.

```rust
pub struct S3FifoCache<K, V, S = RandomState> {
    cache: QuickCache<K, V, CacheEntryWeighter, S>,
}
```

`DEFAULT_SHARDS = 256` (line 38–41) provides high concurrency with minimal
lock contention. The cache delegates to `quick_cache`, a sharded,
weighted-entry cache.

### S3-FIFO Design (SOSP'23)

S3-FIFO maintains three logical queues:

```
  ┌───────────────────────────────────────────────────────────────┐
  │                      S3-FIFO Cache                            │
  │                                                               │
  │  Page read request                                            │
  │        │                                                      │
  │        ▼                                                      │
  │  ┌──────────┐   miss    ┌─────────────────────────────────┐   │
  │  │  Lookup  │──────────▶│  Read from disk, insert into    │   │
  │  │  in cache│           │  Small queue                    │   │
  │  └────┬─────┘           └─────────────────────────────────┘   │
  │       │ hit                                                   │
  │       ▼                                                       │
  │    Return page                                                │
  │                                                               │
  │  ┌─────────────────────────────────────────────────────────┐  │
  │  │                                                         │  │
  │  │  ┌────────────┐   promoted if   ┌──────────────────┐    │  │
  │  │  │   SMALL    │───accessed ────▶│      MAIN        │    │  │
  │  │  │  (FIFO)    │   again         │  (FIFO, freq>0)  │    │  │
  │  │  │            │                 │                   │    │  │
  │  │  │ admission  │                 │ entries survive   │    │  │
  │  │  │ filter:    │                 │ eviction while    │    │  │
  │  │  │ one-shot   │   evicted,      │ frequency > 0    │    │  │
  │  │  │ items die  │───key goes ───▶│                   │    │  │
  │  │  │ here       │   to Ghost      └────────┬─────────┘    │  │
  │  │  └────────────┘                          │              │  │
  │  │                                     evicted             │  │
  │  │                          ┌───────────────┘              │  │
  │  │                          ▼                              │  │
  │  │                 ┌──────────────────┐                    │  │
  │  │                 │     GHOST        │                    │  │
  │  │                 │  (metadata only) │                    │  │
  │  │                 │                  │                    │  │
  │  │                 │  Tracks recently │                    │  │
  │  │                 │  evicted keys.   │                    │  │
  │  │                 │  Prevents re-    │                    │  │
  │  │                 │  admitting one-  │                    │  │
  │  │                 │  hit wonders.    │                    │  │
  │  │                 └──────────────────┘                    │  │
  │  └─────────────────────────────────────────────────────────┘  │
  └───────────────────────────────────────────────────────────────┘
```

**Why S3-FIFO?**

| Property | Benefit for Feldera |
|---|---|
| FIFO-based, no LRU linked list | Lock-free sharded design (256 shards) |
| Ghost queue filters one-hit wonders | Scan-resistant: a full-table scan does not flush the cache |
| Weighted entries | Large pages and small pages coexist under one memory budget |
| O(1) admission/eviction | Predictable latency — critical for streaming at step granularity |

Traditional LRU caches collapse under scan workloads: a single large merge
pass can evict every hot page. S3-FIFO's admission filter ensures that pages
touched only once during a merge never reach the Main queue.

---

## 6.5 Concrete State Walkthrough — `gold_cancellation_impact`

Recall the query from previous documents. It computes weekly cancellation
rates by category with cumulative and 3-week rolling windows. Here is where
state lives at each stage.

### Stage 1: Inner GROUP BY — `(category, week) → aggregates`

```
  Trace (Spine):
  ┌───────────────────────────────────────────────────────────┐
  │  Key: (category, week)                                    │
  │  Value: (cnt_cancelled, cnt_total, sum_revenue_lost, ...) │
  │  Weight: 1                                                │
  │                                                           │
  │  Example contents (logical view):                         │
  │    ("Laptops", 2025-W01) → (3, 50, 4500.00, ...)  +1     │
  │    ("Laptops", 2025-W02) → (1, 42, 1200.00, ...)  +1     │
  │    ("Phones",  2025-W01) → (7, 120, 2800.00, ...) +1     │
  │    ...                                                    │
  └───────────────────────────────────────────────────────────┘
```

This Trace grows by one entry per `(category, week)` combination. For 20
categories and 52 weeks, the Trace holds ~1,040 entries — comfortably in
memory at Level 0 of the Spine.

### Stage 2: Window — `SUM(… UNBOUNDED PRECEDING)`

The cumulative window operator maintains its own Trace, keyed by
`(category)` as the partition and ordered by `week`:

```
  Trace (Spine):
  ┌───────────────────────────────────────────────────────────┐
  │  Partition: category                                      │
  │  Order:     week                                          │
  │  State:     running totals up to each week                │
  │                                                           │
  │    ("Laptops", W01) → cumulative_sum = 4500.00     +1     │
  │    ("Laptops", W02) → cumulative_sum = 5700.00     +1     │
  │    ...                                                    │
  └───────────────────────────────────────────────────────────┘
```

### Stage 3: Window — `SUM(… RANGE BETWEEN INTERVAL '3' WEEK …)`

The sliding window operator's Trace holds partial sums over the 3-week range:

```
  Trace (Spine):
  ┌───────────────────────────────────────────────────────────┐
  │  Partition: category                                      │
  │  Order:     week                                          │
  │  State:     3-week rolling sum for each week              │
  │                                                           │
  │    ("Laptops", W03) → rolling_3w = sum(W01..W03)   +1    │
  │    ("Laptops", W04) → rolling_3w = sum(W02..W04)   +1    │
  │    ...                                                    │
  └───────────────────────────────────────────────────────────┘
```

### Stage 4: Division (cancellation rate) — Stateless

The expression `cancelled / total` is a pure map. No Trace.

### Incremental Update: An Order Is Cancelled

```
  UPDATE bronze_orders SET order_status = 'cancelled'
  WHERE order_id = 42;

  ──────────────────────────────────────────────────────────────

  Input Z-set delta:
    { (order_42_old, -1), (order_42_new, +1) }

  ┌──────────────────────────────────────┐
  │  GROUP BY operator                   │
  │                                      │
  │  Lookup key: ("Laptops", 2025-W03)   │
  │  in Trace → find current aggregate   │
  │                                      │
  │  Emit delta:                         │
  │    (old_agg_row, -1)  ← retract      │
  │    (new_agg_row, +1)  ← insert       │
  │                                      │
  │  Update Trace: one key modified      │
  └──────────┬───────────────────────────┘
             │ delta flows downstream
             ▼
  ┌──────────────────────────────────────┐
  │  UNBOUNDED PRECEDING window          │
  │                                      │
  │  Affected weeks: W03, W04, ..., W52  │
  │  (all weeks ≥ W03 in "Laptops")      │
  │                                      │
  │  Emit deltas for each affected week  │
  │  Update Trace entries                │
  └──────────┬───────────────────────────┘
             │
             ▼
  ┌──────────────────────────────────────┐
  │  3-WEEK ROLLING window               │
  │                                      │
  │  Affected weeks: W03, W04, W05 only  │
  │  (the 3-week windows that include    │
  │   W03 in their range)                │
  │                                      │
  │  Emit deltas for 3 weeks             │
  │  Update Trace entries                │
  └──────────┬───────────────────────────┘
             │
             ▼
  ┌──────────────────────────────────────┐
  │  Division (stateless)                │
  │  Emit final output deltas            │
  └──────────────────────────────────────┘
```

**Cost:** The work is proportional to the number of *affected groups*, not the
total table size. Cancelling one order touches:
- 1 GROUP BY key
- O(weeks remaining in partition) for the cumulative window
- 3 entries for the rolling window
- 1 stateless division per output delta

For a table with millions of rows, updating a single cancellation produces a
handful of Trace lookups and a small Z-set delta at each stage.

---

## 6.6 Putting It All Together

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                        Feldera Worker                           │
  │                                                                 │
  │   Circuit Step N                                                │
  │   ┌───────────────────────────────────────────────────────┐     │
  │   │  Operator: GROUP BY (category, week)                  │     │
  │   │  ┌─────────────────────────────────────────────────┐  │     │
  │   │  │ Trace (Spine)                                   │  │     │
  │   │  │  L0: [batch_N] [batch_N-1]                      │  │     │
  │   │  │  L1: [merged_batch]                             │  │     │
  │   │  │  L2: [larger_merged]  ← may be file-backed     │  │     │
  │   │  └──────────────────────────┬──────────────────────┘  │     │
  │   └─────────────────────────────│─────────────────────────┘     │
  │                                 │                               │
  │                                 │ page read                     │
  │                                 ▼                               │
  │   ┌───────────────────────────────────────────────────────┐     │
  │   │          Buffer Cache  (S3-FIFO, 256 shards)          │     │
  │   │                                                       │     │
  │   │  hit? ──YES──▶ return page from memory                │     │
  │   │   │                                                   │     │
  │   │   NO                                                  │     │
  │   │   │                                                   │     │
  │   │   ▼                                                   │     │
  │   │  Read from storage backend                            │     │
  │   │  Insert into Small queue                              │     │
  │   │  Return page                                          │     │
  │   └───────────────────────────────────────────────────────┘     │
  │                                 │                               │
  │                                 │ miss path                     │
  │                                 ▼                               │
  │   ┌───────────────────────────────────────────────────────┐     │
  │   │          Storage Backend (LocalFS)                     │     │
  │   │                                                       │     │
  │   │  /var/feldera/data/pipeline_xxx/batch_yyy.dat         │     │
  │   └───────────────────────────────────────────────────────┘     │
  └─────────────────────────────────────────────────────────────────┘
```

### Key Takeaways

1. **Traces are the unit of state.** Each stateful operator owns exactly one
   Trace. The Trace is a Spine — a tiered merge structure with up to 9 levels.

2. **Spines amortize compaction.** Small batches arrive at Level 0 every step.
   Background merges compact them downward. Reads merge across levels
   on the fly.

3. **Fallback types hide the storage boundary.** `FallbackIndexedWSet` presents
   a uniform interface whether the batch is in memory or on disk. Operators
   never branch on storage location.

4. **S3-FIFO protects hot pages.** The buffer cache uses a scan-resistant
   eviction policy so that merge passes (which touch every page once) do not
   flush frequently accessed data.

5. **Incremental updates touch minimal state.** A single cancelled order
   produces small deltas that propagate through a few Trace lookups — the
   total table size is irrelevant.

---

## References

- DBSP Paper, §6 "Implementation"
- `crates/dbsp/src/trace/spine_async.rs` — Spine implementation
- `crates/dbsp/src/trace.rs:210–260` — Trace trait
- `crates/dbsp/src/trace/ord.rs` — Batch type aliases (Fallback, File)
- `crates/storage/src/backend/local_fs.rs` — LocalFS storage backend
- `crates/buffer-cache/src/s3_fifo.rs` — S3-FIFO cache implementation
- Yang et al., "FIFO queues are all you need for cache eviction" (SOSP'23)
