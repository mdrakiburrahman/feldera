# Z-Set Algebra and DBSP Theory

> **Audience**: You have run the medallion-architecture demo and want to understand
> the mathematical machinery that makes Feldera incremental.  This document maps
> the theory from the [DBSP paper](https://arxiv.org/abs/2203.16684) to the Rust
> types you will find in `crates/dbsp/`.

---

## 1  What Is a Z-Set?

A **Z-set** is a function that maps elements to integer *weights*.

| Element             | Weight |
|---------------------|--------|
| `supplier_row_A`   | `+1`   |
| `supplier_row_B`   | `+1`   |
| `supplier_row_C`   | `-1`   |

*DBSP Paper: §2 "Streams and Z-sets", Definition 2.1*

A weight of **+1** means *insert* (the element is present); **-1** means *delete*
(the element has been retracted).  A weight of **0** means the element is absent —
it has been fully cancelled out.

### 1.1  Why integers?

Z-sets form a **commutative group** under pointwise addition:

```
  {(A, +1), (B, +1)}          -- batch 1: insert A and B
+ {(B, -1), (C, +1)}          -- batch 2: delete B, insert C
= {(A, +1), (B,  0), (C, +1)} -- result:  A and C present, B cancelled
```

Because addition has an inverse (negation), we can always *undo* previous
contributions.  This is the algebraic property that makes incremental
evaluation possible — you never need to re-read the entire input.

### 1.2  Z-Sets in the Feldera codebase

The core weight type is a simple signed 64-bit integer:

```rust
// crates/dbsp/src/algebra/zset.rs, line 39
pub type ZWeight = i64;

// line 43 — dynamically-typed wrapper for FFI / trait-object boundaries
pub type DynZWeight = DynWeightTyped<ZWeight>;
```

A Z-set that maps **keys** to weights (no associated value) is:

```rust
// crates/dbsp/src/algebra/zset.rs, line 46
pub type OrdZSet<K> = OrdWSet<K, DynZWeight>;
```

When a value is associated with each key we get an **Indexed Z-Set** — conceptually
a set of `(key, value, weight)` tuples:

```rust
// crates/dbsp/src/algebra/zset.rs
//   "An IndexedZSet is conceptually a set of (key, value, weight) tuples."

// line 129
pub trait IndexedZSetReader: BatchReader<Time = (), R = DynZWeight> { .. }

// line 145
pub trait IndexedZSet: Batch<Time = (), R = DynZWeight> { .. }

// line 240 — flat Z-set (Val = unit type, i.e. no value)
pub trait ZSetReader: IndexedZSetReader<Val = DynUnit> { .. }

// line 248
pub trait ZSet: IndexedZSet<Val = DynUnit> { .. }
```

Concrete backing stores live in `crates/dbsp/src/trace/ord/mod.rs`:

| Type                   | Storage    | Notes                                        |
|------------------------|------------|----------------------------------------------|
| `OrdIndexedWSet`       | Memory     | In-memory sorted batches                     |
| `FileIndexedWSet`      | Disk       | On-disk sorted batches (lines 29-31)         |
| `FallbackIndexedWSet`  | Auto       | Starts in memory, spills to disk (lines 11-13) — aliased as `OrdIndexedZSet` |

The `Fallback*` types are the default.  When `max_rss_bytes` is exceeded the
runtime transparently moves batches to disk without changing operator semantics.

### 1.3  ASCII picture

```
                      Z-Set Z
    ┌──────────────────────────────────┐
    │  Element              Weight     │
    ├──────────────────────────────────┤
    │  supplier_row_A        +1        │  ← present (inserted)
    │  supplier_row_B        +1        │  ← present (inserted)
    │  supplier_row_C        -1        │  ← retracted (deleted)
    │  supplier_row_D         0        │  ← absent  (cancelled)
    └──────────────────────────────────┘

    Stored as sorted (key, weight) pairs.
    Zeros are never materialised — they are elided during compaction.
```

---

## 2  The Incrementalization Theorem

*DBSP Paper: §3 "Incremental computation", Theorem 3.3*

Given a query **Q** and input collection **i**, the naive approach recomputes
from scratch every time:

```
Full input i ──────► [ Q ] ──────► Full output Q(i)
```

When a change **Δi** arrives, we could recompute `Q(i + Δi)` — but that means
re-reading all of **i**, which may be terabytes.

The **Incrementalization Theorem** says:

```
Q(i + Δi) = Q(i) + ΔQ(Δi, state)
               │         │
               │         └── incremental operator: reads ONLY the change
               └──────────── previous output (already computed)
```

Because Z-sets are a group, we can *add* the delta to the previous result.  The
state term captures whatever the operator needs to remember (e.g., the contents
of one side of a join for a lookup).

### 2.1  Batch vs. incremental — visual comparison

```
 ── Batch path (traditional) ──────────────────────────────────

   Full input i + Δi          Full output
   ┌──────────────┐           ┌──────────────┐
   │  10 M rows   │──► Q ───►│  10 M rows   │   cost: O(|i|)
   └──────────────┘           └──────────────┘


 ── Incremental path (DBSP / Feldera) ────────────────────────

   Only the change Δi          Only the output change
   ┌──────────────┐           ┌──────────────┐
   │  12 rows     │──► ΔQ ──►│   3 rows     │   cost: O(|Δi|)
   └──────────────┘    │      └──────────────┘
                       │
                    ┌──┴──┐
                    │state│   (trace — see §3)
                    └─────┘
```

Key insight: the incremental operator **ΔQ** works with O(|Δi|) data,
not O(|i|).  For a 10-million-row table with 12 new rows per second, this
is a six-orders-of-magnitude improvement.

---

## 3  Traces — The State Behind Incrementality

*DBSP Paper: §5 "Nested incremental computation"*

Some operators are *stateless* (e.g., `map`, `filter`) — they need only the
current delta.  Others are *stateful* (e.g., `join`, `distinct`, `aggregate`) —
they must remember the accumulated collection to produce correct deltas.

A **Trace** stores the history of Z-set changes over discrete time steps
and provides efficient lookup into that history.

### 3.1  The Trace trait

```rust
// crates/dbsp/src/trace.rs, lines ~210-260
pub trait Trace: .. {
    fn new(activator: Option<Activator>) -> Self;

    /// Restrict the trace to times ≥ frontier.
    fn set_frontier(&mut self, frontier: AntichainRef<Self::Time>);

    /// Allow background work (merges) to proceed.
    fn exert(&mut self, effort: &mut isize);

    /// Merge all batches into a single compacted representation.
    fn consolidate(&mut self);

    /// Insert a new batch of changes into the trace.
    fn insert(&mut self, batch: Self::Batch);
}
```

### 3.2  The Spine — tiered merge tree

The default `Trace` implementation is the **Spine** — a tiered structure of
progressively larger merged batches, conceptually similar to an LSM tree.

```rust
// crates/dbsp/src/trace/spine_async.rs

// line 88
const MAX_LEVELS: usize = 9;

// The spine is "a Trace that internally consists of a vector of batches."
```

```
  Level 0   ▓▓  ▓▓  ▓▓     ← newest, smallest batches (recent deltas)
  Level 1   ▓▓▓▓▓▓▓▓        ← merged from level 0
  Level 2   ▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ← merged from level 1
    ...
  Level 8   ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ← oldest, largest
```

**Background merge tasks** compact levels asynchronously so that the critical
path (processing the next delta) is never blocked by large merges.

### 3.3  Batch types

Each level in the spine stores one or more **Batches** — sorted collections of
`(key, value, time, weight)` tuples.  The batch types from
`crates/dbsp/src/trace/ord/mod.rs`:

- `OrdIndexedWSet` — in-memory sorted arrays
- `FileIndexedWSet` — on-disk sorted (mmap-friendly)
- `FallbackIndexedWSet` — starts in memory, spills to file under pressure

---

## 4  The Circuit Model

*DBSP Paper: §4 "DBSP"*

A DBSP program is a **directed graph** of operators connected by **streams**.

- **Streams** carry Z-sets (one Z-set per clock tick).
- **Operators** transform streams: `map`, `filter`, `join`, `aggregate`, …
- The **Z1** operator provides a one-cycle delay — it outputs the *previous*
  tick's value.  This is how state flows between steps.
- **Nested circuits** handle iterative computations (e.g., recursive queries).

### 4.1  The Z1 delay operator

```rust
// crates/dbsp/src/operator/z1.rs

// line 36-43
pub struct DelayedFeedback<C, D>
where
    C: Circuit,
    D: Data,
{
    connector: FeedbackConnector<C, D, D, Z1<D>>,
}

// line 198+
pub struct Z1<T> {
    prev: T,   // the value from the previous clock tick
}

// line 147 — convenience function
pub fn delay() -> Z1<T> { .. }
```

At each tick, `Z1` outputs whatever it stored from the *previous* tick, then
stores the current input for next time.  This is the fundamental building block
that converts a purely functional dataflow graph into a stateful incremental
pipeline.

### 4.2  Circuit construction

```rust
// crates/dbsp/src/circuit/circuit_builder.rs  — the builder API

// crates/dbsp/src/circuit/dbsp_handle.rs, ~line 270
pub struct DBSPHandle { .. }

// CircuitConfig controls runtime behaviour:
//   - Layout:       worker thread count and topology
//   - max_rss_bytes: memory limit before spilling to disk
//   - pin_cpus:     CPU affinity for worker threads
//   - mode:         execution mode (step / run)
//   - storage:      persistent storage backend configuration
```

### 4.3  The medallion pipeline as a DBSP circuit

The demo's bronze → silver → gold pipeline maps directly onto a DBSP circuit:

```
 ── Bronze inputs (CDC / Kafka) ─────────────────────────────────────────
                                                                         
  bronze_supplier ─Δ──┐                                                  
                      │                                                  
  bronze_part ────Δ──┐│                                                  
                     ││                                                  
  bronze_partsupp Δ──┤│                                                  
                     ││                                                  
                     ▼▼                                                  
 ── Silver operators ────────────────────────────────────────────────────
                                                                         
  ┌──────────┐    ┌─────────────┐    ┌───────────┐                       
  │  filter   │───►│    join     │───►│ aggregate │                      
  │(validate) │    │(supplier ⋈  │    │(SUM cost) │                      
  └──────────┘    │  partsupp)  │    └─────┬─────┘                       
       │          └──────┬──────┘          │                              
       │                 │                 │                              
       │          ┌──────┴──────┐          │                              
       │          │  Z1 (state) │          │                              
       │          │  trace of   │          │                              
       │          │  supplier   │          │                              
       │          └─────────────┘          │                              
       │                                   │                              
       ▼                                   ▼                              
 ── Gold outputs ────────────────────────────────────────────────────────
                                                                         
  silver_supplier ──────────────► gold_agg_supply_cost                   
  (cleaned rows)                  (materialized view)                    
                                                                         
 ────────────────────────────────────────────────────────────────────────
                                                                         
  Legend:                                                                 
    ─Δ──  stream carrying Z-set deltas                                   
    Z1    one-cycle delay operator (stores trace for stateful ops)       
    ⋈     relational join                                                
```

Every arrow is a **stream of Z-sets**.  At each tick, only the *changes* flow
through the graph.  The `Z1` boxes store **traces** so that stateful operators
(joins, aggregations) can look up accumulated state.

---

## 5  Worked Example: A New Supplier Row Arrives

Let us trace a concrete change through the pipeline.

### Step 0 — The change arrives as a Z-set delta

A new row is inserted into the `bronze_supplier` source:

```
Δ(bronze_supplier) = { (supplier_42, +1) }
```

This is a Z-set with a single element and weight `+1`.

### Step 1 — Filter (silver validation)

The `filter` operator checks business rules (e.g., `s_acctbal > 0`).
Because `filter` is **stateless**, it examines only the delta:

```
  Input:  { (supplier_42, +1) }
  Rule:   s_acctbal > 0  →  TRUE
  Output: { (supplier_42, +1) }          -- passes through
```

If the rule had failed, the output would be the empty Z-set `{}`.
Cost: O(|Δi|) = O(1).

### Step 2 — Join (supplier ⋈ partsupp)

The `join` operator is **stateful**.  It must match `supplier_42` against all
`partsupp` rows with the same supplier key.

The Z1 delay feeds the **trace** of previously accumulated `partsupp` rows
into the join operator:

```
  Left input  (new):   Δ(supplier)  = { (supplier_42, +1) }
  Right state (trace):  partsupp    = { (ps_100, +1), (ps_101, +1), ... }

  Join:  supplier_42.s_suppkey = ps.ps_suppkey
         → matches ps_100, ps_101

  Output: { ( (supplier_42, ps_100), +1 ),
            ( (supplier_42, ps_101), +1 ) }
```

The join reads the new delta (1 row) and probes the trace.  It does **not**
re-scan the entire supplier table.

### Step 3 — Aggregate (SUM of supply cost)

The `aggregate` operator is also stateful.  It receives the join output and
updates a running sum.

For a SUM aggregate, the incremental rule is straightforward:

```
  Previous gold_agg_supply_cost for supplier_42's group = $5000
  New contributions: ps_100.cost = $200, ps_101.cost = $350

  Output delta: { (group_key, +$550) }
                     │          │
                     │          └── new total contribution from this delta
                     └──────────── the aggregation group
```

The materialized view `gold_agg_supply_cost` is updated by *adding* this
delta to the previous result — no full re-aggregation needed.

### Step 4 — Output to gold

The final Z-set delta is emitted to the output connector (Kafka sink, HTTP
long-poll, etc.):

```
  gold_agg_supply_cost delta = { (group_key, old_value, -1),
                                  (group_key, new_value, +1) }
```

A deletion of the old aggregate value and an insertion of the new one — the
downstream consumer sees a clean update.

### End-to-end cost

| Stage     | Rows processed | State accessed           |
|-----------|---------------|--------------------------|
| Filter    | 1             | none                     |
| Join      | 1 (probe)     | trace of partsupp        |
| Aggregate | 2 (matches)   | running sum for group    |
| **Total** | **4 rows**    | vs. millions in batch    |

---

## 6  DBSP Paper Section Map

| Paper Section | Topic                          | Codebase Entry Point                          |
|---------------|--------------------------------|-----------------------------------------------|
| §2            | Streams and Z-sets             | `crates/dbsp/src/algebra/zset.rs`             |
| §3            | Incremental computation        | `crates/dbsp/src/operator/z1.rs` (Z1 delay)  |
| §4            | DBSP circuits                  | `crates/dbsp/src/circuit/circuit_builder.rs`  |
| §5            | Nested incremental computation | `crates/dbsp/src/circuit/` (nested circuits)  |
| §6            | Relational operators           | `crates/dbsp/src/operator/` (join, agg, …)   |
| Def 2.1       | Z-set definition               | `ZWeight = i64` in `zset.rs:39`               |
| Thm 3.3       | Incrementalization theorem     | Realized by every `Stream::*` method          |
| Def 4.1       | Delay operator                 | `Z1<T>` struct in `z1.rs:198`                 |

---

## 7  Key Takeaways

1. **Z-sets are the universal data type.**  Every stream, every batch, every
   trace entry is a Z-set — a mapping from elements to integer weights.

2. **Group structure enables incrementality.**  Because Z-sets support addition
   and negation, we can always compute `output_new = output_old + Δoutput`
   without re-reading the full input.

3. **Traces are the cost of statefulness.**  Stateful operators (join, distinct,
   aggregate) store traces — tiered, asynchronously-merged histories of past
   deltas.  Memory pressure is managed by spilling batches to disk via
   `FallbackIndexedWSet`.

4. **Z1 is the bridge between ticks.**  The delay operator is the only way state
   crosses a clock boundary.  Every feedback loop in a DBSP circuit passes
   through exactly one Z1.

5. **The circuit is the program.**  Feldera compiles SQL into a DBSP circuit
   graph.  At runtime, `DBSPHandle` drives the clock: each `step()` pushes one
   Z-set delta through every operator in topological order.

---

*Next: [02-medallion-pipeline.md](./02-medallion-pipeline.md) — how the SQL
demo maps onto this theory.*
