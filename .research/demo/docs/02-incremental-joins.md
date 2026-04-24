# 02 — Incremental Multi-Way JOINs

> **Prerequisites:** You have run the medallion demo and read
> [01 — Z-Sets](01-z-sets.md). You understand that Feldera processes
> *changes* (Z-sets) rather than full tables.

A five-table JOIN sounds expensive. In a batch system it *is* expensive:
every new row triggers a full re-scan of every other table. DBSP turns
that O(N⁵) nightmare into five small, independent operations—each
touching only the *new* data on one side and the *accumulated* data on
the other.

This document explains the theory (the delta-join rule), the
implementation (`JoinTrace`), and walks through a concrete example from
the medallion demo.

---

## 1  The Delta-Join Rule

### 1.1  Two-Way Case

Given two time-varying relations A and B, the *full* join at time t is:

```
output(t) = A(t) ⋈ B(t)
```

We want the *change* in the output—its Z-set delta:

```
Δ(A ⋈ B) = A(t) ⋈ B(t)  −  A(t−1) ⋈ B(t−1)
```

Expanding A(t) = A(t−1) + Δ(A) and B(t) = B(t−1) + Δ(B):

```
Δ(A ⋈ B)  =  Δ(A) ⋈ B(t−1)          -- term 1: new A rows vs old B
           +  A(t−1) ⋈ Δ(B)          -- term 2: old A rows vs new B
           +  Δ(A)   ⋈ Δ(B)          -- term 3: new A rows vs new B
```

The code at `crates/dbsp/src/operator/dynamic/join.rs` (line 472)
writes this in z-transform notation:

```
delta(A ⋈ B) = a ⋈ z⁻¹(B) + z⁻¹(A) ⋈ b + a ⋈ b
```

where `a = Δ(A)`, `b = Δ(B)`, and `z⁻¹(X)` is the previous
accumulated state of X (the *trace*).

> **DBSP Paper Reference:** §3.2 "Joins" — the join operator ⋈ is
> bilinear, so its incrementalization decomposes into a sum of
> partial-delta terms. This is the core insight that makes incremental
> joins possible without re-scanning full tables.

### 1.2  N-Way Generalization

For an N-way join A₁ ⋈ A₂ ⋈ … ⋈ Aₙ, the delta decomposes into N
incremental terms. Each term processes the delta of *one* input against
the accumulated traces of all the others.

In practice, the SQL compiler decomposes the N-way join into a tree of
binary joins. Each binary join applies the two-way delta rule above, and
the deltas cascade through the tree.

---

## 2  The Five-Way Join in the Medallion Demo

The view `silver_order_items_enriched` joins five relations:

```sql
FROM bronze_order_items oi
    JOIN bronze_products   p ON oi.product_id  = p.product_id
    JOIN bronze_suppliers  s ON p.supplier_id   = s.supplier_id
    JOIN bronze_orders     o ON oi.order_id     = o.order_id
    JOIN silver_customers  c ON o.user_id       = c.customer_id
```

### 2.1  Binary-Join Decomposition

The compiler breaks this into a tree of four binary joins (J1–J4):

```
                      silver_order_items_enriched
                                 │
                            J4: ⋈ ON o.user_id = c.customer_id
                           ╱         ╲
                     J3: ⋈            silver_customers (c)
                    ON oi.order_id
                    = o.order_id
                   ╱          ╲
             J2: ⋈             bronze_orders (o)
            ON p.supplier_id
            = s.supplier_id
           ╱          ╲
     J1: ⋈             bronze_suppliers (s)
    ON oi.product_id
    = p.product_id
   ╱          ╲
bronze_       bronze_
order_items   products
  (oi)          (p)
```

Each `⋈` node is a binary incremental join. Deltas flow *upward*
through the tree: a change at any leaf propagates through only the
joins on the path to the root.

### 2.2  Delta Propagation — All Five Cases

When a delta arrives at one of the five inputs, here is what each
binary join does:

```
Input delta        J1                J2                J3                J4
─────────────────  ────────────────  ────────────────  ────────────────  ────────────────
Δ(order_items)     Δ(oi) ⋈ Tr(p)    propagate ΔJ1     propagate ΔJ2     propagate ΔJ3
                   ───────────────>  ΔJ1 ⋈ Tr(s)      ΔJ2 ⋈ Tr(o)      ΔJ3 ⋈ Tr(c)
                                     ───────────────>  ───────────────>  ───────────────>

Δ(products)        Tr(oi) ⋈ Δ(p)    propagate ΔJ1     propagate ΔJ2     propagate ΔJ3
                   ───────────────>  ΔJ1 ⋈ Tr(s)      ΔJ2 ⋈ Tr(o)      ΔJ3 ⋈ Tr(c)

Δ(suppliers)       (no-op)           Tr(J1) ⋈ Δ(s)    propagate ΔJ2     propagate ΔJ3
                                     ───────────────>  ΔJ2 ⋈ Tr(o)      ΔJ3 ⋈ Tr(c)

Δ(orders)          (no-op)           (no-op)           Tr(J2) ⋈ Δ(o)    propagate ΔJ3
                                                       ───────────────>  ΔJ3 ⋈ Tr(c)

Δ(customers)       (no-op)           (no-op)           (no-op)           Tr(J3) ⋈ Δ(c)
```

Key: `Tr(X)` = accumulated trace of X (all historical data).

**Observation:** A delta to `bronze_suppliers` touches only J2, J3,
and J4. It never re-scans `bronze_order_items` or `bronze_products`
directly—it looks them up *through* the stored trace of J1.

---

## 3  JoinTrace — The Implementation

### 3.1  What JoinTrace Does

The `JoinTrace` struct (around line ~1120 in `join.rs`) is the runtime
operator that implements one side of a binary incremental join. It:

1. Stores the **trace** of one input — an indexed, compacted history of
   all Z-set changes seen so far.
2. On each step, receives the **delta** from the other input.
3. Joins the incoming delta against the stored trace.
4. Outputs the resulting Z-set delta.

### 3.2  The Operator Wiring

For a single binary join `A ⋈ B`, the `dyn_join_incremental` function
(lines 481–512 in `join.rs`) wires two parallel JoinTrace operators
plus a union:

```
                    ┌───────────────────────────────┐
                    │    Binary Incremental Join     │
                    │                                │
   Δ(A) ──────────>│──┬──> JoinTrace(left)          │
                    │  │     Δ(A) ⋈ delay(Trace(B))  │──┐
                    │  │                             │  │
   Δ(B) ──────────>│──┼──> JoinTrace(right)         │  ├──> Δ(A ⋈ B)
                    │  │     delay(Trace(A)) ⋈ Δ(B)  │──┘
                    │  │                             │  (+)
                    │  └──> StreamJoin               │──┘
                    │        Δ(A) ⋈ Δ(B)             │
                    └───────────────────────────────┘
```

The three terms match the delta-join formula from §1.1.

In Rust (from `dyn_join_incremental`):

```rust
// Term 1: Δ(A) ⋈ z⁻¹(B)
left.dyn_integrate_trace(self_factories)
    .delay_trace()
    .dyn_stream_join_inner(&right, join_func.fork())

// Term 2: z⁻¹(A) ⋈ Δ(B)  (added via .plus())
.plus(&left.dyn_stream_join_inner(
    &right.dyn_integrate_trace(other_factories),
    join_func,
))
```

> **Note:** The `Δ(A) ⋈ Δ(B)` term is absorbed into Term 2 by using
> `integrate_trace()` *without* `delay_trace()` on the right side.
> `integrate_trace()` returns the trace *including* the current step's
> delta, so `Trace(A)(t)` already contains `Δ(A)(t)`. This is a
> deliberate optimization that avoids a separate third operator.

### 3.3  integrate_trace() and delay_trace()

These two primitives are the building blocks:

| Primitive            | What it computes           | Intuition                                                  |
|----------------------|----------------------------|------------------------------------------------------------|
| `integrate_trace()`  | Trace(A)(t) = Σ Δ(A)(0..t) | Running sum of all deltas up to and including time t.      |
| `delay_trace()`      | z⁻¹(Trace(A))             | The trace as it was at the *previous* step (time t−1).     |

Why both? Consider Term 1: `Δ(A) ⋈ z⁻¹(B)`. We want to join
*new* A rows against *old* B. If we used `integrate_trace()` alone, the
trace would include the current step's Δ(B), and we would double-count
the `Δ(A) ⋈ Δ(B)` contribution (once in Term 1 and once in Term 2).
The `delay_trace()` shifts the trace back by one step, excluding the
current delta.

```
Time:         t=0    t=1         t=2         t=3
              ────   ────        ────        ────
Δ(B):         {b1}   {b2}        {b3}        {}

integrate:    {b1}   {b1,b2}     {b1,b2,b3}  {b1,b2,b3}
              ^^^^   ^^^^^^^     ^^^^^^^^^^   ^^^^^^^^^^
              includes current step's delta

delay of
integrate:     {}    {b1}        {b1,b2}     {b1,b2,b3}
              ^^^^   ^^^^        ^^^^^^^     ^^^^^^^^^^
              excludes current step's delta
```

The asymmetry is deliberate: one side uses `delay_trace()`, the other
does not. Together they cover all three terms of the delta-join formula
exactly once, with no double-counting.

---

## 4  Concrete Walkthrough: A New Supplier Arrives

Let's trace what happens when a new row arrives in `bronze_suppliers`
and how it produces updates in `gold_supplier_performance`.

### 4.1  Setup

Assume the pipeline has been running and the traces already contain:

- **Trace(oi):** 10,000 order items
- **Trace(p):** 500 products
- **Trace(s):** 49 suppliers (about to become 50)
- **Trace(o):** 3,000 orders
- **Trace(c):** 1,000 customers

Now a new supplier arrives:

```
Δ(bronze_suppliers) = { (supplier_id=50, name="Acme Corp", ...) : +1 }
```

### 4.2  Step-by-Step Propagation

```
Step 1: J1 (order_items ⋈ products)
────────────────────────────────────
  Input deltas:  Δ(oi) = {},  Δ(p) = {}
  Output:        ΔJ1 = {}                         ← nothing to do

Step 2: J2 (J1_result ⋈ suppliers)
────────────────────────────────────
  Input deltas:  ΔJ1 = {},  Δ(s) = {supplier_50: +1}

  Term 1:  ΔJ1 ⋈ delay(Trace(s))  =  {} ⋈ ...  =  {}
  Term 2:  Trace(J1) ⋈ Δ(s)

           Trace(J1) contains all (order_item, product) pairs.
           Δ(s) has supplier_50.
           The join key is p.supplier_id = s.supplier_id.

           Suppose 8 products have supplier_id=50.
           Those 8 products are already joined with their order_items
           in Trace(J1)—say 120 rows.

           Result: ΔJ2 = 120 rows  (oi+p+s triples)

Step 3: J3 (J2_result ⋈ orders)
────────────────────────────────────
  Input deltas:  ΔJ2 = {120 rows},  Δ(o) = {}

  Term 1:  ΔJ2 ⋈ delay(Trace(o))

           Each of the 120 rows carries an order_id.
           Look up each order_id in Trace(o) (the 3,000 orders).
           Suppose all 120 have valid orders.

           Result: ΔJ3 = 120 rows  (oi+p+s+o quads)

Step 4: J4 (J3_result ⋈ customers)
────────────────────────────────────
  Input deltas:  ΔJ3 = {120 rows},  Δ(c) = {}

  Term 1:  ΔJ3 ⋈ delay(Trace(c))

           Each of the 120 rows carries a user_id (from the order).
           Look up each user_id in Trace(c).

           Result: ΔJ4 = 120 rows  (the full enriched records)
```

### 4.3  Downstream: gold_supplier_performance

The `gold_supplier_performance` view aggregates from the enriched data.
It receives those 120 new enriched rows and incrementally updates the
aggregate for supplier_id=50:

```
Δ(gold_supplier_performance) = {
    (supplier_id=50,
     name="Acme Corp",
     total_products=8,
     total_orders=120,
     total_revenue=...,
     avg_unit_price=...) : +1
}
```

**Total work done:** 120 lookups in Trace(J1) + 120 lookups in Trace(o)
+ 120 lookups in Trace(c) + one aggregation update. The pipeline never
re-scanned the 10,000 order items or the 3,000 orders as flat tables.

---

## 5  Why This Is Efficient

### 5.1  Work Proportional to Change Size

| Approach             | Work per new supplier row              |
|----------------------|----------------------------------------|
| Batch re-execution   | Scan all 5 tables, re-join everything  |
| Incremental (DBSP)   | Look up matching rows in traces only   |

The incremental approach does work proportional to the *number of
matching rows*, not the total table size.

### 5.2  Traces Are Indexed

Traces are not flat lists. They are indexed by key (the join column),
so the lookup is O(log n) or O(1) per probe, not O(n). This is what
makes the "look up in trace" step fast even when the trace contains
millions of rows.

### 5.3  No Redundant Recomputation

The delta-join rule guarantees that each combination of rows is
produced exactly once, at the moment when the *last* required row
arrives. If supplier_50 arrives before any products reference it,
the join produces zero output—and later, when a product with
`supplier_id=50` arrives, *that* delta flows through and picks up
the already-stored supplier from Trace(s).

---

## 6  The General Pattern

Every incremental binary join in Feldera follows this pattern:

```
        Δ(A)                            Δ(B)
         │                               │
         ▼                               ▼
   ┌───────────┐                   ┌───────────┐
   │integrate_ │                   │integrate_ │
   │  trace()  │                   │  trace()  │
   └─────┬─────┘                   └─────┬─────┘
         │                               │
         ▼                               ▼
   ┌───────────┐                   ┌───────────┐
   │  delay_   │                   │  (no      │
   │  trace()  │                   │   delay)  │
   └─────┬─────┘                   └─────┬─────┘
         │ z⁻¹(Trace(A))                │ Trace(B) incl. current
         │                               │
         │         ┌─────────┐           │
         │         │         │           │
         └────────>│  Join   │<──── Δ(A) │
                   │  Trace  │           │
   Δ(B) ─────────>│  (left) │           │
                   └────┬────┘           │
                        │                │
                        ▼                │
                   ┌─────────┐           │
         Δ(A) ───>│  Join   │<──────────┘
                   │  Trace  │
                   │ (right) │
                   └────┬────┘
                        │
                        ▼
                    (+) union
                        │
                        ▼
                   Δ(A ⋈ B)
```

This pattern repeats at every `⋈` node in the join tree. Deltas
cascade upward; traces accumulate downward. The result is a dataflow
graph where each operator does a small amount of work per step.

---

## 7  DBSP Paper References

| Topic                              | Reference                          |
|------------------------------------|------------------------------------|
| Bilinear operator incrementalization | §3.2 "Joins"                     |
| The z⁻¹ (delay) operator          | §2.3 "Streams and operators"       |
| Integration (running sum)          | §2.4 "Integration and differentiation" |
| Nested incremental computation     | §4 "Nested incremental computation"|
| Correctness proof for N-way joins  | §3.2, Proposition 3.4              |

The key insight from the paper: because the relational join ⋈ is a
*bilinear* operator (it distributes over addition in both arguments),
its incrementalization decomposes cleanly into partial-delta terms.
Non-bilinear operators (like `DISTINCT`) require different techniques
covered in later sections of the paper.

---

## Summary

1. **Delta-join rule:** `Δ(A ⋈ B) = Δ(A) ⋈ z⁻¹(B) + Trace(A) ⋈ Δ(B)`
   — each side's new data joins against the other side's history.
2. **JoinTrace** is the runtime operator that stores one side's trace
   and joins incoming deltas against it.
3. **integrate_trace()** accumulates all deltas into a trace;
   **delay_trace()** shifts it back one step to avoid double-counting.
4. **N-way joins** decompose into a tree of binary joins; deltas
   cascade through only the relevant path.
5. **Work is proportional to change size**, not total data size.

*Next: [03 — Incremental GROUP BY](03-incremental-group-by.md) —
how aggregations maintain running totals without re-scanning.*
