# 10. SQL-to-DBSP Compiler Deep Dive

## Executive Summary

Feldera ships a custom Java compiler that transforms SQL programs (CREATE TABLE + CREATE VIEW
statements) into Rust DBSP circuit code. The compiler is built atop Apache Calcite 1.42.0 and
proceeds through five well-defined phases: **parse → validate → plan → translate → optimize →
codegen**. Each SQL view becomes a subgraph of DBSP operators wired together in a `DBSPCircuit`.
The circuit is then serialized to Rust source that links against the `dbsp` runtime crate. This
document traces every phase in detail, with file-and-line-number citations into the
`sql-to-dbsp-compiler/SQL-compiler/` tree.

---

## 10.1 High-Level Architecture

```
                           ┌────────────────────────────────────────────┐
                           │            CompilerMain.run()              │
                           │  CompilerMain.java:157-301                 │
                           └──────────────┬─────────────────────────────┘
                                          │
                 ┌────────────────────────┼────────────────────────┐
                 │                        │                        │
         ┌───────▼────────┐    ┌──────────▼──────────┐    ┌───────▼────────┐
         │  Phase 1-3:    │    │  Phase 4:            │    │  Phase 5-6:    │
         │  Parse →       │    │  Calcite RelNode →   │    │  Optimize →    │
         │  Validate →    │    │  DBSP Circuit        │    │  Rust Codegen  │
         │  Calcite Plan  │    │                      │    │                │
         └───────┬────────┘    └──────────┬───────────┘    └───────┬────────┘
                 │                        │                        │
         SqlToRelCompiler       CalciteToDBSPCompiler       CircuitOptimizer
         .java                  .java                       .java
                 │                        │                 ToRustVisitor.java
                 ▼                        ▼                        │
           Calcite RelNode          DBSPCircuit                   ▼
           tree per view            (operator DAG)            Rust source
                                                              (.rs files)
```

**Data flow in one sentence:**

> SQL text → Calcite `SqlNode` AST → Calcite `RelNode` logical plan → DBSP `DBSPCircuit`
> (operator DAG) → optimized circuit → Rust source code.

### Key Classes

| Class | File | Role |
|---|---|---|
| `CompilerMain` | `CompilerMain.java:63-341`[^1] | CLI entry point, orchestrates phases |
| `DBSPCompiler` | `DBSPCompiler.java:118-175`[^2] | Compiler session: holds all state, coordinates subcomponents |
| `SqlToRelCompiler` | `SqlToRelCompiler.java`[^3] | Calcite frontend: parse + validate + plan |
| `CalciteToDBSPCompiler` | `CalciteToDBSPCompiler.java:245-260`[^4] | Translates Calcite RelNodes → DBSP operators |
| `CircuitOptimizer` | `CircuitOptimizer.java:53-175`[^5] | 40+ optimization passes on the DBSP circuit |
| `ToRustVisitor` | `ToRustVisitor.java:143-250`[^6] | Serializes DBSP circuit to Rust source |
| `DBSPCircuit` | `DBSPCircuit.java:61-258`[^7] | The circuit graph: nodes = operators, edges = data flow |

---

## 10.2 Phase 1 — SQL Parsing (Calcite Frontend)

Feldera uses a **customized Calcite parser** to handle standard SQL plus Feldera-specific DDL
extensions (`CREATE TABLE` with connectors/properties, `LATENESS`, `CREATE TYPE`, UDFs, UDAs).

### Parser Configuration

```java
// SqlToRelCompiler.java:280-286
SqlParser.config()
    .withLex(Lex.ORACLE)                              // Oracle-style lexing
    .withParserFactory(DbspParserImpl.FACTORY)         // custom parser (JavaCC-generated)
    .withUnquotedCasing(Casing.TO_LOWER)              // unquoted → lowercase
    .withQuotedCasing(Casing.UNCHANGED)               // quoted → preserve case
    .withConformance(SqlConformanceEnum.LENIENT)       // permissive SQL dialect
```

### Custom Parser Grammar

Feldera extends the Calcite `Parser.jj` grammar via JavaCC template substitution[^8]:

- The base `Parser.jj` is unpacked from the `calcite-core` JAR (`SQL-compiler/pom.xml:86-116`).
- Custom productions are merged by the Maven `fmpp` + `javacc` plugins (`pom.xml:167-190`).
- The generated parser class is `DbspParserImpl`.

### Custom DDL AST Nodes

Feldera registers custom `SqlNode` subclasses for DDL statements that Calcite does not support
natively:

| SQL Statement | AST Class | File |
|---|---|---|
| `CREATE TABLE` | `SqlCreateTable` | `parser/SqlCreateTable.java:25-103`[^9] |
| `CREATE VIEW` / `CREATE MATERIALIZED VIEW` | `SqlCreateView` | `parser/SqlCreateView.java:23-96`[^10] |
| `CREATE TYPE` | `SqlCreateType` | `parser/SqlCreateType.java:24-77`[^11] |
| `CREATE FUNCTION` | `SqlCreateFunctionDeclaration` | `parser/SqlCreateFunctionDeclaration.java` |
| `CREATE AGGREGATE` (UDA) | `SqlCreateAggregate` | `parser/SqlCreateAggregate.java` |
| `DROP TABLE` / `DROP VIEW` | `SqlDropTable` / `SqlDropView` | `parser/SqlDropTable.java`, `SqlDropView.java` |
| `LATENESS` annotations | `SqlLateness` | `parser/SqlLateness.java` |
| `PRIMARY KEY` / `FOREIGN KEY` | `SqlPrimaryKey` / `SqlForeignKey` | `parser/SqlPrimaryKey.java`, `SqlForeignKey.java` |
| Connector properties | `PropertyList` | `parser/PropertyList.java` |

### How `CREATE TABLE` Is Parsed

The demo's `medallion.sql` starts with seven `CREATE TABLE` statements (one per bronze input).
Each table passes through:

1. **Parser** → `SqlCreateTable` AST node with column definitions, primary keys, foreign keys,
   connector properties (`WITH (...)` clause).
2. **`SqlToRelCompiler.compileCreateTable()`** (`SqlToRelCompiler.java:1813-1844`[^12]) →
   validates columns, processes primary key/foreign key, registers the table in the Calcite
   catalog.
3. **`CalciteToDBSPCompiler`** emits a `DBSPSourceTableOperator` for each table.

### How `CREATE VIEW` Is Parsed

Each silver/gold view:

1. **Parser** → `SqlCreateView` AST node with `ViewKind` enum (`STANDARD`, `MATERIALIZED`, `LOCAL`).
2. **`SqlToRelCompiler.compileCreateView()`** (`SqlToRelCompiler.java:2057-2162`[^13]):
   - Parses the SQL body using `SqlParser`.
   - Validates the query using the Calcite validator.
   - Converts the validated `SqlNode` to a Calcite `RelNode` tree via `SqlToRelConverter`.
   - Runs the `CalciteOptimizer` (Calcite-level optimization rules, distinct from the
     DBSP-level `CircuitOptimizer`).
   - Wraps the result in a `CreateViewStatement` with the compiled `RelRoot`.

---

## 10.3 Phase 2 — SQL Validation and Type Resolution

### Calcite Validator Configuration

```java
// SqlToRelCompiler.java:626-642
SqlValidator.Config validatorConfig = SqlValidator.Config.DEFAULT
    .withIdentifierExpansion(true)
    .withDefaultNullCollation(NullCollation.LOW)    // NULLs sort first
    .withCallRewrite(false);
```

Additional conformance settings extend `SqlConformanceEnum.LENIENT`[^14]:

- `isGroupByOrdinal() = true` — allows `GROUP BY 1, 2` positional references
- `isSelectAlias() = LEFT_TO_RIGHT` — aliases visible left-to-right in same SELECT
- `isGroupByAlias() = true` — allows `GROUP BY alias_name`
- `isHavingAlias() = true`

### Custom Type System

Feldera defines a custom Calcite `RelDataTypeSystem` (`SqlToRelCompiler.java:363-393`[^15])
that sets maximum precision for `DECIMAL`, `VARCHAR`, and other types. It also registers
type aliases:

```java
// SqlToRelCompiler.java:245-263
DEFAULT_TYPE_ALIASES = {
    "BOOL"      → BOOLEAN,
    "STRING"    → VARCHAR,
    "INT2"      → SMALLINT,
    "INT4"      → INTEGER,
    "INT8"      → BIGINT,
    "FLOAT4"    → REAL,
    "FLOAT8"    → DOUBLE,
    "FLOAT32"   → REAL,
    "FLOAT64"   → DOUBLE,
    // ... more aliases
};
```

### Custom Functions

Feldera extends Calcite's operator table with 30+ custom SQL functions
(`CustomFunctions.java:56-164`[^16]):

| Category | Functions |
|---|---|
| **Array** | `ARRAY_CONTAINS`, `ARRAY_EXCEPT`, `ARRAY_EXISTS`, `ARRAY_INSERT`, `ARRAY_INTERSECT`, `ARRAY_POSITION`, `ARRAY_REMOVE`, `ARRAYS_OVERLAP`, `TRANSFORM` |
| **String** | `SPLIT_PART`, `RLIKE`, `INITCAP_SPACES`, `BIN2UTF8` |
| **Date/Time** | `CONVERT_TIMEZONE`, `FORMAT_DATE`, `FORMAT_TIMESTAMP`, `FORMAT_TIME`, `PARSE_DATE`, `PARSE_TIME`, `PARSE_TIMESTAMP` |
| **JSON** | `PARSE_JSON`, `TO_JSON` |
| **Numeric** | `BROUND`, `TO_INT`, `GREATEST_IGNORE_NULLS`, `LEAST_IGNORE_NULLS` |
| **System** | `NOW`, `SEQUENCE`, `CONNECTOR_METADATA`, `WRITE_LOG`, `BLACKBOX`, `GUNZIP` |

These are registered via the operator table chain:

```java
// SqlToRelCompiler.java:579-584
SqlOperatorTables.chain(
    CalciteFunctions.INSTANCE.getFunctions(),
    new CaseInsensitiveOperatorTable(customFunctions.getInitialFunctions())
)
```

### Extra Validation

After Calcite's standard validation, `ExtraValidation` (`SqlToRelCompiler.java:462-560`[^17])
runs additional checks including:
- Duplicate column names in tables
- Unsupported SQL constructs
- Connector property validation
- View column declarations matching query output

---

## 10.4 Phase 3 — Calcite Logical Plan (RelNode Tree)

### Planner Configuration

Feldera uses a **trivial Heuristic planner** — it does not rely on Calcite's cost-based
optimizer (Volcano planner):

```java
// SqlToRelCompiler.java:294-302
RelOptPlanner planner = new HepPlanner(new HepProgramBuilder().build());
RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(typeFactory));
```

The `SqlToRelConverter` is configured to:

```java
// SqlToRelCompiler.java:302-316
SqlToRelConverter.config()
    .withExpand(true)            // expand subqueries into joins
    .withTrimUnusedFields(true)  // prune unused columns early
    .withRelBuilderConfigTransform(t -> t.withSimplify(false))  // don't simplify during conversion
```

### Calcite-Level Optimization

After conversion to `RelNode`, the `CalciteOptimizer` applies Feldera-specific rewrite rules:

| Rule | File | Purpose |
|---|---|---|
| `ExceptOptimizerRule` | `optimizer/ExceptOptimizerRule.java:44-172`[^18] | Rewrite EXCEPT to more efficient form |
| `SetopOptimizerRule` | `optimizer/SetopOptimizerRule.java` | Optimize UNION/INTERSECT |
| `ValuesReduceRule` | `optimizer/ValuesReduceRule.java` | Fold constant `VALUES` clauses |
| `ReduceExpressionsRule` | `optimizer/ReduceExpressionsRule.java` | Constant folding in expressions |
| `InnerDecorrelator` | `optimizer/InnerDecorrelator.java:13-43`[^19] | Decorrelate correlated subqueries |
| `CorrelateUnionSwap` | `optimizer/CorrelateUnionSwap.java:52-156`[^20] | Push correlate above union for better plans |

### What a RelNode Tree Looks Like

For the demo's `silver_order_items_enriched` (5-way join), Calcite produces a tree like:

```
LogicalProject(order_id=..., sku=..., category=..., ...)
  └── LogicalJoin(condition=[...], joinType=[left])
        ├── LogicalJoin(condition=[...], joinType=[left])
        │     ├── LogicalJoin(condition=[...], joinType=[inner])
        │     │     ├── LogicalJoin(condition=[...], joinType=[inner])
        │     │     │     ├── LogicalTableScan(table=bronze_orders)
        │     │     │     └── LogicalTableScan(table=bronze_order_items)
        │     │     └── LogicalTableScan(table=bronze_products)
        │     └── LogicalTableScan(table=bronze_customers)
        └── LogicalTableScan(table=bronze_inventory)
```

Each node in this tree will be visited by `CalciteToDBSPCompiler` in the next phase.

---

## 10.5 Phase 4 — Calcite RelNode → DBSP Circuit

This is the heart of the compiler. `CalciteToDBSPCompiler` extends Calcite's `RelVisitor` and
walks the RelNode tree bottom-up, emitting DBSP operators for each node.

### The Translation Dispatch

The visitor dispatch uses `visitIfMatches()` (`CalciteToDBSPCompiler.java:320-330`[^21]):

```java
<T> boolean visitIfMatches(RelNode node, Class<T> clazz, Consumer<T> method) {
    T value = ICastable.as(node, clazz);
    if (value != null) {
        method.accept(value);
        return true;
    }
    return false;
}
```

Each Calcite node type is dispatched to a specific `visit*` method.

### RelNode → DBSP Operator Mapping

| Calcite RelNode | DBSP Operator | Translation Method | What It Does |
|---|---|---|---|
| `LogicalTableScan` | `DBSPSourceTableOperator` | `visitScan()` :985[^22] | Input source for a table |
| `LogicalProject` | `DBSPMapOperator` | `visitProject()` :1031[^23] | Column projection / computed columns |
| `LogicalFilter` | `DBSPFilterOperator` | `visitFilter()` :1196[^24] | WHERE clause predicate |
| `LogicalAggregate` | `DBSPMapIndexOperator` + `DBSPStreamAggregateOperator` | `visitAggregate()` :943[^25] | GROUP BY + aggregate functions |
| `LogicalAggregate` (no aggs) | `DBSPMapOperator` + `DBSPStreamDistinctOperator` | `visitAggregate()` :956[^25] | DISTINCT (Calcite encodes it as aggregate with no agg calls) |
| `LogicalJoin` (inner) | `DBSPMapIndexOperator` × 2 + `DBSPStreamJoinOperator` | `visitJoin()` :1521[^26] | Equi-join: index both sides by key, then join |
| `LogicalJoin` (left) | Same + `DBSPLeftJoinOperator` | `visitJoin()` :1521[^26] | Left outer join |
| `LogicalJoin` (cross) | `DBSPStreamJoinOperator` with empty key | `visitJoin()` :1575[^26] | Cross join |
| `LogicalUnion ALL` | `DBSPSumOperator` | `visitUnion()` :1141[^27] | Multiset sum of inputs |
| `LogicalUnion DISTINCT` | `DBSPSumOperator` + `DBSPStreamDistinctOperator` | `visitUnion()` :1155[^27] | Sum + distinct |
| `LogicalMinus` | `DBSPNegateOperator` + `DBSPSumOperator` + `DBSPStreamDistinctOperator` | `visitMinus()` :1164[^28] | EXCEPT = negate second input, sum, distinct |
| `LogicalWindow` | `DBSPLagOperator` / `DBSPPartitionedRollingAggregateOperator` | `visitWindow()` | Window functions (LAG, SUM OVER, etc.) |
| `LogicalCorrelate` + `Uncollect` | `DBSPFlatMapOperator` | `visitCorrelate()` :372[^29] | UNNEST / CROSS APPLY |
| `LogicalTableFunctionScan` (TUMBLE) | `DBSPMapOperator` | `compileTumble()` :543[^30] | Tumbling window TVF |
| `LogicalTableFunctionScan` (HOP) | `DBSPHopOperator` | `compileHop()` | Hopping window TVF |
| `LogicalAsofJoin` | `DBSPAsofJoinOperator` | (dedicated visitor) | AS OF join |
| `LogicalSort` | `DBSPMapOperator` + sorting logic | `visitSort()` | ORDER BY / LIMIT |
| `LogicalValues` | `DBSPConstantOperator` | `visitValues()` | Literal row constructors |

### How a JOIN Is Translated (Detail)

The join translation (`visitJoin()`, lines 1521-1650+[^26]) is the most complex. For an
equi-join `A JOIN B ON A.key = B.key`:

```
Step 1: Analyze join condition
   JoinConditionAnalyzer.analyze(join, condition)
   → decomposition: equality tests + left/right/residual predicates

Step 2: Pull single-side predicates
   left predicates  → DBSPFilterOperator on left input
   right predicates → DBSPFilterOperator on right input

Step 3: Filter null keys (for equi-join columns)
   filterNonNullFields() on both sides

Step 4: Index both sides by join key
   left  → DBSPMapIndexOperator (key=join_cols, value=remaining_cols)
   right → DBSPMapIndexOperator (key=join_cols, value=remaining_cols)

Step 5: Join
   Inner join  → DBSPStreamJoinOperator(left_index, right_index, closure)
   Left join   → DBSPLeftJoinOperator(left_index, right_index, closure)
   Right join  → swap inputs + left join
   Full outer  → union of left-join + anti-join

Step 6: Apply residual non-equi predicates (if any)
   → DBSPFilterOperator

Step 7: Project to output schema (cast nullable columns)
   → DBSPMapOperator
```

### How an AGGREGATE Is Translated

For `SELECT category, COUNT(*) FROM t GROUP BY category` (line 943+[^25]):

```
Step 1: Get input operator

Step 2: Index by group-by key
   → DBSPMapIndexOperator (key=(category), value=(full_row))

Step 3: Compile aggregate functions
   AggregateCompiler per aggregate call
   → produces IAggregate implementation (linear or non-linear)

Step 4: Create aggregate operator
   → DBSPStreamAggregateOperator (IndexedZSet in, IndexedZSet out)
   The operator maintains a Trace of (key → running aggregate)

Step 5: Flatten key+value to output tuple
   → DBSPMapOperator (IndexedZSet → ZSet of flat tuples)

Step 6: Handle empty-group-set case
   If GROUP BY has no keys and no rows → emit default aggregate values
   → DBSPAggregateZeroOperator
```

### The `ExpressionCompiler`

Individual SQL expressions (column references, arithmetic, function calls, CASE, CAST, etc.)
are compiled by `ExpressionCompiler` (`frontend/ExpressionCompiler.java`[^31]), which maps
Calcite `RexNode` expressions to DBSP `DBSPExpression` IR nodes.

---

## 10.6 The DBSP Intermediate Representation (IR)

### Operator Hierarchy

All operators live under `circuit/operator/`[^32]. The base class is `DBSPOperator`
(`DBSPOperator.java:51`), with key fields:

```java
List<OutputPort> inputs;          // graph edges (predecessors)
List<CircuitAnnotation> annotations;
@Nullable RelNode derivedFrom;    // Calcite origin (for error reporting)
```

Operators connect via `OutputPort` objects. The circuit is a DAG stored in topological order.

### Type System

The DBSP IR has its own type system under `ir/type/`[^33] that mirrors Rust types:

| SQL Type | DBSP Type | Rust Type |
|---|---|---|
| `INTEGER` | `DBSPTypeInteger(32, true)` | `i32` |
| `BIGINT` | `DBSPTypeInteger(64, true)` | `i64` |
| `VARCHAR` | `DBSPTypeString` | `String` |
| `BOOLEAN` | `DBSPTypeBool` | `bool` |
| `DECIMAL(p,s)` | `DBSPTypeDecimal` | `Decimal` |
| `TIMESTAMP` | `DBSPTypeTimestamp` | `Timestamp` |
| `DATE` | `DBSPTypeDate` | `Date` |
| `ARRAY<T>` | `DBSPTypeArray(T)` | `Vec<T>` |
| `MAP<K,V>` | `DBSPTypeMap(K,V)` | `BTreeMap<K,V>` |
| `ROW(...)` | `DBSPTypeTuple(...)` | `Tuple<...>` |
| `VARIANT` | `DBSPTypeVariant` | `Variant` |
| Any nullable T | `DBSPTypeOption(T)` | `Option<T>` |

Type conversion from Calcite `RelDataType` to DBSP types is performed by `TypeCompiler`
(`frontend/TypeCompiler.java:106-260`[^34]).

**Nullability** is tracked per-type: every `DBSPType` has a `mayBeNull` field
(`ir/type/DBSPType.java:49-57`[^35]). When `mayBeNull = true`, the Rust codegen wraps
the type in `Option<T>`.

### Expression System

Expressions under `ir/expression/`[^36] form a tree:

- `DBSPTupleExpression` — tuple constructors `Tuple3::new(a, b, c)`
- `DBSPClosureExpression` — Rust closures `|x: &T| { body }`
- `DBSPBinaryExpression` — `a + b`, `a && b`, etc.
- `DBSPApplyExpression` — function calls `f(x, y)`
- `DBSPFieldExpression` — field access `x.0`, `x.field_name`
- `DBSPCastExpression` — type casts
- `DBSPIfExpression` — ternary `if cond { a } else { b }`
- `DBSPIsNullExpression` — `x.is_none()`
- `DBSPSomeExpression` — `Some(x)`
- Literals in `ir/expression/literal/` — `DBSPBoolLiteral`, `DBSPStringLiteral`, etc.

### Circuit Structure

`DBSPCircuit` (`circuit/DBSPCircuit.java:61-258`[^7]) stores:

```java
List<DBSPDeclaration> declarations;     // top-level Rust items
List<DBSPSourceBaseOperator> sourceOperators;  // CREATE TABLE inputs
List<DBSPViewBaseOperator> viewOperators;      // CREATE VIEW outputs
List<DBSPSinkOperator> sinkOperators;          // output sinks
List<DBSPOperator> allOperators;               // all operators in topo order
```

It implements `DiGraph<DBSPOperator>` — the graph API is used by optimization passes.

---

## 10.7 Phase 5 — Circuit Optimization (40+ Passes)

The `CircuitOptimizer` (`CircuitOptimizer.java:53-175`[^5]) applies an ordered sequence of
~45 optimization passes. These operate on the DBSP circuit graph (not the Calcite plan).

### Optimization Pass Pipeline

The passes execute in the following order, grouped by purpose:

#### Early Passes (Circuit Construction Finalization)

| Pass | Purpose |
|---|---|
| `ImplementNow` | Replaces references to the virtual `NOW` table with `DBSPNowOperator` |
| `DeterministicFunctions` | Marks pure/deterministic functions for optimization |
| `NoConnectorMetadata` | Strips connector metadata functions from the circuit |
| `RecursiveComponents` | Detects and structures recursive view definitions |
| `DeadCode` | Removes unreachable operators |
| `EnsureDistinctOutputs` | Adds `DISTINCT` on outputs if `outputsAreSets` is enabled |
| `PropagateEmptySources` | Propagates known-empty sources to simplify downstream |
| `MergeSums` | Merges adjacent `SumOperator`s |

#### Structural Optimizations

| Pass | Purpose |
|---|---|
| `CreateStarJoins` | Detects multi-way join patterns and rewrites to star-join operators |
| `RemoveNoops` | Removes identity/noop operators |
| `OptimizeProjections` | Pushes projections down, removes redundant ones |
| `RemoveViewOperators` | Inlines view references |
| `UnusedFields` | Prunes columns not used by downstream operators |
| `Intern` | Interns string/type constants for sharing |
| `CSE` | Common subexpression elimination at the operator level |
| `ExpandAggregates` | Expands aggregate operators into primitive operations |
| `RemoveStarJoins` | Expands star joins back if not beneficial |

#### Incrementalization

| Pass | Purpose |
|---|---|
| `OptimizeDistinctVisitor` | Removes unnecessary `DISTINCT` operators |
| `OptimizeIncrementalVisitor` | Pre-incrementalization cleanup |
| **`IncrementalizeVisitor`** | **The core pass: converts the batch circuit to an incremental circuit** by inserting `Integrate` (∫) and `Differentiate` (D) operators per the DBSP theory[^37] |
| `RemoveIAfterD` | Removes `Integrate` immediately following `Differentiate` (identity) |

This is the pass that connects SQL to the DBSP paper (§4). Each stateful operator gets
wrapped: inputs pass through `D` (differentiate), operators process deltas, and outputs pass
through `∫` (integrate) to reconstruct full collections where needed.

#### Post-Incrementalization Optimization

| Pass | Purpose |
|---|---|
| `Simplify` | Expression simplification (constant folding, algebraic identities) |
| `RemoveConstantFilters` | Removes filters with constant `true`/`false` conditions |
| `ShareIndexes` | Shares `MapIndex` operators when multiple consumers need the same index |
| `FilterJoinVisitor` | Pushes filters into joins for monotonicity |
| `MonotoneAnalyzer` | Analyzes which operators produce monotonically growing output (enables garbage collection of old state) |
| `RemoveTable` | Removes the internal `FELDERA_ERROR_TABLE` after analysis |

#### Lowering and Code Preparation

| Pass | Purpose |
|---|---|
| `CloneOperatorsWithFanout` | Duplicates operators consumed by multiple downstreams |
| `ExpandIndexedInputs` | Expands indexed inputs for join/aggregate |
| `NoIntegralVisitor` | Removes unnecessary integrals in incremental mode |
| `ExpandHop` | Expands hopping-window operators |
| `RemoveDeindexOperators` | Cleans up deindex operators |
| `ChainVisitor` | Chains sequential operators for fusion |
| `ImplementChains` | Converts chains to `DBSPChainAggregateOperator` |
| `ExpandCasts` | Expands complex type casts |
| `ImplementJoins` | Lowers abstract join operators to concrete implementations |
| `LowerAsof` | Lowers ASOF join to concrete operator |
| `LowerCircuitVisitor` | Final lowering to runtime-compatible operators |
| `BalancedJoins` | Rebalances join tree for better performance |

#### Final Passes

| Pass | Purpose |
|---|---|
| `PushDifferentialsUp` | Hoists `D` operators for better sharing |
| `CSE` (again) | Second CSE pass after all lowering |
| `InnerCSE` | CSE within expression closures |
| `CreateRuntimeErrorWrappers` | Wraps expressions that may fail with error handling |
| `StrayGC` | Removes stray garbage collection operators |
| `CanonicalForm` | Normalizes expression trees for stable Merkle hashes |
| `StaticDeclarations` | Hoists static data to Rust `static` declarations |
| `ComparatorDeclarations` | Generates comparator functions |
| `CompactNames` | Shortens operator names for readable Rust output |
| `MerkleOuter` | Computes Merkle hashes for change detection (2 passes) |
| `CircuitStatistics` | Collects statistics about the final circuit |

---

## 10.8 Phase 6 — Rust Code Generation

### Code Generation Entry Point

From `CompilerMain.run()` (lines 254-298[^1]):

```java
if (!compiler.options.ioOptions.multiCrates()) {
    // Single-crate mode
    RustFileWriter writer = new RustFileWriter(materializations);
    writer.add(circuit);
    writer.write(compiler);
} else {
    // Multi-crate mode (default for pipeline manager)
    MultiCratesWriter multiWriter = new MultiCratesWriter(outputFile, crates, true);
    multiWriter.add(circuit);
    multiWriter.write(compiler);
}
```

### `ToRustVisitor` — The Circuit Serializer

`ToRustVisitor` (`backend/rust/ToRustVisitor.java:143-250`[^6]) walks the circuit and emits
Rust source for each operator:

1. **Declarations**: static data, comparators, type aliases
2. **Operator code**: each `DBSPOperator` has a `generateOperator()` method that emits:
   - The operator instantiation (e.g., `circuit.add_stream_join(...)`)
   - Closure parameters (the `|key, left, right| { ... }` Rust closures)
   - Wire-up to input ports
3. **Catalog registration**: connects input/output handles for the runtime

### `ToRustInnerVisitor` — Expression Serializer

Inner expressions (closures, arithmetic, casts, etc.) are serialized by `ToRustInnerVisitor`,
which maps each `DBSPExpression` to its Rust syntax:

| DBSP Expression | Rust Output |
|---|---|
| `DBSPTupleExpression([a, b, c])` | `Tuple3::new(a, b, c)` |
| `DBSPBinaryExpression(+, a, b)` | `a.add(b)` or `a + b` |
| `DBSPClosureExpression(body, param)` | `\|param: &Type\| { body }` |
| `DBSPFieldExpression(x, 0)` | `x.0` |
| `DBSPCastExpression(x, i64)` | `cast_to_i64(x)` |
| `DBSPSomeExpression(x)` | `Some(x)` |
| `DBSPIsNullExpression(x)` | `x.is_none()` |

### Multi-Crate Output

In production, the pipeline manager uses multi-crate mode (`--crates` flag). This generates a
Cargo workspace with multiple crates for parallel compilation:

```
output/
├── Cargo.toml          (workspace)
├── crates/
│   ├── globals/        (static declarations, types, comparators)
│   │   └── src/
│   │       ├── lib.rs
│   │       └── stubs.rs   (UDF stubs for user-defined functions)
│   ├── crate_0/        (operators 0..N)
│   │   └── src/lib.rs
│   ├── crate_1/        (operators N..M)
│   │   └── src/lib.rs
│   └── ...
└── main/               (entry point, catalog registration)
    └── src/lib.rs
```

### `RustSqlRuntimeLibrary`

This class (`backend/rust/RustSqlRuntimeLibrary.java`[^38]) maintains the mapping between SQL
functions and their Rust runtime implementations. For example:

- SQL `+` on nullable integers → `plus_N_N()` (null-propagating addition)
- SQL `LIKE` → `like_()` from the `sqllib` crate
- SQL `CAST(x AS TIMESTAMP)` → `cast_to_Timestamp_s(x)`

---

## 10.9 The Visitor Pattern Architecture

The compiler uses a double-dispatch visitor pattern with two layers:

### Outer Visitors (Circuit-Level)

Base class: `CircuitVisitor` (`visitors/outer/CircuitVisitor.java`[^39])

These traverse the operator DAG and optionally rewrite it:
- `CircuitCloneVisitor` — deep-clones the circuit while applying transformations
- Each optimization pass is an outer visitor

### Inner Visitors (Expression-Level)

Base class: `InnerVisitor` (`visitors/inner/`[^40])

These traverse expression trees within operator closures:
- `Simplify` — algebraic simplification
- `BetaReduction` — inline closure applications
- `ConstantExpressions` — fold constant subexpressions
- `ExpressionsCSE` — common subexpression elimination within closures
- `ExpandWriteLog` — expand `WRITE_LOG()` calls
- `CanonicalForm` — normalize expression order for stable hashing

### Monotonicity Analysis

The `MonotoneAnalyzer` (`visitors/outer/monotonicity/MonotoneAnalyzer.java`[^41]) is a
particularly important pass. It performs a dataflow analysis to determine which operators
produce **monotonically increasing** outputs — meaning old data can be garbage-collected
from Traces. This is critical for:

- Temporal queries with `LATENESS` annotations
- Window functions that only look at recent data
- Queries where the `NOW` table drives time progression

When an operator is proven monotone, the optimizer inserts
`DBSPIntegrateTraceRetainKeysOperator` or `DBSPControlledKeyFilterOperator` to bound the
state that the runtime Spine must maintain.

---

## 10.10 Concrete Example — Demo's `gold_weekly_revenue_trend`

To make this concrete, let's trace the demo's `gold_weekly_revenue_trend` view through the
compiler:

### SQL Input

```sql
CREATE VIEW gold_weekly_revenue_trend AS
SELECT
    DATE_TRUNC('week', o.order_date) AS week_start,
    SUM(oi.quantity * oi.unit_price) AS total_revenue,
    LAG(SUM(oi.quantity * oi.unit_price), 1) OVER (ORDER BY DATE_TRUNC('week', o.order_date))
        AS prev_week_revenue
FROM bronze_orders o
JOIN bronze_order_items oi ON o.order_id = oi.order_id
GROUP BY DATE_TRUNC('week', o.order_date);
```

### Phase 1-3: Calcite Plan

```
LogicalWindow(window#0=[window(order by [$0] rows between 1 PRECEDING and 1 PRECEDING aggs [LAG($1)])])
  └── LogicalAggregate(group=[{0}], total_revenue=[SUM($1)])
        └── LogicalProject(week_start=[DATE_TRUNC('week', $1)], revenue=[$3 * $4])
              └── LogicalJoin(condition=[$0 = $2], joinType=[inner])
                    ├── LogicalTableScan(table=bronze_orders)  -- (order_id, order_date, ...)
                    └── LogicalTableScan(table=bronze_order_items)  -- (order_id, sku, qty, price, ...)
```

### Phase 4: DBSP Operators

```
[SourceTable: bronze_orders]───┐
                                ├──[MapIndex: key=order_id]──┐
[SourceTable: bronze_order_items]──[MapIndex: key=order_id]──┤
                                                              │
                                                    [StreamJoin: equi on order_id]
                                                              │
                                                    [Map: project (week_start, qty*price)]
                                                              │
                                                    [MapIndex: key=week_start]
                                                              │
                                                    [StreamAggregate: SUM(revenue)]
                                                              │
                                                    [Map: flatten (week_start, total_revenue)]
                                                              │
                                                    [Lag: partition=(), order=week_start, offset=1]
                                                              │
                                                    [ViewOperator: gold_weekly_revenue_trend]
```

### Phase 5: After Incrementalization

The `IncrementalizeVisitor` wraps stateful operators with `D` and `∫`:

```
[SourceTable]──[D]──[MapIndex]──[StreamJoin]──[Map]──[MapIndex]──[StreamAggregate]──[Map]──[Lag]──[∫]──[D]──[Sink]
                                     │                    │                                  │
                                  (Trace)              (Trace)                            (Trace)
                                  for join             for aggregate                      for LAG
```

Each `Trace` is a Spine in the runtime — the state structures documented in
`06-state-and-storage.md` §6.1.

### Phase 6: Rust Output (Simplified)

```rust
// Generated operator for the join
let join_output = circuit.add_stream_join(
    &bronze_orders_indexed,
    &bronze_order_items_indexed,
    |key: &i64, left: &OrderRow, right: &OrderItemRow| -> Tuple2<Date, Decimal> {
        let week_start = date_trunc_week(left.order_date);
        let revenue = right.quantity * right.unit_price;
        Tuple2::new(week_start, revenue)
    }
);

// Generated operator for the aggregate
let agg_output = circuit.add_stream_aggregate(
    &join_indexed_by_week,
    SumAggregator::new(|row: &Tuple2<Date, Decimal>| row.1.clone())
);
```

---

## 10.11 Calcite Version and Dependencies

| Dependency | Version | Source |
|---|---|---|
| Apache Calcite | **1.42.0** | `SQL-compiler/pom.xml:19-20`[^42] |
| Calcite Core | 1.42.0 | `pom.xml:291-300` |
| Calcite Linq4j | 1.42.0 | `pom.xml:291-300` |
| Java | 19, 20, or 21 | `README.md:12` |
| JavaCC (parser generation) | via Maven plugin | `pom.xml:167-190` |
| FMPP (template processing) | via Maven plugin | `pom.xml:86-116` |

The `calcite_version.env` file[^43] tracks:
- `CALCITE_CURRENT="1.41.0"` — current stable baseline
- `CALCITE_NEXT="1.42.0"` — version in use (patched/next)
- `CALCITE_NEXT_COMMIT="18454caf..."` — pinned Calcite commit

---

## 10.12 Compiler Options Reference

`CompilerOptions.java:41-334`[^44] defines all configurable options:

### Language Options

| Flag | Default | Purpose |
|---|---|---|
| `-i` | `true` | Enable incrementalization |
| `-O level` | `2` | Optimization level (0=none, 1=basic, 2=full) |
| `--alltables` | `false` | Generate input operators for all tables (even unused) |
| `--ignoreOrder` | `false` | Ignore ORDER BY in views |
| `--outputsAreSets` | `false` | Treat all outputs as sets (add DISTINCT) |
| `--streaming` | `true` | Streaming mode (vs. batch) |
| `--lenient` | `false` | Lenient SQL parsing |

### IO Options

| Flag | Default | Purpose |
|---|---|---|
| `-o file` | stdout | Output file |
| `--crates` | `0` | Number of parallel compilation crates (0 = single file) |
| `--handles` | `false` | Emit handle-based API |
| `--plan` | none | Emit Calcite plan as JSON |
| `--dataflow` | none | Emit full dataflow (Calcite plan + DBSP MIR) |
| `--jpg` / `--png` | `false` | Emit circuit as image (requires graphviz) |
| `--jit` | `false` | Emit JIT-friendly JSON IR |
| `--trimInputs` | `false` | Prune unused input columns |

---

## 10.13 How the Pipeline Manager Invokes the Compiler

The pipeline manager calls the compiler as a Java subprocess
(`crates/pipeline-manager/src/compiler/sql_compiler.rs`[^45]):

```
java -jar sql_compiler.jar \
    --crates <n> \
    -o <output_dir> \
    --handles \
    --dataflow <dataflow.json> \
    --schema <schema.json> \
    program.sql
```

The `sql_compiler_task()` (line 66[^45]) runs a periodic loop:
1. Check for pending compilation requests
2. Write the SQL program to a temporary file
3. Invoke `java -jar sql_compiler.jar` with appropriate flags
4. Parse the generated Rust crate(s) and the JSON schema/dataflow
5. Feed the Rust crate to `cargo build` (the Rust compiler task)

---

## 10.14 Summary — The Full Pipeline for the Demo

```
medallion.sql (683 lines)
    │
    ▼ Phase 1: Parse
    │  SqlParser (Calcite + custom DbspParserImpl)
    │  → 7 SqlCreateTable + ~12 SqlCreateView AST nodes
    │
    ▼ Phase 2: Validate
    │  Calcite SqlValidator (custom conformance + type system)
    │  → type-checked, name-resolved AST
    │
    ▼ Phase 3: Plan
    │  SqlToRelConverter → RelNode tree per view
    │  CalciteOptimizer → optimized RelNode trees
    │  → ~12 RelNode trees (one per view)
    │
    ▼ Phase 4: Translate
    │  CalciteToDBSPCompiler (RelVisitor)
    │  → DBSPCircuit with ~50-100 operators
    │    (sources, maps, filters, joins, aggregates, windows, sinks)
    │
    ▼ Phase 5: Optimize (45 passes)
    │  CircuitOptimizer
    │  Key: IncrementalizeVisitor adds D/∫ operators
    │  Key: MonotoneAnalyzer enables GC of temporal state
    │  → Optimized incremental circuit
    │
    ▼ Phase 6: Codegen
    │  ToRustVisitor + ToRustInnerVisitor
    │  → Rust source code (multi-crate workspace)
    │
    ▼ Rust Compilation
    │  cargo build (with sccache)
    │  → Native shared library (.so/.dylib)
    │
    ▼ Runtime
       DBSP runtime loads library
       → Pipeline processes Delta Lake changes incrementally
```

---

## Confidence Assessment

| Aspect | Confidence | Notes |
|---|---|---|
| Compilation phases | ★★★★★ | Directly verified from source code |
| Calcite configuration | ★★★★★ | Exact line numbers from `SqlToRelCompiler.java` |
| RelNode → DBSP mapping | ★★★★☆ | Most mappings verified; some complex cases (window functions) are simplified |
| Optimization pass list | ★★★★★ | Complete list from `CircuitOptimizer.java:72-175` |
| Rust codegen details | ★★★★☆ | High-level flow verified; exact Rust output is generated dynamically |
| Demo-specific trace | ★★★☆☆ | Conceptual walkthrough; actual operator count may differ |
| Calcite version | ★★★★★ | From `pom.xml` and `calcite_version.env` |

---

## Footnotes

[^1]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/CompilerMain.java:63-341`
[^2]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/DBSPCompiler.java:118-175`
[^3]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/calciteCompiler/SqlToRelCompiler.java`
[^4]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/CalciteToDBSPCompiler.java:245-260`
[^5]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/CircuitOptimizer.java:53-175`
[^6]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/backend/rust/ToRustVisitor.java:143-250`
[^7]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/DBSPCircuit.java:61-258`
[^8]: `sql-to-dbsp-compiler/SQL-compiler/pom.xml:86-190` — Maven plugins unpack and recompile Calcite parser
[^9]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/parser/SqlCreateTable.java:25-103`
[^10]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/parser/SqlCreateView.java:23-96`
[^11]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/parser/SqlCreateType.java:24-77`
[^12]: `SqlToRelCompiler.java:1813-1844`
[^13]: `SqlToRelCompiler.java:2057-2162`
[^14]: `SqlToRelCompiler.java:626-642`
[^15]: `SqlToRelCompiler.java:363-393`
[^16]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/calciteCompiler/CustomFunctions.java:56-164`
[^17]: `SqlToRelCompiler.java:462-560`
[^18]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/calciteCompiler/optimizer/ExceptOptimizerRule.java:44-172`
[^19]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/calciteCompiler/optimizer/InnerDecorrelator.java:13-43`
[^20]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/calciteCompiler/optimizer/CorrelateUnionSwap.java:52-156`
[^21]: `CalciteToDBSPCompiler.java:320-330`
[^22]: `CalciteToDBSPCompiler.java:985`
[^23]: `CalciteToDBSPCompiler.java:1031-1067`
[^24]: `CalciteToDBSPCompiler.java:1196-1218`
[^25]: `CalciteToDBSPCompiler.java:943-983`
[^26]: `CalciteToDBSPCompiler.java:1521-1650+`
[^27]: `CalciteToDBSPCompiler.java:1141-1162`
[^28]: `CalciteToDBSPCompiler.java:1164-1193`
[^29]: `CalciteToDBSPCompiler.java:372-528`
[^30]: `CalciteToDBSPCompiler.java:543`
[^31]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/ExpressionCompiler.java`
[^32]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/circuit/operator/`
[^33]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/ir/type/`
[^34]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/frontend/TypeCompiler.java:106-260`
[^35]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/ir/type/DBSPType.java:49-57`
[^36]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/ir/expression/`
[^37]: DBSP paper §4 — Incremental evaluation via `D` (differentiate) and `∫` (integrate) operators
[^38]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/backend/rust/RustSqlRuntimeLibrary.java`
[^39]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/CircuitVisitor.java`
[^40]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/inner/`
[^41]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/visitors/outer/monotonicity/MonotoneAnalyzer.java`
[^42]: `sql-to-dbsp-compiler/SQL-compiler/pom.xml:19-20`
[^43]: `sql-to-dbsp-compiler/calcite_version.env:1-8`
[^44]: `sql-to-dbsp-compiler/SQL-compiler/src/main/java/org/dbsp/sqlCompiler/compiler/CompilerOptions.java:41-334`
[^45]: `crates/pipeline-manager/src/compiler/sql_compiler.rs:66`
