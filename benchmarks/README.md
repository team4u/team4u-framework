# Team4u JMH Benchmarks

This is a standalone, unpublished Maven project. It inherits Java 8 compiler and
release settings from the root POM but is intentionally absent from the root
module list and does not publish an artifact.

## Covered Hot Paths

- `CriterionMatchBenchmark`: two measured paths: a precompiled logical/property
  expression (`age >= 18 && status == 'ACTIVE'`) against a reused map-backed
  `MatchContext`, and a separate precompiled subject numeric expression
  (`it > 18`) against a reused numeric `MatchContext`. Each benchmark method
  executes only `MatchPredicate.test`.
- `RouterRouteBenchmark`: a prebuilt `ExpressionRouter` / `RoutePolicy` route
  decision against a prebuilt `MatchContext`; no policy parsing happens in the
  measured method.
- `KvTieredReadBenchmark`: a `TieredStore` L1 hit for a stable, unexpired key
  and record. Setup's write-through warms L1; it performs no read-time backfill,
  and a counting L2 receives zero `get` calls during setup and the measured
  operation. A trial-level `@TearDown` re-asserts `l2.getCalls == 0` before
  closing the store, so every recorded run is proven to have never touched L2
  across warmup and all measured iterations combined; the assertion message
  carries the actual nonzero count if it ever fails, and `close()` still runs
  from a `finally` block so the store is closed either way.
- `ProxyDelegateBenchmark`: a prebuilt JDK interface proxy delegating one
  no-argument method to its target; ByteBuddy is not involved.

All benchmark classes use `@State(Scope.Thread)`, average-time mode,
nanosecond output, one fork, three 1-second warmups, and five 1-second
measurements. Setup contains all compilation, construction, context creation,
warm reads, and behavioral sanity assertions.

## Reproduction

After installing the current reactor artifacts, package from the repository
root, then run all classes from `benchmarks/`:

```bash
mvn -q -f benchmarks/pom.xml clean package
cd benchmarks

java -jar target/benchmarks.jar CriterionMatchBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/CriterionMatchBenchmark.json
java -jar target/benchmarks.jar RouterRouteBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/RouterRouteBenchmark.json
java -jar target/benchmarks.jar KvTieredReadBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/KvTieredReadBenchmark.json
java -jar target/benchmarks.jar ProxyDelegateBenchmark -prof gc -f 1 -wi 3 -i 5 -rf json -rff results/ProxyDelegateBenchmark.json
```

These same relative paths also identify the committed evidence from the
repository root: `benchmarks/results/<Class>.json`. If you remain at the root,
either prefix the JAR path with `benchmarks/` or first `cd benchmarks`; do not
mix the two working-directory conventions. Add `-wf 1` to make the 1-second
annotation-derived warmup time explicit. Environment details and raw text/JSON
evidence are kept in `benchmarks/results/`.

The commands already run from `benchmarks/`, so JMH writes `-rff` under
`benchmarks/results/`. Read `gc.alloc.rate.norm` from the GC profiler output as
the observed allocation per operation under this specific run.
The score column from JMH average-time mode is a mean, not a median. A single short run does not
prove a general absolute-allocation or fixed-latency contract: JIT behavior, CPU
frequency, container quota, memory layout, and co-tenancy can all change the
result. Numbers should be compared only with the recorded environment and
command.

## Recorded Results

JDK 21.0.11 (Corretto), Linux 6.8.12-22-pve, AMD Ryzen 7 7735HS, 16 visible
processors, and the cgroup quota recorded in `results/environment.txt`. One
single-threaded fork was used; each caveat above still applies.

| Benchmark | Mean score, 99.9% CI | `gc.alloc.rate` | `gc.alloc.rate.norm` | Raw evidence |
| :--- | :--- | :--- | :--- | :--- |
| Criterion logical/property predicate | `53.720 ± 1.492 ns/op` | `1135.981 ± 31.595 MB/sec` | `64.000 ± 0.001 B/op` | `results/CriterionMatchBenchmark.{json,txt}` |
| Criterion subject numeric comparison | `3.974 ± 0.070 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution | `results/CriterionMatchBenchmark.{json,txt}` |
| Router route decision | `34.293 ± 0.332 ns/op` | `3336.570 ± 32.368 MB/sec` | `120.000 ± 0.001 B/op` | `results/RouterRouteBenchmark.{json,txt}` |
| Tiered KV L1 read | `45.978 ± 1.279 ns/op` | `0.008 ± 0.014 MB/sec` | below profiler resolution | `results/KvTieredReadBenchmark.{json,txt}` |
| Proxy delegated invocation | `9.467 ± 0.256 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution | `results/ProxyDelegateBenchmark.{json,txt}` |
