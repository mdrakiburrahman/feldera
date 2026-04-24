# 7. Worker Parallelism, Sharding & Exchange

The demo pipeline runs with `"workers": 4` in its runtime config. This document
explains how Feldera distributes data and computation across those four workers,
how it reshuffles data when keys change, and how it keeps everything consistent.

---

## 7.1 The Big Picture

Each worker holds a **shard** — a horizontal slice of every Z-set in the
pipeline. Stateless operators (map, filter, flat_map) run independently on each
shard. Stateful operators (join, aggregate, distinct) require that all records
sharing the same key land on the same worker. When they don't, an **Exchange**
reshuffles.

```
┌─────────────────────────────────────────────────────────┐
│                   Feldera Pipeline                       │
│                                                         │
│  Worker 0          Worker 1         Worker 2         Worker 3        │
│  ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐   │
│  │ Shard 0  │     │ Shard 1  │     │ Shard 2  │     │ Shard 3  │   │
│  │          │     │          │     │          │     │          │   │
│  │ orders   │     │ orders   │     │ orders   │     │ orders   │   │
│  │ items    │     │ items    │     │ items    │     │ items    │   │
│  │ products │     │ products │     │ products │     │ products │   │
│  │ customers│     │ customers│     │ customers│     │ customers│   │
│  │ inventory│     │ inventory│     │ inventory│     │ inventory│   │
│  └────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘   │
│       │                │                │                │          │
│       └───── Exchange channels (crossbeam) ──────────────┘          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

Every Z-set (orders, items, products, customers, inventory) is split across
all four workers. A record's home worker is determined by hashing its key.

---

## 7.2 Sharding Strategy

### Hash-Partition Routing

When records enter the pipeline (from Kafka, HTTP, etc.) or when an Exchange
occurs, each record is routed by:

```
target_worker = hash(key) % num_workers
```

With 4 workers the mapping looks like this:

```
                    hash(key) % 4
                    ─────────────
  key = "order-0017"  ──▶  hash = 0xA3F1...  ──▶  0xA3F1 % 4 = 1  ──▶  Worker 1
  key = "order-0042"  ──▶  hash = 0x7C20...  ──▶  0x7C20 % 4 = 0  ──▶  Worker 0
  key = "order-0099"  ──▶  hash = 0x1DE8...  ──▶  0x1DE8 % 4 = 0  ──▶  Worker 0
  key = "cust-0005"   ──▶  hash = 0x58B3...  ──▶  0x58B3 % 4 = 3  ──▶  Worker 3
  key = "prod-0201"   ──▶  hash = 0xEF44...  ──▶  0xEF44 % 4 = 0  ──▶  Worker 0
```

### Shard Implementation

The shard operator (`crates/dbsp/src/operator/communication/shard.rs`) takes an
input stream and redistributes its records so each worker holds exactly the
records whose keys hash to that worker's index:

```rust
// shard.rs:11-68 (simplified)
pub fn shard(&self) -> Stream<C, IB> {
    let factories = BatchReaderFactories::new::<IB::Key, IB::Val, IB::R>();
    self.inner().dyn_shard(&factories).typed()
}
```

Under the hood `dyn_shard` inserts an Exchange operator that routes each
`(key, value, weight)` triple to the correct worker.

### What Runs Where

| Operator kind | Key requirement            | Exchange needed? |
|---------------|----------------------------|------------------|
| map / filter  | None — stateless           | No               |
| flat_map      | None — stateless           | No               |
| join          | Both inputs keyed the same | Yes, if keys differ |
| aggregate     | Grouped by key             | Yes, if partition key ≠ group key |
| distinct      | Keyed by full record       | Yes, if not already partitioned |

---

## 7.3 Exchange (Shuffle) Operations

An Exchange is the mechanism that moves records between workers when the
required key changes. It is the distributed equivalent of a MapReduce shuffle.

### Architecture

```
          ┌──────────────────────────────────────────────────┐
          │               Exchange Operator                   │
          │                                                   │
  Worker 0│  ExchangeSender ──┬──▶ chan[0] ──▶ ExchangeReceiver │ Worker 0
          │                   ├──▶ chan[1] ──▶ ExchangeReceiver │ Worker 1
          │                   ├──▶ chan[2] ──▶ ExchangeReceiver │ Worker 2
          │                   └──▶ chan[3] ──▶ ExchangeReceiver │ Worker 3
          │                                                     │
  Worker 1│  ExchangeSender ──┬──▶ chan[0] ──▶ ExchangeReceiver │ Worker 0
          │                   ├──▶ chan[1] ──▶ ExchangeReceiver │ Worker 1
          │                   ├──▶ chan[2] ──▶ ExchangeReceiver │ Worker 2
          │                   └──▶ chan[3] ──▶ ExchangeReceiver │ Worker 3
          │                                                     │
          │         ... same for Workers 2 and 3 ...            │
          └─────────────────────────────────────────────────────┘

  Total channels: 4 senders × 4 receivers = 16 crossbeam channels
```

Each **ExchangeSender** (exchange.rs ~line 148) partitions its local records
into per-destination batches and pushes them onto bounded crossbeam channels.
Each **ExchangeReceiver** (exchange.rs ~line 219) drains its 4 incoming channels
and merges them into a single sorted batch. The full exchange wiring lives at
exchange.rs lines 429–520.

### Properties

- **Lock-free**: crossbeam channels use lock-free algorithms internally.
- **Bounded**: back-pressure prevents fast workers from overwhelming slow ones.
- **Zero-copy where possible**: batches are moved, not cloned.
- **Deterministic**: the hash function is deterministic, so the same key always
  lands on the same worker regardless of which worker produced it.

---

## 7.4 Barrier Synchronization Between Steps

Feldera processes data in discrete **steps** (micro-batches). Within a step
every operator fires once. A barrier ensures all workers finish the current step
before any worker begins the next.

```
  Time ──▶

  Worker 0: ───[step N]───|barrier|───[step N+1]───|barrier|───
  Worker 1: ───[step N]───|barrier|───[step N+1]───|barrier|───
  Worker 2: ───[step N]───|barrier|───[step N+1]───|barrier|───
  Worker 3: ───[step N]───|barrier|───[step N+1]───|barrier|───
                           ▲                        ▲
                    all 4 workers              all 4 workers
                    must arrive               must arrive
                    before any                before any
                    proceeds                  proceeds
```

### Scheduler and Executor

The scheduling machinery lives in `crates/dbsp/src/circuit/schedule.rs`:

- **Scheduler trait** (lines 227–297): decides the order in which operators
  within a single worker fire during one step. Implementations include a
  `DynamicScheduler` (re-exported at lines 20–21) that fires operators as their
  inputs become ready.
- **Executor trait** (lines 302–344): manages the pool of worker threads and
  coordinates the barrier between steps.

### CircuitConfig

`crates/dbsp/src/circuit/dbsp_handle.rs` (line 279) defines:

```rust
pub struct CircuitConfig {
    pub layout: Layout,        // how the circuit is laid out across machines
    pub pin_cpus: Vec<usize>,  // optional CPU pinning per worker
    // ...
}
```

CPU pinning maps each worker thread to a specific core, reducing context-switch
overhead and improving cache locality — useful when `workers: 4` maps to four
dedicated cores on the host.

---

## 7.5 Concrete Example: The 5-Way Join in `silver_order_items_enriched`

The demo's most complex view joins five tables:

```sql
SELECT ...
FROM   order_items oi
  JOIN orders    o  ON oi.order_id   = o.order_id
  JOIN customers c  ON o.customer_id = c.customer_id
  JOIN products  p  ON oi.product_id = p.product_id
  JOIN inventory i  ON p.product_id  = i.product_id
```

### Step-by-step across 4 workers

```
  ┌────────────────────────────────────────────────────────────────┐
  │  Phase 1: Shard inputs by their join key                       │
  │                                                                │
  │  order_items ──shard(order_id)──▶  [W0 W1 W2 W3]              │
  │  orders      ──shard(order_id)──▶  [W0 W1 W2 W3]              │
  │                                                                │
  │  Phase 2: JOIN order_items ⋈ orders  (on order_id)             │
  │  Each worker joins only its local shard — no cross-talk.       │
  │                                                                │
  │  Result: oi_orders, partitioned by order_id                    │
  │                                                                │
  ├────────────────────────────────────────────────────────────────┤
  │  Phase 3: Exchange oi_orders by customer_id                    │
  │                                                                │
  │  oi_orders ──exchange(customer_id)──▶  [W0 W1 W2 W3]          │
  │  customers ──shard(customer_id)────▶  [W0 W1 W2 W3]           │
  │                                                                │
  │  Phase 4: JOIN oi_orders ⋈ customers  (on customer_id)         │
  │  Purely local again after the exchange.                        │
  │                                                                │
  │  Result: oi_oc, partitioned by customer_id                     │
  │                                                                │
  ├────────────────────────────────────────────────────────────────┤
  │  Phase 5: Exchange oi_oc by product_id                         │
  │                                                                │
  │  oi_oc   ──exchange(product_id)──▶  [W0 W1 W2 W3]             │
  │  products──shard(product_id)────▶  [W0 W1 W2 W3]              │
  │                                                                │
  │  Phase 6: JOIN oi_oc ⋈ products  (on product_id)               │
  │  Local join.                                                   │
  │                                                                │
  │  Result: oi_ocp, partitioned by product_id                     │
  │                                                                │
  ├────────────────────────────────────────────────────────────────┤
  │  Phase 7: inventory is already sharded by product_id           │
  │                                                                │
  │  Phase 8: JOIN oi_ocp ⋈ inventory  (on product_id)             │
  │  No exchange needed — both sides share the same partition key. │
  │                                                                │
  │  Result: silver_order_items_enriched, partitioned by product_id│
  └────────────────────────────────────────────────────────────────┘
```

**Key insight**: the last two joins (products and inventory) share
`product_id` as their key, so the second join is "free" — no exchange needed.
The optimizer recognizes this and chains them without an intervening shuffle.

### Exchange count

| Join                          | Exchange? | Reason                          |
|-------------------------------|-----------|---------------------------------|
| order_items ⋈ orders          | Shard     | Initial partitioning            |
| result ⋈ customers            | Yes       | Key changes: order_id → customer_id |
| result ⋈ products             | Yes       | Key changes: customer_id → product_id |
| result ⋈ inventory            | No        | Already partitioned by product_id |

Total exchanges for the 5-way join: **2 reshuffles + 1 initial shard**.

---

## 7.6 Exchange Pattern: GROUP BY After a JOIN on a Different Key

Consider a simplified scenario: after joining on `order_id`, we want to
aggregate by `category`.

```sql
SELECT   p.category, SUM(oi.quantity) AS total_qty
FROM     order_items oi
  JOIN   products p ON oi.product_id = p.product_id
GROUP BY p.category
```

### Before the GROUP BY

After the join, data is partitioned by `product_id`. But `GROUP BY category`
needs all records with the same `category` on the same worker.

```
  BEFORE exchange                          AFTER exchange(category)
  (partitioned by product_id)              (partitioned by category)

  Worker 0:                                Worker 0:
    (prod-01, "Electronics", 5)              ("Books",        5)
    (prod-07, "Books",       3)              ("Books",        3)
                                             ("Books",        1)
  Worker 1:
    (prod-02, "Clothing",    2)            Worker 1:
    (prod-11, "Electronics", 8)              ("Clothing",     2)
                                             ("Clothing",     4)
  Worker 2:
    (prod-03, "Books",       1)            Worker 2:
    (prod-09, "Clothing",    4)              ("Electronics",  5)
                                             ("Electronics",  8)
  Worker 3:                                  ("Electronics",  7)
    (prod-05, "Electronics", 7)
    (prod-12, "Books",       5)            Worker 3:
                                             (empty — or other categories)
```

After the exchange, each worker can compute its local `SUM(quantity)` and that
**is** the global result for the categories it owns. No further coordination is
needed within the step.

```
  Worker 0:  Books        → SUM = 5 + 3 + 1 = 9
  Worker 1:  Clothing     → SUM = 2 + 4     = 6
  Worker 2:  Electronics  → SUM = 5 + 8 + 7 = 20
  Worker 3:  (none)
```

---

## 7.7 Performance Implications

### Why 4 Workers?

The demo uses `"workers": 4` as a practical default. The trade-offs:

| Workers | Throughput         | Exchange overhead | Memory       |
|---------|--------------------|-------------------|--------------|
| 1       | Baseline           | Zero              | 1× state     |
| 2       | ~1.8× (typical)    | Low               | 2× state     |
| 4       | ~3.2× (typical)    | Moderate          | 4× state     |
| 8       | ~5× (typical)      | Higher            | 8× state     |

Each worker maintains its own shard of every indexed Z-set, so memory scales
linearly. The exchange overhead (serialization + channel transit) is the main
cost of parallelism.

### When Exchange Dominates

If a pipeline has many key-changes (e.g., join on A, then group by B, then
join on C), each transition requires an exchange. In pathological cases the
shuffling cost can exceed the compute savings from parallelism. The remedy is to
restructure the SQL so consecutive operators share keys, minimizing reshuffles —
exactly as the 5-way join example demonstrates with the products → inventory
chain.

---

## 7.8 Summary

| Concept              | Mechanism                                    | Code location                          |
|----------------------|----------------------------------------------|----------------------------------------|
| Sharding             | hash(key) % num_workers                      | `communication/shard.rs`               |
| Exchange             | ExchangeSender/Receiver over crossbeam       | `communication/exchange.rs`            |
| Barrier sync         | All workers finish step N before step N+1    | `circuit/schedule.rs` (Executor trait) |
| CPU pinning          | `pin_cpus` in CircuitConfig                  | `circuit/dbsp_handle.rs`               |
| Stateless operators  | Run independently per shard — no exchange    | —                                      |
| Stateful operators   | Require co-located keys — exchange if needed | —                                      |
