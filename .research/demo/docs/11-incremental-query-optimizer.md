# 11. Incremental Query Optimizer Deep Dive

## Executive Summary

Feldera's incremental query optimizer is the mechanism that transforms a standard "batch" DBSP
circuit — which would recompute everything from scratch each step — into an **incremental**
circuit that processes only changes (deltas). This is the practical realization of the DBSP
paper's core theorem: every query built from Z-set operators has a mechanically derivable
incremental version. The optimizer operates in three major phases: **(1) seed incrementalization**
(insert D/∫ operators), **(2) algebraic simplification** (push ∫ through linear operators,
cancel D∘∫ pairs, upgrade Stream* operators to stateful variants), and **(3) monotonicity
analysis** (prove which columns grow monotonically, then insert GC operators to bound state
growth). The result: a circuit where stateful operators maintain only the state needed to
process the next delta, and old state is garbage-collected when provably safe.

---

## 11.1 Theoretical Foundation — The DBSP Paper

The DBSP paper (Budiu et al., VLDB 2023) establishes two key operators on streams:

| Symbol | Name | Definition | Rust Implementation |
|---|---|---|---|
| **D** | Differentiate | `D[s](t) = s(t) - s(t-1)` | `stream.differentiate()` in `differentiate.rs:38-40`[^1] |
| **∫** | Integrate | `∫[s](t) = Σ_{i≤t} s(i)` | `stream.integrate()` in `integrate.rs:85-125`[^2] |
| **z⁻¹** | Unit delay | `z⁻¹[s](t) = s(t-1)` | `Z1` in `z1.rs:198-392`[^3] |

Key identities used by the optimizer:

1. **D ∘ ∫ = identity** — differentiating an integral recovers the original stream
2. **∫ ∘ D = identity** — integrating a differentiated stream recovers the original
3. **Linear operators commute with D/∫** — if `f` is linear: `f(∫(s)) = ∫(f(s))`
4. **Bilinear operators distribute over ∫** — for join: `join(∫(a), ∫(b))` can be
   incrementalized using traces

### Rust Runtime Implementation

**Integrate** (`integrate.rs:85-125`[^2]) builds a feedback loop:

```
          input
  ┌─────────────────►
  │
  │    ┌───┐ current
  └───►│   ├────────►        ∫(s)(t) = s(t) + z⁻¹(∫(s))(t)
       │ + │                         = s(t) + ∫(s)(t-1)
  ┌───►│   ├────┐
  │    └───┘    │
  │    ┌───┐    │
  └────┤z⁻¹├───┘
       └───┘
```

**Differentiate** (`differentiate.rs:105-122`[^1]) computes `s(t) - z⁻¹(s)(t)`:

```rust
let differentiated = circuit.add_binary_operator(
    Minus::new(),
    &self.try_sharded_version(),
    &self.try_sharded_version().delay_with_initial_value(initial),
);
```

Both operators register their inverse in the circuit cache so that `D(∫(s))` and `∫(D(s))`
are optimized away at the runtime level too[^4].

---

## 11.2 Phase 1 — Seed Incrementalization (`IncrementalizeVisitor`)

**File:** `visitors/outer/IncrementalizeVisitor.java`[^5] (87 lines — one of the simplest passes)

This pass does a straightforward mechanical transformation: wrap every source with ∫ and every
sink with D. The generated circuit is correct but inefficient — optimization follows.

### Rules

| Operator Type | Transformation | Lines |
|---|---|---|
| `SourceMapOperator` (keyed table) | `source → ∫(source)` | 49-51[^5] |
| `SourceMultisetOperator` (non-keyed table) | `source → ∫(source)` | 54-56[^5] |
| `NowOperator` (system clock) | `now → D(now) → ∫(D(now))` | 59-66[^5] |
| `SinkOperator` (view output) | `∫(upstream) → D(∫(upstream)) → sink` | 69-76[^5] |
| `ConstantOperator` (literal data) | `const → D(const) → ∫(D(const))` | 79-86[^5] |
| All other operators | **No change** — passed through unchanged | (inherited from base class) |

### What the Circuit Looks Like After Phase 1

Before:
```
[Source] ──► [Map] ──► [Filter] ──► [Join] ──► [Aggregate] ──► [Sink]
```

After IncrementalizeVisitor:
```
[Source] ──► [∫] ──► [Map] ──► [Filter] ──► [Join] ──► [Aggregate] ──► [D] ──► [Sink]
```

At this point, the circuit is correct but **worst-case**: every interior operator still
receives full collections (the ∫ turned deltas into full collections), and the final D
re-extracts deltas. The `OptimizeIncrementalVisitor` fixes this.

---

## 11.3 Phase 2 — Algebraic Simplification (`OptimizeIncrementalVisitor`)

**File:** `visitors/outer/OptimizeIncrementalVisitor.java`[^6] (293 lines)

This is the workhorse pass. It walks the circuit bottom-up and applies two key transformations:

### Rule A: Push ∫ Through Linear Operators

For any **linear** (homomorphic) operator `f`: `f(∫(s)) = ∫(f(s))`

The optimizer detects when an operator's input is an `IntegrateOperator`, peels off the ∫,
applies the operator to the raw delta stream, then re-wraps with ∫:

```java
// OptimizeIncrementalVisitor.java:67-80
public void linear(DBSPUnaryOperator operator) {
    OutputPort source = this.mapped(operator.input());
    if (source.node().is(DBSPIntegrateOperator.class)) {
        // Replace: operator(∫(x)) → ∫(operator(x))
        DBSPSimpleOperator replace = operator.withInputs(source.node().inputs, true);
        this.addOperator(replace);
        DBSPIntegrateOperator integral = new DBSPIntegrateOperator(..., replace.outputPort());
        this.map(operator, integral);
        return;
    }
    super.postorder(operator);
}
```

**Operators treated as linear** (lines 83-122[^6]):

| Operator | Why Linear |
|---|---|
| `MapOperator` | `map(f, a+b) = map(f,a) + map(f,b)` |
| `FilterOperator` | `filter(p, a+b) = filter(p,a) + filter(p,b)` |
| `FlatMapOperator` | Same as map |
| `NegateOperator` | `neg(a+b) = neg(a) + neg(b)` |
| `MapIndexOperator` | Linear re-keying |
| `DeindexOperator` | Linear un-keying |
| `NoopOperator` | Identity |
| `HopOperator` | Window assignment is linear |
| `ViewOperator` | Transparent wrapper |
| `PartitionedRollingAggregateOperator` | Treated as linear |
| `ChainAggregateOperator` | "Not linear, but behaves like one" (line 115[^6]) |

As ∫ pushes forward, it eventually reaches a **non-linear** (stateful) operator.

### Rule B: Upgrade Stateful Operators

When a non-linear operator has **all inputs wrapped in ∫**, the optimizer strips the ∫
operators, replaces the `Stream*` (batch) operator with its incremental `*` counterpart,
and wraps the output with ∫:

```
Before: StreamJoin(∫(a), ∫(b))
After:  ∫(Join(a, b))
```

| Stream Operator (batch) | Incremental Replacement | Lines |
|---|---|---|
| `StreamJoinOperator` | `JoinOperator` | 125-140[^6] |
| `StreamJoinIndexOperator` | `JoinIndexOperator` | 143-157[^6] |
| `StreamAntiJoinOperator` | `AntiJoinOperator` | 160-172[^6] |
| `StreamDistinctOperator` | `DistinctOperator` | 203-213[^6] |
| `StreamAggregateOperator` | `AggregateOperator` | 216-228[^6] |
| `SumOperator` (all inputs integrated) | `SumOperator` (rewired) | 175-186[^6] |
| `SubtractOperator` (all inputs integrated) | `SubtractOperator` (rewired) | 189-200[^6] |

### Rule C: Cancel D ∘ ∫

When D immediately follows ∫, they cancel (identity):

```java
// OptimizeIncrementalVisitor.java:51-65
public void postorder(DBSPDifferentiateOperator operator) {
    OutputPort source = this.mapped(operator.input());
    if (source.node().is(DBSPIntegrateOperator.class)) {
        // D(∫(x)) = x — bypass both operators
        this.map(operator.outputPort(), integral.input(), false);
        return;
    }
}
```

### Rule D: Nested Circuit Handling

For `DBSPNestedOperator` (recursive queries): if all inputs are integrated, the optimizer
"pushes" integrators through the nested boundary via the `pushIntegrators` set (lines
248-292[^6]).

### Example: Full Optimization of a Filter → Join → Aggregate

```
Initial (after IncrementalizeVisitor):
[Source_A] → [∫] → [Filter] → [StreamJoin] ← [∫] ← [Source_B]
                                    ↓
                            [StreamAggregate]
                                    ↓
                                   [D] → [Sink]

After OptimizeIncrementalVisitor:

Step 1: Push ∫ through Filter (linear)
[Source_A] → [Filter] → [∫] → [StreamJoin] ← [∫] ← [Source_B]

Step 2: All join inputs are ∫ → upgrade to incremental Join
[Source_A] → [Filter] → [Join] ← [Source_B]
                            ↓
                           [∫] → [StreamAggregate]

Step 3: Aggregate input is ∫ → upgrade to incremental Aggregate
[Source_A] → [Filter] → [Join] ← [Source_B]
                            ↓
                        [Aggregate]
                            ↓
                           [∫] → [D] → [Sink]

Step 4: D(∫(x)) = identity → cancel
[Source_A] → [Filter] → [Join] ← [Source_B]
                            ↓
                        [Aggregate]
                            ↓
                          [Sink]
```

The final circuit has **no ∫ or D operators** — every operator processes deltas directly.
Filter and Map operate on deltas (they're linear). Join and Aggregate maintain internal
Traces (state) to process deltas correctly.

---

## 11.4 Cleanup Passes

### `RemoveIAfterD`

**File:** `visitors/outer/RemoveIAfterD.java`[^7] (26 lines)

Catches any leftover `∫(D(x))` patterns that `OptimizeIncrementalVisitor` missed:

```java
public void postorder(DBSPIntegrateOperator operator) {
    OutputPort source = this.mapped(operator.input());
    if (source.node().is(DBSPDifferentiateOperator.class)) {
        this.map(operator.outputPort(), integral.input(), false);
    }
}
```

### `NoIntegralVisitor`

**File:** `visitors/outer/NoIntegralVisitor.java`[^8] (45 lines)

A **sanity check** that runs after all optimization. If any `IntegrateOperator` survives in
the circuit, it throws `InternalCompilerError`. This guarantees that the optimizer fully
resolved all D/∫ pairs (except in legitimate cases like recursive queries, which are handled
separately).

```java
public VisitDecision preorder(DBSPIntegrateOperator node) {
    throw new InternalCompilerError("Circuit contains an integration operator", node);
}
```

### `DeadCode`

Runs three times during optimization (lines 84, 101, 111 of `CircuitOptimizer.java`[^9]).
After D/∫ cancellation, some operators become unreachable and are pruned.

---

## 11.5 Stream* vs Incremental Operator Semantics

The compiler maintains two families of operators:

| Property | `Stream*` (batch/non-incremental) | Incremental |
|---|---|---|
| **Interface marker** | `INonIncremental` | `IIncremental` |
| **Input semantics** | Processes the **current delta** batch | Processes **delta** but maintains **Trace** (accumulated state) |
| **State** | Stateless | Stateful (Trace = Spine of historical batches) |
| **Examples** | `StreamJoinOperator`, `StreamDistinctOperator`, `StreamAggregateOperator` | `JoinOperator`, `DistinctOperator`, `AggregateOperator` |
| **When used** | Before incrementalization, or in nested stream contexts | After `OptimizeIncrementalVisitor` upgrades them |

### How Incremental Operators Use Traces (Rust Runtime)

In the Rust runtime, incremental operators maintain **Traces** — the accumulated history of
all past deltas, stored in a Spine (tiered merge tree of sorted batches)[^10].

**Incremental Join** (`operator/join.rs:343-374`[^11]):
- Maintains Traces for both left and right inputs
- On receiving delta `Δleft` at step `t`:
  - Joins `Δleft` with `Trace_right` (all historical right data)
  - Joins `Trace_left` with `Δright` (all historical left data)
  - Joins `Δleft` with `Δright` (current step data)
  - Output = sum of all three
- This is the **delta-join** rule from DBSP paper §3.2

**Incremental Aggregate** (`operator/aggregate.rs:65-168`[^12]):
- Maintains Trace of `(key, value)` pairs
- On delta: updates running aggregate per key using the Trace
- Linear aggregates (SUM, COUNT) can be updated without re-scanning the full group

**Incremental Distinct** (`operator/dynamic/distinct.rs:310-350`[^13]):
- Maintains Trace of element weights
- On delta with weight change: emits +1 if element goes from 0→positive, -1 if positive→0

---

## 11.6 Phase 3 — Monotonicity Analysis

Without bounds, Traces grow forever — every historical delta is accumulated. Monotonicity
analysis determines which columns grow monotonically (e.g., timestamps), enabling the
optimizer to insert GC operators that prune old state.

### Architecture

**File:** `visitors/outer/monotonicity/MonotoneAnalyzer.java`[^14] (171 lines)

```
MonotoneAnalyzer.apply(circuit)
    │
    ├─ 1. EnsureTree: normalize expressions to tree form
    │
    ├─ 2. SeparateIntegrators: insert noop buffers between
    │     consecutive integrators (so retain scopes are explicit)
    │
    ├─ 3. AppendOnly: identify append-only relations
    │
    ├─ 4. KeyPropagation: track primary/foreign key usage through joins
    │
    ├─ 5. DeltaExpandOperators: expand circuit for analysis
    │     (creates a "shadow" circuit where each operator is duplicated
    │      to track which outputs are monotone)
    │
    ├─ 6. Monotonicity: analyze expanded circuit
    │     → produces MonotonicityInformation (per-output-port)
    │
    ├─ 7. InsertLimiters: insert GC operators into ORIGINAL circuit
    │     based on monotonicity results from expanded circuit
    │
    ├─ 8. MergeGC: deduplicate equivalent GC chains
    │
    └─ 9. CheckRetain: verify no operator has conflicting retain policies
```

### Monotonicity Transfer Functions

**File:** `visitors/outer/monotonicity/Monotonicity.java`[^15] (858+ lines)

For each operator type, a transfer function computes output monotonicity from input
monotonicity:

| Operator | Transfer Rule | Lines |
|---|---|---|
| **Source** (with LATENESS) | Columns with `LATENESS` annotation are monotone | 245-255[^15] |
| **Filter** | Preserves input monotonicity; comparisons with monotone columns may strengthen bounds | 730-772[^15] |
| **Map / FlatMap** | Analyze closure via `MonotoneTransferFunctions`; output column is monotone if it depends only on monotone input columns through monotone functions | 473-500[^15] |
| **Join** | Combine left/right monotonicity; key monotonicity propagates | 406-439[^15] |
| **Aggregate** | Only key columns propagate monotonicity (aggregate values may not be monotone) | 798-829[^15] |
| **Distinct** | Identity — preserves monotonicity | 552-554[^15] |
| **Union / Sum / Subtract** | Intersection of input monotonicities (a column is monotone only if monotone in ALL inputs) | 503-521[^15] |
| **Window / Lag** | Identity-like on left input | 583-590[^15] |

### Data Structures

- **`IMaybeMonotoneType`** (`visitors/monotone/IMaybeMonotoneType.java:11-43`[^16]) — interface
  for monotonicity information at the type level
- **`MonotoneType`** — a scalar type that IS monotone (`visitors/monotone/MonotoneType.java:8-53`[^17])
- **`PartiallyMonotoneTuple`** — tracks per-column monotonicity for tuple types
  (`visitors/monotone/PartiallyMonotoneTuple.java:18-152`[^18])
- **`MonotoneExpression`** — `(original_expr, monotonicity_type, reduced_expr)` triple
  (`visitors/monotone/MonotoneExpression.java:10-57`[^19])
- **`MonotonicityInformation`** — maps `OutputPort → MonotoneExpression` for the entire circuit
  (`Monotonicity.java:103-144`[^15])

### How LATENESS Drives Monotonicity

When a user writes:

```sql
CREATE TABLE events (
    event_time TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOUR,
    ...
);
```

The compiler:

1. Records `event_time` as having lateness in the table metadata
2. `Monotonicity` analysis marks `event_time` as a monotone column (lines 245-255[^15])
3. Monotonicity propagates through downstream operators via transfer functions
4. `InsertLimiters` uses the propagated bounds to insert GC operators

### The `NOW` Table

The virtual `NOW` table (auto-created at `DBSPCompiler.java:240-242`[^20]) is a special case:
its timestamp column is always monotone. `ChainAggregateOperator` with `AlwaysMonotone`
annotation is treated as having lateness 0 (lines 842-858[^15]).

---

## 11.7 State Bounding — GC Operators

Once monotonicity analysis proves a column is monotone, `InsertLimiters` inserts operators that
bound state growth:

### `DBSPWaterlineOperator`

**File:** `circuit/operator/DBSPWaterlineOperator.java:19-39`[^21]

Computes a running minimum/maximum of a monotone column: `waterline(t) = min(extractTs(input(t)),
z⁻¹(waterline(t-1)))`. This produces a **control stream** that represents "the oldest data
that could still arrive."

### `DBSPControlledKeyFilterOperator`

**File:** `circuit/operator/DBSPControlledKeyFilterOperator.java:32-52`[^22]

A two-input operator: data stream + control stream. Drops tuples whose key is below the
control waterline. Used to:
- Filter late-arriving data at input
- Prune old entries from Traces

### `DBSPIntegrateTraceRetainKeysOperator`

**File:** `circuit/operator/DBSPIntegrateTraceRetainKeysOperator.java:42-87`[^23]

Attaches to an integrator/trace and tells it: "you may discard any entries with keys below
this bound." The bound comes from a monotone key expression projected through the circuit.

### `DBSPIntegrateTraceRetainValuesOperator`

**File:** `circuit/operator/DBSPIntegrateTraceRetainValuesOperator.java:45-70`[^24]

Same concept, but for value columns instead of key columns.

### `LinearPostprocessRetainKeys`

**File:** `visitors/outer/LinearPostprocessRetainKeys.java:17-102`[^25]

Fuses `AggregateLinearPostprocess → IntegrateTraceRetainKeys` into a single operator, adding
a graph edge from the retain-keys waterline back to the aggregate to preserve topological order.

### How Limiters Wire Into the Circuit

`InsertLimiters.processLateness()` (lines 1623-1710[^26]) creates:

```
[Source] ──────────────────────────► [data stream]
    │
    ├── extractTs() ──► [WaterlineOp] ──► [z⁻¹] ──► [ControlledKeyFilter] ──► [filtered data]
    │                                                        ▲
    │                                                        │
    └── (original data) ─────────────────────────────────────┘
```

For each downstream stateful operator (join/aggregate/distinct), a
`IntegrateTraceRetainKeysOperator` is inserted that receives the propagated waterline and
prunes the Trace.

---

## 11.8 The `isMultiset` Flag

Every `DBSPSimpleOperator` carries an `isMultiset` flag (`DBSPSimpleOperator.java:49-51`[^27])
indicating whether the output may contain duplicate elements.

**Impact on incrementalization:**

1. **DISTINCT elimination**: if `isMultiset = false`, `OptimizeDistinctVisitor`
   (`OptimizeDistinctVisitor.java:39-133`[^28]) removes redundant DISTINCT operators:
   - `distinct(distinct(x)) = distinct(x)` (idempotence)
   - `distinct(x)` where `x.isMultiset = false` → removed entirely
   - `filter(distinct(x))` → `distinct(filter(x))` (push distinct up)

2. **Join optimization**: joins of two non-multiset inputs may skip duplicate tracking

3. **Propagation**: the flag propagates through the circuit — a `FilterOperator` on a
   non-multiset input produces non-multiset output.

---

## 11.9 Optimization Pass Ordering in `CircuitOptimizer`

The incrementalization-related passes execute in this specific order within
`CircuitOptimizer.java:72-175`[^9]:

```
1.  [Pre-incrementalization passes: ImplementNow, DeadCode, CSE, etc.]

2.  OptimizeDistinctVisitor          ← Remove redundant DISTINCT
3.  OptimizeIncrementalVisitor        ← Pre-pass: simplify before incrementalization
4.  DeadCode                          ← Clean up

5.  ★ IncrementalizeVisitor ★         ← THE CORE: insert D/∫ operators
    (only if options.languageOptions.incrementalize is true)

6.  OptimizeIncrementalVisitor        ← Push ∫, upgrade operators, cancel D∘∫
7.  RemoveIAfterD                     ← Catch leftover ∫∘D pairs
8.  DeadCode                          ← Remove dead operators

9.  [Expression simplification: Simplify, RemoveConstantFilters]

10. ShareIndexes                      ← Share MapIndex operators
11. FilterJoinVisitor                 ← Push filters into joins (improves monotonicity)
12. ★ MonotoneAnalyzer ★              ← Monotonicity analysis + GC insertion

13. [Post-monotonicity: CloneOperators, ExpandIndexedInputs, etc.]

14. NoIntegralVisitor                 ← Sanity check: no ∫ should remain
    (only if options.languageOptions.incrementalize is true)

15. [Final lowering: ExpandHop, ImplementJoins, LowerCircuitVisitor, etc.]
```

Note that `OptimizeIncrementalVisitor` runs **twice** (lines 104 and 109[^9]): once before
incrementalization (to optimize any pre-existing incremental patterns) and once after (to
optimize the patterns created by `IncrementalizeVisitor`).

---

## 11.10 Concrete Example — Demo's `gold_cancellation_impact`

### The SQL

```sql
CREATE VIEW gold_cancellation_impact AS
SELECT
    p.category,
    DATE_TRUNC('week', o.order_date) AS week,
    COUNT(*) FILTER (WHERE o.status = 'cancelled') AS cancelled_orders,
    COUNT(*) AS total_orders,
    ...
    SUM(SUM(oi.quantity * oi.unit_price) FILTER (WHERE o.status = 'cancelled'))
        OVER (PARTITION BY p.category ORDER BY DATE_TRUNC('week', o.order_date)
              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
        AS cumulative_lost_revenue
FROM bronze_orders o
JOIN bronze_order_items oi ON o.order_id = oi.order_id
JOIN bronze_products p ON oi.product_id = p.product_id
GROUP BY p.category, DATE_TRUNC('week', o.order_date);
```

### Batch Circuit (Before Incrementalization)

```
[orders]──►[StreamJoin]◄──[items]
                │
           [StreamJoin]◄──[products]
                │
           [MapIndex: key=(category, week)]
                │
           [StreamAggregate: COUNT(*), SUM(...)]
                │
           [Map: flatten]
                │
           [Window: cumulative SUM]
                │
           [Sink]
```

### After IncrementalizeVisitor

```
[orders]──►[∫]──►[StreamJoin]◄──[∫]◄──[items]
                      │
                 [StreamJoin]◄──[∫]◄──[products]
                      │
                 [MapIndex]──►[StreamAggregate]──►[Map]──►[Window]──►[D]──►[Sink]
```

### After OptimizeIncrementalVisitor

```
[orders]──►[Join]◄──[items]          (∫ consumed by Join — stateful, uses Traces)
               │
          [Join]◄──[products]        (∫ consumed by Join)
               │
          [MapIndex]──►[Aggregate]   (∫ consumed by Aggregate — stateful, uses Trace)
               │
          [Map]──►[Window]           (linear: pushed through)
               │
          [Sink]                     (D∘∫ cancelled)
```

**Result**: 3 stateful operators (2 Joins + 1 Aggregate), each maintaining a Trace.
All other operators are stateless and process only the current delta. No D or ∫ remain.

### After MonotoneAnalyzer

If `order_date` has a `LATENESS` annotation:
- The `week` column (derived from `order_date`) is proven monotone
- `IntegrateTraceRetainKeysOperator` is inserted on each Join's trace and on the Aggregate's
  trace, bounded by the propagated week waterline
- Old state for weeks that can no longer receive updates is garbage-collected

---

## 11.11 Comparison with Other Systems

| Aspect | Feldera | Spark Structured Streaming | Flink |
|---|---|---|---|
| **Incrementalization** | Automatic via DBSP theory | Manual (mapGroupsWithState) | Manual (ProcessFunction) |
| **State management** | Trace (Spine) with automatic GC | RocksDB with TTL | RocksDB with TTL |
| **Optimizer** | 45-pass compiler | Catalyst (batch-focused) | Cost-based (batch-focused) |
| **D/∫ theory** | First-class operators in IR | N/A | N/A |
| **Monotonicity** | Automatic per-column analysis | Manual watermarks | Manual watermarks |
| **State GC** | Compiler-inserted retain operators | Manual TTL configuration | Manual TTL / timer-based |

---

## 11.12 Key Files Summary

| File | Purpose | Lines |
|---|---|---|
| `IncrementalizeVisitor.java` | Insert D/∫ operators | 87 |
| `OptimizeIncrementalVisitor.java` | Push ∫, upgrade operators, cancel D∘∫ | 293 |
| `RemoveIAfterD.java` | Catch leftover ∫∘D pairs | 26 |
| `NoIntegralVisitor.java` | Sanity check: no ∫ should remain | 45 |
| `OptimizeDistinctVisitor.java` | Remove redundant DISTINCT | 133 |
| `MonotoneAnalyzer.java` | Orchestrate monotonicity analysis + GC | 171 |
| `Monotonicity.java` | Per-operator monotonicity transfer functions | 858+ |
| `InsertLimiters.java` | Insert GC operators based on monotonicity | 1821+ |
| `SeparateIntegrators.java` | Isolate integrators for GC scoping | 120 |
| `MergeGC.java` | Deduplicate GC chains | — |
| `KeyPropagation.java` | Track PK/FK through joins | — |
| `integrate.rs` | Rust ∫ implementation | 125 |
| `differentiate.rs` | Rust D implementation | 123 |
| `z1.rs` | Rust z⁻¹ (unit delay) implementation | 392 |
| `join.rs` | Incremental join with Traces | 374 |
| `aggregate.rs` | Incremental aggregate with Traces | 168 |
| `distinct.rs` | Incremental distinct with Traces | 350 |

---

## Confidence Assessment

| Aspect | Confidence | Notes |
|---|---|---|
| IncrementalizeVisitor mechanics | ★★★★★ | Read the complete 87-line file |
| OptimizeIncrementalVisitor rules | ★★★★★ | Read the complete 293-line file |
| D/∫ cancellation rules | ★★★★★ | Verified in source + Rust runtime |
| Rust runtime D/∫/z⁻¹ | ★★★★★ | Read integrate.rs, differentiate.rs, z1.rs |
| Monotonicity transfer functions | ★★★★☆ | Verified key rules; some complex cases simplified |
| InsertLimiters wiring | ★★★★☆ | Read key methods; full file is 1800+ lines |
| Demo-specific example | ★★★☆☆ | Conceptual walkthrough; actual operator count may differ |

---

## Footnotes

[^1]: `crates/dbsp/src/operator/differentiate.rs:38-40, 105-122`
[^2]: `crates/dbsp/src/operator/integrate.rs:85-125`
[^3]: `crates/dbsp/src/operator/z1.rs:198-392`
[^4]: `integrate.rs:119-121` caches `DifferentiateId → self`; `differentiate.rs:117-118` caches `IntegralId → self`
[^5]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/IncrementalizeVisitor.java`
[^6]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/OptimizeIncrementalVisitor.java`
[^7]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/RemoveIAfterD.java`
[^8]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/NoIntegralVisitor.java`
[^9]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/CircuitOptimizer.java:72-175`
[^10]: See `06-state-and-storage.md` §6.1 for Spine/Trace details
[^11]: `crates/dbsp/src/operator/join.rs:343-374`
[^12]: `crates/dbsp/src/operator/aggregate.rs:65-168`
[^13]: `crates/dbsp/src/operator/dynamic/distinct.rs:310-350`
[^14]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/monotonicity/MonotoneAnalyzer.java:92-171`
[^15]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/monotonicity/Monotonicity.java`
[^16]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/monotone/IMaybeMonotoneType.java:11-43`
[^17]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/monotone/MonotoneType.java:8-53`
[^18]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/monotone/PartiallyMonotoneTuple.java:18-152`
[^19]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/monotone/MonotoneExpression.java:10-57`
[^20]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/DBSPCompiler.java:240-242`
[^21]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/DBSPWaterlineOperator.java:19-39`
[^22]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/DBSPControlledKeyFilterOperator.java:32-52`
[^23]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/DBSPIntegrateTraceRetainKeysOperator.java:42-87`
[^24]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/DBSPIntegrateTraceRetainValuesOperator.java:45-70`
[^25]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/LinearPostprocessRetainKeys.java:17-102`
[^26]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/monotonicity/InsertLimiters.java:1623-1710`
[^27]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/DBSPSimpleOperator.java:49-51`
[^28]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/OptimizeDistinctVisitor.java:39-133`
