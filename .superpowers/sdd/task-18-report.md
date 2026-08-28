# Task 18 Final Report: JMH Evidence and Release Gates

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Starting HEAD: `ed3e840be04dd614cf125026687d30ddcc6509fe`
- Production behavior: unchanged.
- Version: `1.0.0-SNAPSHOT`, unchanged.
- No KV, ID, production API, or production source change was made for Task 18.

## Final Evidence

The benchmark README has one row for each of the five measured JMH methods. Exact raw values, confidence intervals, environment, commands, paths, one-fork scope, mean-not-median caveat, container/cgroup caveat, write-through L1 warmup, and below-profiler-resolution wording are recorded there and in `benchmarks/results/`.

| Method | Mean score, 99.9% CI | `gc.alloc.rate` | `gc.alloc.rate.norm` |
| :--- | :--- | :--- | :--- |
| `CriterionMatchBenchmark.compiledPredicate` | `53.720 ± 1.492 ns/op` | `1135.981 ± 31.595 MB/sec` | `64.000 ± 0.001 B/op` |
| `CriterionMatchBenchmark.numericComparison` | `3.974 ± 0.070 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution |
| `RouterRouteBenchmark.routeDecision` | `34.293 ± 0.332 ns/op` | `3336.570 ± 32.368 MB/sec` | `120.000 ± 0.001 B/op` |
| `KvTieredReadBenchmark.l1Read` | `46.153 ± 0.219 ns/op` | `0.008 ± 0.014 MB/sec` | below profiler resolution |
| `ProxyDelegateBenchmark.delegatedNoArgInvocation` | `9.467 ± 0.256 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution |

Raw evidence is in `benchmarks/results/CriterionMatchBenchmark.{json,txt}`, `RouterRouteBenchmark.{json,txt}`, `KvTieredReadBenchmark.{json,txt}`, `ProxyDelegateBenchmark.{json,txt}`, and `environment.txt`. Environment: Corretto 21.0.11, Linux 6.8.12-22-pve, AMD Ryzen 7 7735HS, 16 visible processors. The recorded cgroup CPU/memory maxima were not set (`cpu.max: max 100000`, `memory.max: max`); host visibility can therefore differ from an imposed container quota.

The near-zero numeric, KV, and proxy GC-profiler values are only observations below that profiler's effective resolution for these runs. They are not generalized as `0 B/op`, zero-allocation, 0 GC, or nanosecond-level claims. The active claim scan is green. The only rejected claim wording left under `docs/` is in the excluded historical convergence plan/spec.

## Final Local Gates

All Maven reactor commands used the absolute root `-f` path; the benchmark package used the absolute standalone POM. The runs were sequential, so invoker staging did not overlap.

| Gate | Result |
| :--- | :--- |
| `mvn -f <root>/pom.xml clean install` | PASS; 1,565 tests, 0 failures, 0 errors, 0 skipped; 253 Surefire report files |
| `mvn -f <root>/benchmarks/pom.xml clean package` | PASS |
| `java -jar <root>/benchmarks/target/benchmarks.jar -l` | PASS; exactly 5 methods in 4 classes |
| `mvn -f <root>/pom.xml -Pconsumer-it -DskipTests verify` | PASS; 6 external consumers |
| `mvn -f <root>/pom.xml -Prelease-contracts -DskipTests verify` | PASS; 6 contract consumers |
| `mvn -f <root>/pom.xml -Prelease -DskipTests package` | PASS |
| `scripts/check-release-contracts.sh` | PASS; 40/40 leaves, 40 real dependency trees, 21 exact shapes |
| `scripts/check-performance-claims.sh` | PASS; `performance claims gate: GREEN` |
| Effective root POM Aliyun check | PASS; 0 `maven.aliyun.com` matches |

The benchmark method list is:

```text
com.team4u.bench.CriterionMatchBenchmark.compiledPredicate
com.team4u.bench.CriterionMatchBenchmark.numericComparison
com.team4u.bench.KvTieredReadBenchmark.l1Read
com.team4u.bench.ProxyDelegateBenchmark.delegatedNoArgInvocation
com.team4u.bench.RouterRouteBenchmark.routeDecision
```

## Artifact and Compatibility Audit

The independent DOM helper produced these exact counts:

- Root direct module IDs: 40; duplicates 0; root self-reference 0; legacy `team4u-log` 0; benchmark 0.
- Root dependency-management `com.team4u` IDs: 40; duplicates 0; root self-reference 0; legacy log 0; benchmark 0.
- Module POM artifact IDs: 40; duplicates 0; root self-reference 0; legacy log 0; benchmark 0.
- All three sorted sets are identical.

Every one of the 40 leaves is `jar` packaging. After the final release package, all 40 have the main binary JAR, sources JAR, and javadoc JAR. Missing artifact sets: 0.

Exhaustive class audit:

- 40 production binary JARs.
- 678 production `com/team4u` classfiles, all present in those JARs.
- Byte-level major-version scan: 678/678 major version 52; non-52 count 0.
- Exhaustive `javap -verbose` scan: 678/678 major version 52; non-52 count 0.
- Benchmark target classes: 31/31 major version 52 by both byte-level and `javap` scans; non-52 count 0.

CI YAML parses as a mapping. The build job has JDK matrix `8, 11, 17, 21`, `fail-fast: false`, and sequential gates for build, tests, benchmark package, consumer contracts, release contracts, release artifact script, performance script, and release package.

Scripts are executable (`755`), `bash -n` passes, and `ReleasePomList.java` compiles with `javac -source 8 -target 8` (JDK 21 emits only four expected obsolete-option/bootstrap warnings). `git diff --check` passes. Local Markdown link-target checking found 0 invalid relative paths.

## Release Limitation

The matrix was not executed on JDK 8, 11, and 17 locally: this machine has JDK 21 only. The required matrix evidence is GitHub Actions execution of the checked-in CI file. Task 18 is complete as implementation, evidence cleanup, and local JDK 21 verification, but release/version-change evidence remains incomplete until that matrix passes.

## Findings and Corrections

- `MIGRATION-1.0.md` retained an obsolete 39-leaf Task 16 sentence beside the final 40-leaf sentence. The stale sentence was removed.
- The Task 18 phase report had a truncated dependency-gate proof passage. It now states the altered-tree negative proof completely.
- No final gate flake occurred. No KV source was modified and no focused KV rerun was needed.
