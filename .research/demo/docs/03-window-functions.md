# Window Functions: LAG, Moving Average, and Cumulative Sum

> **Prerequisite**: You understand Z-sets (`{element → weight}`) and how Feldera
> processes deltas incrementally. This document explains how *window functions*
> — LAG, moving averages, and cumulative sums — work under the hood.

---

## 1. Window Functions in the Medallion Pipeline

The gold-layer views in the medallion demo use three distinct window patterns:

```sql
-- gold_weekly_revenue_trend
LAG(weekly_net_revenue, 1)
  OVER (PARTITION BY category ORDER BY week_start)

AVG(weekly_net_revenue)
  OVER (PARTITION BY category ORDER BY week_start
        RANGE BETWEEN INTERVAL 3 WEEKS PRECEDING AND CURRENT ROW)

-- gold_cancellation_impact
SUM(weekly_net_revenue)
  OVER (PARTITION BY category, EXTRACT(YEAR FROM week_start)
        ORDER BY week_start RANGE UNBOUNDED PRECEDING)
```

Each compiles to a different DBSP operator:

| SQL Pattern | DBSP Operator | Source |
|---|---|---|
| `LAG(expr, N)` | `lag()` / `lag_custom_order()` | `crates/dbsp/src/operator/group/lag.rs` |
| `AVG(...) OVER (RANGE BETWEEN ...)` | `partitioned_rolling_aggregate()` | `crates/dbsp/src/operator/time_series/rolling_aggregate.rs` |
| `SUM(...) OVER (RANGE UNBOUNDED PRECEDING)` | `partitioned_rolling_aggregate()` with unbounded range | same file |

All three share the same core principle: **maintain a Trace** (the integrated
history of all past deltas), and when a new delta arrives, emit only the
*changes* to the window function's output as a Z-set delta.

---

## 2. How LAG Works Incrementally

### 2.1 The Interface

From `crates/dbsp/src/operator/group/lag.rs` (lines 37–66):

```rust
pub fn lag<VL, PF>(
    &self,
    offset: isize,
    project: PF,
) -> Stream<RootCircuit, TypedBatch<B::Key, Tup2<B::Val, VL>, ZWeight, ...>>
where
    VL: DBData,
    PF: Fn(Option<&B::Val>) -> VL + 'static,
```

- **`offset`** — how many rows back (positive) or forward (negative, i.e. LEAD).
- **`project`** — extracts the desired column from the lagged row, or returns
  a default when the offset reaches beyond the partition boundary.
- The operator is generic over `IndexedZSet`, where `B::Key` is the partition
  key and `B::Val` is the order key (plus payload).

For `LAG(weekly_net_revenue, 1) OVER (PARTITION BY category ORDER BY week_start)`:
- `B::Key` = `category`
- `B::Val` = `(week_start, weekly_net_revenue, ...)`
- `offset` = `1`
- `project` = extract `weekly_net_revenue` from the lagged row, or `NULL`

### 2.2 State Model

```
                         Trace (per partition key)
                    ┌──────────────────────────────────┐
  category =       │  Sorted values (by week_start):   │
  "Electronics"    │                                    │
                   │  week_1  week_2  week_3  week_4   │
                   │  $100    $150    $120    $200      │
                   │  (w:+1)  (w:+1)  (w:+1)  (w:+1)  │
                   └──────────────────────────────────────┘

                         Output Trace
                   ┌──────────────────────────────────────┐
                   │  (week_1, NULL)    w:+1              │
                   │  (week_2, $100)    w:+1              │
                   │  (week_3, $150)    w:+1              │
                   │  (week_4, $120)    w:+1              │
                   └──────────────────────────────────────┘
```

The `Lag` struct maintains per-invocation state (`crates/dbsp/src/operator/dynamic/group/lag.rs`,
line 190):

```rust
struct Lag<I: DataTrait + ?Sized, O: DataTrait + ?Sized, KCF> {
    lag: usize,
    asc: bool,                              // true for LAG, false for LEAD
    project: Box<dyn Fn(Option<&I>, &mut O)>,
    affected_keys: Box<DynVec<I>>,          // keys needing re-evaluation
    encountered_keys: Vec<EncounteredKey>,   // per-key offset/weight state
    key_cmp: KCF,                           // ordering function
    // ...
}
```

### 2.3 The Two-Pass Algorithm

The `transform()` method (line 625) receives three cursors:

1. **`input_delta`** — new changes (this step's Z-set delta)
2. **`input_trace`** — integrated history of all past inputs
3. **`output_trace`** — integrated history of all past outputs

It runs two passes:

```
  FORWARD PASS  ──────────────────────────────────────────────────►
  Walk input_delta + output_trace in ascending order.
  For each changed key:
    1. Retract its old output: emit (old_pair, -1) for each existing entry
    2. Mark the next `lag` keys as "affected" — they also need recomputation
    3. Retract affected keys' old outputs too

  BACKWARD PASS  ◄──────────────────────────────────────────────────
  Walk input_trace in descending order.
  For each affected key:
    1. Step backward `offset` positions in the integrated trace
    2. Apply `project` to the lagged row
    3. Emit (key, projected_value, +1)
```

### 2.4 Concrete Example: New Week Arrives

Suppose week_5 arrives for "Electronics" with `$180` revenue:

```
  Input delta:   { ("Electronics", week_5, $180) → +1 }

  ── FORWARD PASS ──
  Scan output_trace for "Electronics":
    week_5 is new (not in output_trace), so no old value to retract.
    Mark next LAG(1) keys as affected: none exist after week_5.
    affected_keys = [week_5]

  ── BACKWARD PASS ──
  Position cursor at end of input_trace for "Electronics":
    ... week_3  week_4  week_5
                         ▲ cursor starts here
    Step back 1 position → cursor at week_4 ($200)
    project(Some(week_4)) → $200

  Output delta:
    { ("Electronics", (week_5, $200)) → +1 }
```

Now suppose week_3 is *corrected* from `$120` to `$130`:

```
  Input delta: { ("Electronics", week_3, $120) → -1,
                 ("Electronics", week_3, $130) → +1 }

  ── FORWARD PASS ──
  week_3 changed → retract old output for week_3:
    emit ("Electronics", (week_3, $150)) → -1     [old lag was week_2=$150]
  Mark next 1 key as affected: week_4
    retract old output for week_4:
    emit ("Electronics", (week_4, $120)) → -1     [old lag was week_3=$120]
  affected_keys = [week_3, week_4]

  ── BACKWARD PASS ──
  For week_4: step back 1 from week_4 → week_3 (now $130)
    emit ("Electronics", (week_4, $130)) → +1
  For week_3: step back 1 from week_3 → week_2 ($150, unchanged)
    emit ("Electronics", (week_3, $150)) → +1

  Net output delta:
    { ("Electronics", (week_3, $150)) → -1 + 1 = 0   (cancels out!)
      ("Electronics", (week_4, $120)) → -1             (retract old)
      ("Electronics", (week_4, $130)) → +1  }          (insert new)
```

The key insight: **changing one row propagates at most `offset` positions
forward**, so LAG(1) disturbs at most 2 output rows (the changed row + 1).

### 2.5 ASCII Diagram: LAG Data Flow

```
                              ┌───────────────┐
  input_delta ───────────────►│               │
  (new/changed rows)          │   dyn_lag     │──────► output_delta
                              │               │        Z-set of
            ┌──────┐          │  ┌──────────┐ │        (key, (val, lag_val))
  ──────────┤ z^-1 │◄─────┐  │  │ Lag struct│ │
  │         └──────┘      │  │  │           │ │
  │  input_trace          │  │  │ Two-pass  │ │
  │  (sorted history) ────┼─►│  │ algorithm │ │
  │                       │  │  └──────────┘ │
  │                       │  └───────────────┘
  │                       │          │
  │                       │          ▼
  │                       │   ┌──────────────┐
  │                       │   │  TraceAppend │
  │                       │   └──────┬───────┘
  │                       │          │
  │                       │   ┌──────┴──┐
  │                       └───┤  z^-1   │ output_trace
  │                           └─────────┘ (sorted history of
  │                                        past outputs)
  │
  ▼ (input_trace also feeds the backward pass)
```

Each `z^-1` is a unit-delay element that stores the running integration (trace)
across clock ticks. The `Lag` struct reads from both the input trace and
the output trace during the forward pass, then reads from the input trace
during the backward pass.

---

## 3. Rolling Window Aggregates (Moving Average)

### 3.1 The Operator

From `crates/dbsp/src/operator/time_series/rolling_aggregate.rs` (line 160):

```rust
pub fn partitioned_rolling_aggregate<PK, OV, Agg, PF>(
    &self,
    partition_func: PF,
    aggregator: Agg,
    range: RelRange<TS>,
) -> OrdPartitionedOverStream<PK, TS, Agg::Output>
```

For `AVG(...) OVER (PARTITION BY category ORDER BY week_start RANGE BETWEEN
INTERVAL 3 WEEKS PRECEDING AND CURRENT ROW)`:

- `PK` = `category`
- `TS` = `week_start` (timestamp type, implements `UnsignedPrimInt`)
- `range` = `RelRange { from: -3_weeks, to: 0 }` (relative to current row)
- `aggregator` = averaging aggregator

### 3.2 How It Works

For each input record `(partition, (ts, value))`, the operator:

1. Looks up all records in the same partition where `ts - 3_weeks <= ts' <= ts`
2. Applies the aggregator across those records
3. Outputs `(partition, (ts, aggregate_result))`

**Incrementally**, when a new delta arrives:

```
  Input trace (integrated):                  Sliding window for week_5:
  ┌─────────────────────────────────┐        ┌──────────────────────┐
  │ category="Electronics"          │        │  week_2  week_3      │
  │                                 │        │  week_4  week_5      │
  │ week_1=$100  week_2=$150        │        │                      │
  │ week_3=$120  week_4=$200        │        │  AVG = ($150 + $120  │
  │ week_5=$180  (NEW)              │        │    + $200 + $180)/4  │
  └─────────────────────────────────┘        │       = $162.50      │
                                             └──────────────────────┘
                                             (week_1 falls outside
                                              the 3-week window)
```

### 3.3 The Delta Output Pattern

When week_5 arrives, the operator must re-evaluate the window for *every
existing row whose window now includes the new data point*. For a 3-week
window, inserting `week_5` can affect windows at `week_5`, `week_6`,
`week_7`, and `week_8` (if they exist). In our case, only `week_5` is new:

```
  Output delta for week_5 arrival:
  ┌──────────────────────────────────────────────────────┐
  │ ("Electronics", (week_5, Some($162.50)))  → +1       │  new result
  └──────────────────────────────────────────────────────┘
```

But what if an **existing row is corrected**? Say week_3 changes from `$120`
to `$130`. Now windows at week_3, week_4, and week_5 (and week_6 if it
existed) all shift:

```
  Output delta for week_3 correction ($120 → $130):
  ┌──────────────────────────────────────────────────────┐
  │ ("Electronics", (week_3, Some($old_avg)))  → -1      │  retract old
  │ ("Electronics", (week_3, Some($new_avg)))  → +1      │  insert new
  │ ("Electronics", (week_4, Some($old_avg)))  → -1      │  retract old
  │ ("Electronics", (week_4, Some($new_avg)))  → +1      │  insert new
  │ ("Electronics", (week_5, Some($old_avg)))  → -1      │  retract old
  │ ("Electronics", (week_5, Some($new_avg)))  → +1      │  insert new
  └──────────────────────────────────────────────────────┘
```

This is the universal pattern: **retract-then-insert for every affected row**.

### 3.4 Rolling Window Data Flow

```
                    ┌─────────────────────────────────────┐
                    │  partitioned_rolling_aggregate       │
                    │                                     │
  self ────────────►│  ┌───────────────────┐              │
  (indexed Z-set    │  │  partition_func   │              │
   by timestamp)    │  │  splits into      │              │
                    │  │  (PK, (TS, V))    │              │
                    │  └────────┬──────────┘              │
                    │           │                         │
                    │           ▼                         │
                    │  ┌────────────────────┐             │
                    │  │  For each (pk, ts) │             │
                    │  │  find all ts' in   │             │
                    │  │  [ts-range, ts]    │──────────────┼──► output delta
                    │  │                    │             │    (pk, (ts, agg))
                    │  │  apply aggregator  │             │
                    │  └────────────────────┘             │
                    │           ▲                         │
                    │           │ reads from              │
                    │     ┌─────┴─────┐                   │
                    │     │   Trace   │                   │
                    │     │ (sorted   │                   │
                    │     │  history) │                   │
                    │     └───────────┘                   │
                    └─────────────────────────────────────┘

  The trace is built from z^-1 (unit delay) + TraceAppend,
  identical in structure to the lag operator's trace.
```

### 3.5 Linear Optimization

When the aggregate function is *linear* (like SUM), the operator can exploit
the `partitioned_rolling_aggregate_linear` variant (line 231). A linear
function satisfies `f(a + b) = f(a) + f(b)`, which means the operator can
compute the new aggregate by adjusting the old one:

```
  new_sum = old_sum + entering_values - leaving_values
```

instead of recomputing from scratch. AVG is computed via
`partitioned_rolling_average` (line 275), which internally tracks both the
sum and count to compute the quotient.

---

## 4. Cumulative Sum (RANGE UNBOUNDED PRECEDING)

### 4.1 Why It Is a Special Case

```sql
SUM(weekly_net_revenue)
  OVER (PARTITION BY category, EXTRACT(YEAR FROM week_start)
        ORDER BY week_start
        RANGE UNBOUNDED PRECEDING)
```

`RANGE UNBOUNDED PRECEDING` means "from the beginning of the partition to the
current row." The window **never shrinks** — it only grows. This simplifies
the incremental computation:

- No values ever *leave* the window (nothing to subtract)
- A new row contributes to its own cumulative sum *and* every future row's sum

### 4.2 Incremental Behavior

```
  Partition: ("Electronics", 2024)

  Input trace (sorted by week_start):
    week_1=$100   week_2=$150   week_3=$120   week_4=$200

  Cumulative sums:
    week_1: $100
    week_2: $100 + $150 = $250
    week_3: $100 + $150 + $120 = $370
    week_4: $100 + $150 + $120 + $200 = $570
```

When `week_5 = $180` arrives:

```
  Output delta:
    (("Electronics", 2024), (week_5, Some($750)))  → +1

  Only one new output row — existing cumulative sums are unchanged
  because no old data was modified.
```

When `week_3` is corrected from `$120` to `$130` (a +$10 change), every
row from week_3 onward shifts by +$10:

```
  Output delta:
    (("Electronics", 2024), (week_3, Some($370)))  → -1   retract old
    (("Electronics", 2024), (week_3, Some($380)))  → +1   insert new
    (("Electronics", 2024), (week_4, Some($570)))  → -1   retract old
    (("Electronics", 2024), (week_4, Some($580)))  → +1   insert new
    (("Electronics", 2024), (week_5, Some($750)))  → -1   retract old
    (("Electronics", 2024), (week_5, Some($760)))  → +1   insert new
```

The correction cascades through *all subsequent rows* in the partition.

---

## 5. End-to-End Walkthrough: `gold_weekly_revenue_trend`

Let us trace a single new delta through the full pipeline.

### Step 0: Setup

The inner `GROUP BY` produces weekly aggregates. Suppose it emits:

```
  Delta from GROUP BY:
    { ("Electronics", week_5, $180_net, 12_orders, 2_cancels) → +1 }
```

### Step 1: LAG(weekly_net_revenue, 1)

```
  ┌─ LAG operator state ──────────────────────────────────────────┐
  │                                                                │
  │  Input trace (partition = "Electronics"):                      │
  │    week_1: ($100, 10, 1)   ← (net_rev, orders, cancels)      │
  │    week_2: ($150, 15, 0)                                       │
  │    week_3: ($120, 8, 3)                                        │
  │    week_4: ($200, 20, 1)                                       │
  │    week_5: ($180, 12, 2)   ← NEW (from delta)                 │
  │                                                                │
  │  Forward pass:                                                 │
  │    week_5 is new → no old output to retract                    │
  │    Mark next 1 key: none after week_5                          │
  │    affected_keys = [week_5]                                    │
  │                                                                │
  │  Backward pass:                                                │
  │    For week_5: step back 1 → week_4 → project($200)           │
  │                                                                │
  │  Output delta:                                                 │
  │    { ("Electronics", (week_5, $200)) → +1 }                   │
  │                     (current, lagged_revenue)                  │
  └────────────────────────────────────────────────────────────────┘
```

### Step 2: 4-Week Moving Average

```
  ┌─ Rolling AVG operator state ───────────────────────────────────┐
  │                                                                │
  │  Window for week_5 (RANGE 3 WEEKS PRECEDING to CURRENT):      │
  │    week_2=$150, week_3=$120, week_4=$200, week_5=$180          │
  │    AVG = ($150 + $120 + $200 + $180) / 4 = $162.50            │
  │                                                                │
  │  Output delta:                                                 │
  │    { ("Electronics", (week_5, Some($162.50))) → +1 }          │
  └────────────────────────────────────────────────────────────────┘
```

### Step 3: Cumulative YTD Sum

```
  ┌─ Cumulative SUM operator state ────────────────────────────────┐
  │                                                                │
  │  Partition = ("Electronics", 2024)                             │
  │  Running total through week_4: $570                            │
  │  New: $570 + $180 = $750                                       │
  │                                                                │
  │  Output delta:                                                 │
  │    { (("Electronics", 2024), (week_5, Some($750))) → +1 }     │
  └────────────────────────────────────────────────────────────────┘
```

### Final Assembled Output

The downstream SELECT combines these columns into the final
`gold_weekly_revenue_trend` row:

```
  { ("Electronics", week_5, $180, $200, $162.50, $750, ...) → +1 }
                     │       │     │      │        │
                     │       │     │      │        └─ ytd_cumulative
                     │       │     │      └────────── 4wk_moving_avg
                     │       │     └───────────────── prev_week_revenue (LAG)
                     │       └─────────────────────── weekly_net_revenue
                     └─────────────────────────────── week_start
```

---

## 6. The Retract-Then-Insert Pattern

Every window operator follows the same delta protocol. For any row whose
window function result changes:

```
  Output delta = { (key, old_result) → -1,    ← retract old value
                   (key, new_result) → +1 }   ← insert new value
```

This is *not* a convention — it is a **mathematical consequence** of Z-set
algebra. The integrated output (the "view") is the sum of all deltas. If a
result changes from `old` to `new`:

```
  Integrated[key] was:  old × (+1)  =  old
  Delta emitted:        old × (-1) + new × (+1)
  Integrated[key] now:  old × (+1) + old × (-1) + new × (+1)
                      = new × (+1)
                      = new   ✓
```

When the old and new values are identical (e.g., a correction upstream that
does not affect this particular window), the `+1` and `-1` cancel in the
Z-set, producing an empty delta for that key. **No downstream work is
triggered.**

---

## 7. Cost Model

| Operation | Work per delta row |
|---|---|
| LAG(N) | Recompute at most `N+1` output rows per changed input row |
| RANGE BETWEEN K PRECEDING AND CURRENT ROW | Recompute at most `K+1` output rows per changed input row |
| RANGE UNBOUNDED PRECEDING | Recompute all output rows *after* the changed input row in the partition |
| Append-only input (no corrections) | All three: O(1) new output rows per new input row |

The common case for streaming data is **append-only**: new weeks arrive in
order, existing weeks are never corrected. In this case:

- LAG emits exactly 1 new output row
- Moving average emits exactly 1 new output row
- Cumulative sum emits exactly 1 new output row

Corrections are the expensive case, but they are also rare. The incremental
approach guarantees correctness regardless.

---

## 8. Connection to DBSP Theory

These operators correspond to **§5 "Extensions"** of the DBSP paper, which
covers windows and time-series processing within the nested circuit model.

The key theoretical insight: window functions are *not* special. They are
compositions of standard DBSP primitives:

1. **Trace** (integration / `z^-1`) — maintains sorted history
2. **GroupTransformer** — per-partition processing with access to input
   trace, output trace, and current delta
3. **Incremental lifting** — the `transform()` method receives deltas and
   emits deltas, exactly as the chain rule requires

The `Lag` struct implements `GroupTransformer` (line 611 of
`crates/dbsp/src/operator/dynamic/group/lag.rs`):

```rust
impl<I, O, KCF> GroupTransformer<I, DynPair<I, O>> for Lag<I, O, KCF>
where
    I: DataTrait + ?Sized,
    O: DataTrait + ?Sized,
    KCF: Fn(&I, &I) -> Ordering + 'static,
{
    fn transform(
        &mut self,
        input_delta: &mut dyn ZCursor<I, DynUnit, ()>,
        input_trace: &mut dyn ZCursor<I, DynUnit, ()>,
        output_trace: &mut dyn ZCursor<DynPair<I, O>, DynUnit, ()>,
        output_cb: &mut dyn FnMut(&mut DynPair<I, O>, &mut DynZWeight),
    ) { ... }
}
```

This uniform interface — **delta in, delta out, with trace access** — is what
makes DBSP composable. The query optimizer can freely stack LAG → AVG →
SUM in the same pipeline, and each operator maintains its own trace
independently.

---

## 9. Waterlines and Memory Bounds

For unbounded streams, traces grow without limit. The `waterline` mechanism
(from `crates/dbsp/src/operator/time_series/waterline.rs`) solves this:

```rust
pub fn waterline_monotonic<TS, DynTS, IF, WF>(
    &self,
    init: IF,
    waterline_func: WF,
) -> Stream<RootCircuit, TypedBox<TS, DynTS>>
```

A waterline is a monotonically increasing lower bound: "no data with
timestamp < W will ever arrive." This allows the rolling aggregate operator
to **discard trace entries** below the waterline, bounding memory usage:

```
  Waterline ──────────┐
                      ▼
  Trace:  [week_1  week_2 | week_3  week_4  week_5]
           ^^^^^^^^^^^^^^^^
           Can be discarded — no future window
           will ever include these rows

  Memory = O(window_size × partitions)  instead of  O(total_rows)
```

The `partitioned_rolling_aggregate_with_waterline` variant (line 81 of
`rolling_aggregate.rs`) integrates the waterline directly:

```rust
pub fn partitioned_rolling_aggregate_with_waterline<PK, OV, Agg, PF>(
    &self,
    waterline: &Stream<RootCircuit, TypedBox<TS, DynDataTyped<TS>>>,
    partition_func: PF,
    aggregator: Agg,
    range: RelRange<TS>,
) -> OrdPartitionedOverStream<PK, TS, Agg::Output>
```

For `RANGE UNBOUNDED PRECEDING` (cumulative sum), there is no waterline
optimization — the window includes the entire history by definition. The
trace must keep everything. In practice, partitioning by year (as the
medallion pipeline does) limits the growth.

---

## Summary

| Concept | Key Takeaway |
|---|---|
| **LAG** | Two-pass algorithm: forward pass finds affected keys, backward pass computes new values. Cost proportional to `offset`. |
| **Moving average** | Maintains sorted trace per partition. Re-evaluates windows that overlap with changed/new rows. |
| **Cumulative sum** | Special case: window never shrinks. Append-only input → O(1) work. Corrections cascade forward. |
| **Delta protocol** | Always `(old, -1) + (new, +1)`. Cancels when unchanged. |
| **Waterlines** | Bound memory by discarding trace entries below a monotonic lower bound. |
| **Composability** | All window operators implement the same `GroupTransformer` / rolling-aggregate interface: delta in → delta out with trace access. |
