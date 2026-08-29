# Master Merge Report — framework convergence

> Status: the merge commit `6a2ef9cc` (`merge: integrate master features with converged boundaries`, parents `57b4865a` + `100f7bc9`) is concluded. The final merge commit has been re-verified with the full local Docker JDK matrix (see Local Docker matrix on the merge commit). Remaining gate: hosted GitHub Actions JDK 8/11/17/21 matrix (see Pending gates).

## Merge geometry

| Item | Value |
| :--- | :--- |
| Ours (feature) | `57b4865a0881953bdd381ccb7ea6f1c17b656c98` (`refactor/framework-convergence`, `docs: synchronize component artifact indexes`) |
| Theirs (master, MERGE_HEAD) | `100f7bc9e9f4a9daa738b09c1b88ec726063339c` (`test: 慢测试治理——固定 sleep 换条件等待、时间标尺最小化（纯测试 44.9s→24.3s）`) |
| Merge base | `11e1eec32a2704befa1976c1dd4d828011b24f21` |
| Master ahead of base | 26 commits (id, ratelimiter, singleflight, proxy-spring, KV capability changes, shared-capability convergence, mask SPI rollback, slow-test governance, docs) |
| Feature ahead of base | Tasks 1–18 of the convergence plan (40-leaf split, release gates) |

## Conflict decisions (21 conflicted paths)

Resolution principle: the convergence-plan artifact boundaries win on structure (module layout, POM dependencies, provider ownership); master's functional content is preserved in full and adapted to those boundaries. No master feature was dropped.

| Area | Conflicted paths | Decision |
| :--- | :--- | :--- |
| Root docs | `README.md`, `docs/README.md` | Both index tables list the master artifacts under their post-merge split IDs: `team4u-id`, `team4u-ratelimiter-core/-proxy/-spring`, `team4u-singleflight-core/-proxy/-spring`, `team4u-proxy` + `team4u-proxy-spring`; `NamedKvStoreRegistry` attributed to `team4u-kv-space`. |
| Base docs | `docs/base/README.md`, `docs/base/base-refresh.md` | Master's rewritten refreshable-value docs kept (scenario-first structure); performance wording softened to mechanism descriptions per the claims gate. |
| Root POM | `pom.xml` | 48 modules kept; `consumer-it` and `release-contracts` profiles extended to 8 pomIncludes each (added `consumer-ratelimiter-core`, `consumer-singleflight-jackson`); dependencyManagement extended with the 8 new leaves. |
| Log governance | `team4u-log-governance/pom.xml`, `LogInvocation.java`, `Team4uMethodInvocationAdapter.java` | Plan-owned boundary kept (governance owns the Jackson provider); master's shared-capability convergence (dba0b5ba: duplicate-implementation elimination across modules) applied inside that boundary. |
| Mask | `team4u-mask/pom.xml`, `FastMasker.java`, `MaskRuleRepository.java`, `team4u-mask-jackson/.../MaskedJson.java`, boundary test | Master's global-SPI-registry rollback (65e48ecf, lossless-contract regression) kept; mask core stays `team4u-base` + `team4u-policy` only; mask-jackson keeps the direct Jackson API without passing `team4u-serializer-jackson`. |
| Retry | `Retries.java`, `DefaultManagedRetryClient.java`, `VersionedRetryRecordSerializer.java`, `SpringRetryInterceptor.java` | Task 11 split kept (core INLINE-only, managed/config/proxy/spring separate); master's shared-capability convergence and versioned-record serializer docs preserved; `retry-spring` keeps the `team4u-bean-spring` shared configuration import. |

New consumers added (untracked → staged): `src/it/consumer-ratelimiter-core/`, `src/it/consumer-singleflight-jackson/`.

## Final artifact graph (48 leaves)

Root `team4u-framework` (the only BOM) → 48 leaf modules. Key production edges (direct, compile/runtime scope, optional/test excluded):

```text
team4u-proxy                 → base                                    (no Spring)
team4u-proxy-spring          → proxy, spring-context, spring-aop       (Enforcer: no ByteBuddy compile/runtime)
team4u-id                    → base, policy, config-core, kv-core, kv-space, serializer-json
team4u-ratelimiter-core      → base, policy, config-core, kv-core, kv-space, serializer-json
team4u-ratelimiter-proxy     → proxy, ratelimiter-core
team4u-ratelimiter-spring    → proxy-spring, ratelimiter-proxy
team4u-singleflight-core     → base, policy, config-core, criterion, kv-core, kv-lock, kv-space, serializer-json, jackson-databind (direct durable-schema exemption)
team4u-singleflight-proxy    → proxy, singleflight-core
team4u-singleflight-spring   → proxy-spring, singleflight-proxy
team4u-kv-core               → base, slf4j-api                          (base-only; no policy/serializer/proxy)
team4u-kv-space              → kv-core, policy, serializer-json         (Space/Spaces + NamedKvStore/NamedKvStoreRegistry, FQCN unchanged)
```

The pre-merge monoliths `team4u-ratelimiter` and `team4u-singleflight` do not exist; the grouping-artifact ban (config/kv/lease/retry/serializer, `team4u-log`) still holds.

## Master features preserved

- **id**: config-driven sequence generation on kv `CounterCapable` — group reset (DATE/EXT), quota exhaustion, recycle, local segment (lazy fetch + CAS + LRU), template formatting; cross-backend contract tests (d3bdca15).
- **ratelimiter**: four algorithms (fixed window, token bucket, precise sliding window, client history window) over kv capability negotiation, rule-chain priority, failOpen, `@RateLimit` annotation, value-typed rejection semantics (aee8399b), rule-model convergence (32426680).
- **singleflight**: same-key unique executor, WAIT/FAIL_FAST/FALLBACK contention, result caching, failure-session sharing, crash takeover, token fencing; engine layering (6e4670cc), errorFallback (1b82c77c), key digest renamed to manual per-rule `keyDigest` with pluggable named algorithms (3a8b3ae5).
- **proxy-spring**: `AnnotationProxyBeanPostProcessor` shared wiring template used by ratelimiter-spring and singleflight-spring.
- **shared capabilities**: cross-module duplicate-implementation elimination (dba0b5ba) applied within plan boundaries.
- **KV contract changes**: `CounterCapable` TTL expiry-reset (c04115d2, 131d40ec), `ScoredWindowCapable` ordered window, multi-generation lock-hold records with heartbeat/renew concurrency fixes (694129ba).
- **mask**: global SPI registry rolled back to the lossless-contract path (65e48ecf, 7083343c).
- **slow-test governance**: fixed sleeps → conditional waits, minimal time scales, pure tests 44.9s→24.3s (100f7bc9).
- **base refreshable-value docs** rewritten scenario-first (85a58acf, 3cf1a274).

## Security and boundary decisions

1. **New cores carry no proxy/Spring/provider edges**: `team4u-id`, `team4u-ratelimiter-core` have zero production dependencies on proxy artifacts, Spring, or any JSON provider (verified by static POM audit of direct compile/runtime dependencies; junit/spring-test/slf4j-simple are root-managed test scope).
2. **Proxy/spring edges**: `proxy` stays Spring-free (base only); `proxy-spring` adds spring-context/spring-aop and is Enforcer-banned from ByteBuddy compile/runtime; component spring adapters (`ratelimiter-spring`, `singleflight-spring`) depend only on `proxy-spring` + their proxy adapter, not on core directly.
3. **kv-core base-only**: `team4u-kv-core` production dependencies are `team4u-base` + `slf4j-api`; `NamedKvStoreRegistry`/`NamedKvStore` moved to `team4u-kv-space` with unchanged FQCNs.
4. **singleflight-core direct databind**: `jackson-databind` is a direct nonoptional compile edge for the durable session-envelope tree schema and `TypeFactory` type introspection in the fallback converter. The fallback *bean conversion* itself goes through the `JsonUtil` SPI (application-owned provider semantics: JavaTimeModule, custom modules, unknown-field tolerance — identical to master's shared mapper), so no private bare ObjectMapper survives in core. This is a distinct, documented exemption (same family as `team4u-retry-lease-runtime`): it does **not** provide `team4u-serializer-jackson`; rule parsing and fallback bean conversion both go through the `JsonUtil` SPI and the application must provide a provider explicitly. The adapters inherit only the raw databind artifact, never the provider (release-contract script: 5 Jackson owners, 2 databind heirs, provider leakage forbidden for heirs).
5. **Cycle-free**: a DFS over the 48-leaf Team4u dependency graph finds no cycles.
6. **ByteBuddy**: optional everywhere except `team4u-config-proxy` (unchanged from the plan); proxy-spring is Enforcer-enforced ByteBuddy-free.

## Verification performed (final JDK 21 non-JMH gates, 2026-08-29)

All runs executed from the fully staged merge worktree with the root POM (`mvn -f` absolute), shared invoker runs strictly sequential:

- **Full clean install, tests on**: `mvn clean install` → **BUILD SUCCESS, 49/49 modules (48 leaves + root), 1,907 tests, 0 failures, 0 errors, 0 skipped** across the 47 modules carrying tests (no Failsafe executions in the default build; integration gates are the invoker profiles below). No KV-heartbeat transient occurred; no reruns needed; no test or production source modified this round.
- **Benchmark standalone**: `mvn -f benchmarks/pom.xml clean package` → BUILD SUCCESS; `java -jar benchmarks/target/benchmarks.jar -l` lists **exactly 5** JMH benchmarks (`CriterionMatchBenchmark.compiledPredicate/numericComparison`, `KvTieredReadBenchmark.l1Read`, `ProxyDelegateBenchmark.delegatedNoArgInvocation`, `RouterRouteBenchmark.routeDecision`); no JMH execution.

## JMH evidence (2026-08-29, merge candidate)

Formal rerun on the fully staged merge state (same host Corretto 21.0.11,
Linux 6.8.12-22-pve, Ryzen 7 7735HS, 16 visible processors; environment and
commands identical to the pre-merge evidence — see `benchmarks/results/environment.txt`).
Reactor snapshots reinstalled from this worktree (`mvn -DskipTests clean install`,
absolute root POM), then `mvn -f benchmarks/pom.xml clean package`; each class run
strictly sequentially with `-prof gc -f 1 -wi 3 -i 5` (annotation-derived 1s
iterations), JSON + text evidence written to `benchmarks/results/<Class>.{json,txt}`.
`-l` confirmed exactly 5 benchmarks before the runs. This rerun supersedes the
Task 18 report's recorded numbers for the merge candidate (the historical
report is left unmodified).

| Benchmark | Mean score, 99.9% CI | `gc.alloc.rate` | `gc.alloc.rate.norm` | vs pre-merge |
| :--- | :--- | :--- | :--- | :--- |
| Criterion compiledPredicate | `54.663 ± 1.368 ns/op` | `1116.413 ± 27.804 MB/sec` | `64.000 ± 0.001 B/op` | +1.8% |
| Criterion numericComparison | `4.033 ± 0.026 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution | +1.5% |
| Router routeDecision | `35.526 ± 0.812 ns/op` | `3220.778 ± 73.606 MB/sec` | `120.000 ± 0.001 B/op` | +3.6% |
| KvTiered l1Read | `45.991 ± 1.682 ns/op` | `0.008 ± 0.014 MB/sec` | below profiler resolution | +0.03% |
| Proxy delegatedNoArgInvocation | `9.462 ± 0.226 ns/op` | `0.007 ± 0.001 MB/sec` | below profiler resolution | −0.05% |

Regression gate: every mean moved < 4% (largest Router +3.6%), well inside the
20% threshold; both measurable `gc.alloc.rate.norm` values are unchanged
(`64`/`120 B/op`). No optimization follow-up is warranted on these numbers.
The tiered-KV trial-level teardown assertion (`counting L2 == 0 get() calls`
across warmup plus all measured iterations) **passed** on the rerun (exit 0,
no `AssertionError` in the recorded text output).
- **Consumer contracts**: `mvn -Pconsumer-it -DskipTests verify` → invoker **Passed: 8, Failed: 0, Errors: 0, Skipped: 0** (25.8s). **Release contracts**: `mvn -Prelease-contracts -DskipTests verify` (separate invocation) → **Passed: 8, Failed: 0, Errors: 0, Skipped: 0** (26.0s).
- **Release-contract script executed**: `scripts/check-release-contracts.sh` → exit 0, message `release contracts: 48/48 leaves, Jackson owners, and effective POM repository verified`. 48 real `dependency:tree -Dscope=runtime` runs each produced a nonempty `outputFile` tree containing its owner row; 30 exact direct-shape assertions GREEN; Jackson owner count 5 (`serializer-jackson`, `mask-jackson`, `log-governance`, `retry-lease-runtime` provider rows present; `singleflight-core` databind-only exemption) with heirs 2 (`singleflight-proxy`/`singleflight-spring`, provider forbidden) and zero provider/Jackson leakage elsewhere; effective root POM contains 0 `maven.aliyun.com` references. Script content unchanged this round — its staged version already matched the merged reactor.
- **Release packaging**: `mvn -Prelease -DskipTests package` → BUILD SUCCESS (57.8s); **48 binary + 48 sources + 48 javadoc = 144 jars**. DOM audit: root `<modules>` = 48 = root dependencyManagement com.team4u entries = leaf artifactIds, sets identical (no diff), root not self-listed. **Bytecode: 792 production class files across the 48 binary JARs, 792/792 major 52** (0 non-52). Benchmarks: own `com/team4u/bench` classes 31/31 major 52 (fat `benchmarks.jar` additionally contains unmodified upstream JMH/joptsimple bytecode at major 49/51 — third-party content, not ours).
- **Hygiene**: `bash -n` both scripts OK; CI YAML parses, matrix `['8','11','17','21']` unchanged vs HEAD; 9 IT helper sources (8 Mains + `MiniJsonPolicy`); repository conflict-marker scan 0; README 36 links + docs `_sidebar` links all resolve; `git diff --check` clean; unstaged = 0, untracked = 0, unmerged = 0; `MERGE_HEAD` = `100f7bc9e9f4a9daa738b09c1b88ec726063339c` retained.

Earlier rounds additionally stand: performance claims gate GREEN (142 docs + 594 Java sources scanned; 6 post-merge claims softened to mechanism wording), static POM boundary audit, docs sync with 0 broken links.

## Local Docker matrix on the merge commit (2026-08-29)

After the merge commit `6a2ef9cc` was created, the full ten-step gate sequence was re-executed locally in Docker against that exact commit, one container per JDK, strictly serial (no overlap: JDK 8 16:36–16:42, JDK 11 16:42–16:48, JDK 17 16:48–16:54, JDK 21 16:54–17:00) so the shared invoker staging directories are never contended. Images: `maven:3.9.11-eclipse-temurin` 8/11/17/21 — Maven 3.9.11 (`3e54c93a704957b63ee3494413a2b544fd3d825b` throughout) on Temurin `1.8.0_472`, `11.0.29`, `17.0.17`, `21.0.9` (JDK 8 vendor line reads `Temurin`; 11/17/21 read `Eclipse Adoptium` — same distribution family). Logs: `/tmp/team4u-merge-jdk-{8,11,17,21}-matrix.log`, each ending `ALL-STEPS-OK` with 22 `BUILD SUCCESS` markers and 0 `BUILD FAILURE`.

Isolation: each container used a fresh, throwaway local repository `/tmp/team4u-merge-m2` (created 16:40 by the first run, reused only across these four serial runs, never the host `~/.m2`), so all dependencies and the 48 reactor snapshots were resolved and installed from this merge state only. No JMH measurement was executed in the matrix (step 4 builds the benchmark jar and asserts `-l` = exactly 5); the formal JMH evidence above — run on the host Corretto 21.0.11 environment — is untouched by this round.

Per-JDK results (identical on all four):

- `mvn -DskipTests clean install` then `mvn test`: 49/49 modules, **1,914 tests / 0 failures / 0 errors / 0 skipped** per JDK (47 modules carrying tests; 1,914 = 1,907 previous + 7 `FallbackConverterTest`). No KV transient this round — the only `transient` strings in the logs are expected negative-path log frames from passing tests (e.g. `ScheduledHeartbeat` renew-error keep-trying tests); zero test reruns, zero failures anywhere.
- Benchmark standalone `clean package`: BUILD SUCCESS; `BENCH_METHODS=5 BENCH_CLASSES=4`; shaded jar lists the same 4 benchmark classes.
- `consumer-it` profile invoker: **Passed: 8, Failed: 0, Errors: 0, Skipped: 0** (read from the invoker `Build Summary` block — 8, not 6).
- `release-contracts` profile invoker (separate invocation): **Passed: 8, Failed: 0, Errors: 0, Skipped: 0**.
- Checked-in release script `scripts/check-release-contracts.sh`: exit 0, `release contracts: 48/48 leaves, Jackson owners, and effective POM repository verified` — **48 real runtime `dependency:tree` outputFile trees, 30 exact direct shapes, Jackson owners 5 / databind heirs 2**, provider leakage 0.
- Performance claims gate: self-test 43 positive + 34 negative cases all as expected, 142 docs + 594 Java sources scanned, **gate GREEN**.
- `mvn -Prelease -DskipTests package`: **MAIN_JARS=48, SRC_JARS=48, JD_JARS=48 (48×3 = 144)**; production bytecode **792 class files, NON52=0** (792/792 major 52); own benchmark classes major 52 (`BENCH_NON52=0`).

A local Docker matrix is not hosted execution: the GitHub Actions workflow remains the only pending gate, and no release or version flip may be made from local-only evidence.

## Counts

| Metric | Value |
| :--- | :--- |
| Final reactor leaves | 48 (40 + 8 merged) |
| Full clean install | BUILD SUCCESS, 1,907 tests / 0 fail / 0 error / 0 skip (JDK 21 host); merge-commit Docker matrix 1,914 per JDK 8/11/17/21 |
| Release-contract direct shapes | 30 (22 pre-merge + 8 post-merge leaves), all GREEN when executed |
| Release-contract outputFile trees | 48/48 real, nonempty, owner-row checked |
| Jackson owners / databind heirs | 5 / 2 (provider leakage 0; aliyun refs 0) |
| Consumer-it / release-contracts consumers | 8/8 and 8/8 passed |
| Release jars | 48 binary + 48 sources + 48 javadoc = 144 |
| Production class files / major 52 | 792 / 792 |
| Benchmark JAR `-l` | exactly 5 benchmarks; own classes 31/31 major 52 |
| JMH rerun (merge candidate) | 5/5 methods measured; all means within 4% of pre-merge; norms unchanged (64/120 B/op); KV L2-zero teardown passed |
| Merge-commit Docker matrix | 4/4 JDKs (Temurin 8/11/17/21, Maven 3.9.11) ALL-STEPS-OK: 1,914 tests, 5-method bench jar, 8+8 consumers, 48 trees / 30 shapes / owners 5 + heirs 2, claims GREEN, 48×3 jars, 792/792 major 52, no KV transient |

## Pending gates

1. ~~JMH benchmark rerun~~ — **complete** (2026-08-29, see JMH evidence above: 5/5 methods, no regression beyond 20% — largest movement +3.6%, allocation norms unchanged).
2. ~~Final review of the merge~~ — **complete**: 3 independent review areas (singleflight fallback converter semantics, serializer provider boundaries, docs/index consistency) returned no Critical and no Important findings; the single fallback finding (I-1, `FallbackConverter` bean conversion must ride the application-owned shared JSON provider — unknown-field tolerance, JavaTimeModule, custom modules — instead of a bare private ObjectMapper) is fixed with 7 dedicated tests in `team4u-singleflight-core` (`FallbackConverterTest`), all GREEN in the 1,907-test full run.
3. Hosted GitHub Actions JDK 8/11/17/21 matrix — the only remaining gate, required before any release or version flip. The merge commit `6a2ef9cc` has now passed the full ten-step gate sequence locally in Docker on Temurin 8/11/17/21 (Maven 3.9.11, fresh `/tmp/team4u-merge-m2`, serial containers; see Local Docker matrix on the merge commit), but a local Docker matrix is not hosted execution — hosted Actions have not been run and must not be assumed to pass.
4. ~~The merge commit itself~~ — **complete**: non-ff merge commit `6a2ef9cc217c226253b562dfc2ece40d61c8b364` with parents `57b4865a` (feature, first) and `100f7bc9` (master, second), subject `merge: integrate master features with converged boundaries`.
