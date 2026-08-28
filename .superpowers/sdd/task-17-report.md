# Task 17 Report: Split Log Core and Governance Runtime

## Status

Complete.

The old `com.team4u:team4u-log` artifact is removed without a compatibility bridge. `team4u-log-core` owns the provider-free event, engine, appender, generic pipeline, and test-support APIs. `team4u-log-governance` owns bootstrap, Jackson serialization, Config/Criterion/Mask/Proxy integration, dynamic proxy tracing, and plain Spring configuration.

## Provider Contradiction Resolution

The original Task 17 plan said `team4u-serializer-jackson` remained test-only throughout the task, while its required dependency graph showed `log-governance -> serializer-jackson`. The graph is authoritative for the final state.

The transition rule is now explicit:

- Before Task 17, the old monolith retained its direct Jackson production dependency from Task 7, and `team4u-serializer-jackson` stayed test-only.
- After the split, `team4u-log-governance` is the explicit runtime owner of `team4u-serializer-jackson` and its direct Jackson dependencies.
- `team4u-log-core` owns neither a serializer provider nor Jackson.

This correction was made in the Task 17 brief and the same historical plan line only; no unrelated plan content was edited.

## Runtime Contract

The new external consumer is `src/it/consumer-log-governance`. It imports the root Team4u BOM and declares exactly one project dependency: `com.team4u:team4u-log-governance`.

The Java 8 main proves:

1. `JsonUtil` is active from the transitive `team4u-serializer-jackson` runtime edge by round-tripping a nonempty JSON map.
2. The original core engine emits through `TestLogHelper` and its active serializer output is plain/non-JSON.
3. `LogBootstrap.start()` installs a different global engine.
4. Governance `LogEngine.toJson(LogEvent)` and `TestLogHelper.lastJson()` produce JSON containing the expected action.
5. `LogBootstrap.stop()` restores the original core owner; cleanup is always attempted in `finally`.
6. After cleanup, the original core engine is restored and its output is again plain/non-JSON.

The invoker hook fails unless the runtime tree contains `team4u-log-governance`, transitive `team4u-log-core`, transitive `team4u-serializer-jackson`, and Jackson `jackson-databind`. The fixture has no explicit provider/Jackson dependency and declares only BOM-managed governance.

The complete runtime tree produced by the consumer gate was:

```text
com.team4u.it:consumer-log-governance:jar:1.0.0-SNAPSHOT
\- com.team4u:team4u-log-governance:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-log-core:jar:1.0.0-SNAPSHOT:compile
   |  +- com.team4u:team4u-base:jar:1.0.0-SNAPSHOT:compile
   |  \- com.team4u:team4u-policy:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-serializer-json:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-serializer-jackson:jar:1.0.0-SNAPSHOT:runtime
   |  \- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.16.1:runtime
   +- com.fasterxml.jackson.core:jackson-annotations:jar:2.16.1:compile
   +- com.fasterxml.jackson.core:jackson-core:jar:2.16.1:compile
   +- com.fasterxml.jackson.core:jackson-databind:jar:2.16.1:compile
   +- com.team4u:team4u-config-core:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-criterion:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-mask:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-mask-config:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-mask-jackson:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-proxy:jar:1.0.0-SNAPSHOT:compile
   +- org.springframework:spring-context:jar:5.3.39:compile
   |  +- org.springframework:spring-beans:jar:5.3.39:compile
   |  +- org.springframework:spring-core:jar:5.3.39:compile
   |  |  \- org.springframework:spring-jcl:jar:5.3.39:compile
   |  \- org.springframework:spring-expression:jar:5.3.39:compile
   +- org.springframework:spring-aop:jar:5.3.39:compile
   \- org.slf4j:slf4j-api:jar:1.7.36:compile
```

## Engine Ownership, Appender, and Rate Proof

- `LogBootstrap.start()` builds a new engine with `JacksonLogSerializer`, installs it globally, transfers/rebinds the current appender, and records the previous engine. Duplicate start is ignored.
- `reconfigure(Options)` changes options without replacing the started engine. A failed reconfigure rolls back prior options; if rollback also fails, governance enters `FAILED`.
- `LogBootstrap.stop()` restores the previous engine only while the governance engine is still the global owner. If ownership moved externally, it leaves the newer owner and appender untouched, resets the detached governance engine, cleans repositories, and still enters `STOPPED`.
- `LogEngine.reset()` does not stop governance. It resets the appender, engine-owned default/SPI interceptor state, and serializer state while retaining explicitly injected interceptors.
- Engine appender ownership is covered by install/restore tests: a replacement receives the prior appender, serializer-aware appenders rebind, restore transfers the appender back, and stale ownership restoration fails without changing the newer owner.
- Appender ownership has two lock layers. Static install/restore/global appender operations own the global pointer under `GLOBAL_MONITOR`, then call one engine-local helper at a time. Transfers snapshot one engine's appender, release that local lock, and bind the next engine, so no operation holds two engine locks and the order is always global-to-local. Instance set/CAS first checks ownership lock-free: detached engines mutate only their private `appenderMonitor`, while a current owner rechecks under `GLOBAL_MONITOR` before the local set/CAS. If ownership is lost while waiting, the operation exits global synchronization and completes detached. This serializes current-global writes with install/restore and preserves an install snapshot against a lost update without making detached writes wait on unrelated global operations.
- Transform callbacks and `SerializerAwareLogAppender.bindSerializer` callbacks must be nonblocking and MUST NOT call back into any engine/global appender mutation API. Both can execute while global ownership and the owning engine's local appender synchronization are held; binding can also occur during local-only ownership synchronization.
- `TestLogHelper` owns a private `HelperCompositeLogAppender` with a volatile stopped marker. Starts wrap atomically; stops remove only that helper's memory appender and collapse only stopped single-child helper composites. It never mutates or collapses a user composite and has no static ownership map.
- `LogInterceptorManager` tracks engine-owned interceptors in an identity set guarded against concurrent unregister/reset. Duplicate suppression and removal use instance identity, so equal-but-distinct custom interceptors remain distinct.
- The governance quickstart starts at configured FinOps limit 1, updates the live repository to limit 2 after bootstrap start, clears only the rate counter with `rate.stop()`, and proves two passes followed by suppression of the third same-signature event.
- Rate behavior also covers the default threshold, reset semantics, and preservation of governance policy across independent engine resets.

These behaviors are locked by `LogQuickstartTest` (7 tests), `LogBootstrapTest` (7 tests), `EngineAppenderAtomicityTest` (3 tests), `EngineAppenderLockingTest` (2 tests), `EngineRuntimeIsolationTest` (4 tests), `TestLogHelperOwnershipTest` (5 tests), `RateLimitInterceptorTest` (8 tests), `LogGovernanceQuickstartTest` (2 tests), and related integration tests.
## Source Boundary

`team4u-log-core` contains 23 production files and 20 test files. It has no governance/Jackson/Spring/ByteBuddy/Config/Mask/Criterion/Proxy/provider source reference or dependency. Its only production dependency tree is `team4u-base`, `team4u-policy`, and `slf4j-api`.

`team4u-log-governance` contains 19 production files, 16 test Java files, and one test-only ServiceLoader resource. It directly uses `JsonUtil`, direct Jackson APIs, Config, Criterion, Mask, Proxy, and plain Spring configuration. It has an explicit runtime `team4u-serializer-jackson` edge plus direct Jackson edges. It has no Boot dependency, Boot metadata, factory file, or auto-configuration naming; its Enforcer rule rejects Boot in compile/runtime/test scopes.

## Root Manifest

The root POM has exactly 40 concrete reactor modules and exactly 40 Team4u dependency-management leaves, with no duplicate Team4u dependency-management entry.

- Old `team4u-log` module and DM entry: absent.
- `team4u-log-core`: present in both lists.
- `team4u-log-governance`: present in both lists.

The complete Team4u managed manifest is:

```text
team4u-base
team4u-base-jdbc
team4u-policy
team4u-criterion
team4u-config-core
team4u-config-proxy
team4u-config-spring
team4u-config-db
team4u-config-test
team4u-proxy
team4u-bean
team4u-bean-spring
team4u-router
team4u-router-proxy
team4u-translator
team4u-mask
team4u-mask-jackson
team4u-mask-config
team4u-log-core
team4u-log-governance
team4u-lease-core
team4u-lease-test
team4u-lease-memory
team4u-lease-jdbc
team4u-retry-core
team4u-retry-managed
team4u-retry-config
team4u-retry-proxy
team4u-retry-spring
team4u-retry-lease-runtime
team4u-serializer-json
team4u-serializer-jackson
team4u-kv-core
team4u-kv-space
team4u-kv-lock
team4u-kv-lifecycle
team4u-kv-retryable
team4u-kv-store-jdbc
team4u-kv-store-redis
team4u-kv-test
```

## Move Accounting

The old log source inventory contains 40 production paths and 21 test paths (61 total); every old production/test source path is accounted for in the new modules.

Current split source counts are core 23 production/20 test and governance 19 production/17 test files, totaling 42 production and 37 test files. That is exactly the old inventory plus 2 new production files and 16 new test files.
Fresh Git rename detection at the 90% similarity threshold reports 48 renames:

- 47 are detected as 100% renames.
- `LogProxyTest.java` is detected as a 90% rename because Phase B adjusted its lifecycle cleanup along with the module move.
- Old files modified below the rename-detection threshold remain accounted for by the exact old/new path inventory; Git's 48 rename reports do not establish that every move was detected.
- The old `team4u-log` directory has no remaining source or POM path.
No production or test file from the old monolith is unaccounted for.

## Documentation

Active log documentation now states:

- Core defaults to provider-free RAW/UNMASKED plain `toString` output and explicitly warns that sensitive values are not masked.
- `LogEngine.builder()` injects serializers and interceptors.
- Governance is the explicit owner of Jackson, Config, Mask, Proxy, Criterion, and Spring integration.
- Governance transitively supplies the serializer provider and Jackson runtime.
- Bootstrap start/reconfigure/stop engine ownership semantics are explicit.
- `TestLogHelper.lastJson()` uses the active serializer and may be RAW/UNMASKED plain text in core.
- Core `toJson(LogEvent)` may be RAW/UNMASKED plain text.
- The old monolith is removed with no compatibility artifact.
- `LogEngine.reset()` no longer stops governance.
- Explicit artifact migration is documented in `MIGRATION-1.0.md` and `docs/breaking-changes-1.0.md`.

Root and docs index dependency snippets now show `team4u-log-core` and optional `team4u-log-governance`. Criterion cross-references governance rather than implying core owns the DSL edge. Historical plan references to the old artifact remain intentional; active instructions no longer direct users to add `team4u-log`.

## Test Counts

Fresh full clean test evidence:

- 252 Surefire report files.
- 1,562 tests, 0 failures, 0 errors, 0 skipped.
- `team4u-log-core`: 74 tests, 0 failures/errors/skipped in the original full clean run; 77 tests, 0 failures/errors/skipped after the identity/locking remediation.
- `team4u-log-governance`: 61 tests, 0 failures/errors/skipped.

Final focused identity and locking remediation evidence:

- `-pl :team4u-log-core -Dtest=EngineAppenderLockingTest,LogInterceptorManagerTest,EngineAppenderAtomicityTest,TestLogHelperOwnershipTest -DfailIfNoSpecifiedTests=false test`: exit 0, 16/16.
- The focused set covers detached-engine locking/current-global transfer serialization, identity removal and core ownership, global/instance atomicity, and helper ownership races.
- `-pl :team4u-log-core test`: exit 0, 77/77.
- `-pl :team4u-log-core,:team4u-log-governance -am test`: exit 0, 77/77 core and 61/61 governance.

Historical full clean ownership/remediation evidence:

- Core focused helper/atomicity/manager/rate/isolation/cleanup/helper tests: exit 0.
- Governance focused cleanup/ownership/quickstart/Jackson tests: exit 0 (18 focused tests).
- `TestLogHelperOwnershipTest`: 5 tests, including all six arbitrary stop orders for three nested helpers.
- `LogInterceptorManagerTest`: 5 tests, including equal-but-distinct interceptor coexistence and identity unregister.
- `LogGovernanceQuickstartTest`: 2 tests, including live limit-1-to-2 hot update and third-event suppression.
- `JacksonLogSerializerTest`: 11 tests.

All Maven commands used the absolute root POM `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

1. Focused log-core ownership/atomicity/manager/rate/isolation/cleanup/helper tests: exit 0.
2. Focused log-governance cleanup/ownership/quickstart/Jackson tests: exit 0.
3. Full `team4u-log-core test`: exit 0, 77/77 after the final identity/locking remediation (74/74 in the earlier full clean run).
4. Full `team4u-log-governance test`: exit 0, 61/61.
5. Combined `-pl :team4u-log-core,:team4u-log-governance -am test`: exit 0, 138/138 across the two log modules.
6. Full reactor `clean test`: exit 0, 252 report files and 1,562 tests / 0 failures / 0 errors / 0 skipped.
7. Focused `-Pconsumer-it -Dinvoker.test=consumer-log-governance verify`: exit 0.
8. Full `-Pconsumer-it -DskipTests verify`: exit 0; all six consumers passed sequentially.
9. `-Prelease-contracts -DskipTests verify`: exit 0; all six consumers passed sequentially.
10. `-Prelease -DskipTests package`: exit 0.
11. Source/test-resource partition, Java 8 classfile audit, binary/source/javadoc JAR checks, core forbidden-dependency-bytecode scan, and `git diff --check`: all passed.

No KV heartbeat modification was made. No Task18, KV, or `team4u-id` work is included.

## Java 8 and Release Artifacts

Every production class in both new artifacts has classfile major version 52:

- `team4u-log-core`: 29 classes, 0 non-52.
- `team4u-log-governance`: 34 classes, 0 non-52.

Both artifacts have binary, source, and javadoc jars:

```text
team4u-log-core-1.0.0-SNAPSHOT.jar
team4u-log-core-1.0.0-SNAPSHOT-sources.jar
team4u-log-core-1.0.0-SNAPSHOT-javadoc.jar
team4u-log-governance-1.0.0-SNAPSHOT.jar
team4u-log-governance-1.0.0-SNAPSHOT-sources.jar
team4u-log-governance-1.0.0-SNAPSHOT-javadoc.jar
```

## Review Findings and Remediation

- The initial `TestLogHelper` ownership map was a `WeakHashMap` whose value reached the key wrapper through owner fields, allowing retention of helper metadata and relying on equality-based ownership. It was removed. Ownership is now the private helper composite's volatile stopped marker, with no static map.
- Helper collapse now recurses only through single-child `HelperCompositeLogAppender` instances marked stopped. A user composite and any live helper wrapper remain untouched; all six three-helper stop orders restore the original root.
- Global appender transforms previously rebound even when the transform returned the same appender. They now skip mutation and serializer rebinding for identity-same results while retaining private `GLOBAL_MONITOR` serialization for every appender mutation path.
- Interceptor core ownership used list equality for duplicate checks/removal and was not synchronized against concurrent unregister/reset. It now uses an identity-backed core set, an identity duplicate check, and a private monitor around register/unregister/reset-core; equal-but-distinct interceptors coexist.
- The FinOps quickstart originally changed the limit before bootstrap and therefore did not prove hot update. It now starts at limit 1, puts limit 2 after start, asserts repository value 2, performs a counter-only `rate.stop()`, and asserts pass/pass/suppressed for the same signature.
- The cleanup test name incorrectly described full registry reset semantics. It is now `engineResetFailuresStillStopCoreRepositories`; deterministic cleanup SPI resources remain under `src/test/resources` only.
- Active documentation no longer calls core output "safe plain/安全明文". README, log README/quick-start, and related pages explicitly call it RAW/UNMASKED and warn that sensitive values are not masked.
- Jackson governance coverage is restored in the split (`JacksonLogSerializerTest`, 11 tests), and all report/source/test/JAR/classfile counts were refreshed from the final verification run.
- Final remediation removes unregister by equality via identity `unregisterIf`, proves equal-but-distinct interceptors remain independently ordered at different priorities, gives each engine its own appender monitor, and serializes current-global instance writes with install/restore under global ownership; core 77/77 and governance 61/61 are green together.

## Concerns

- The external consumer uses `LogEngine.processAndOutput` rather than `Loggers.log()` for its deterministic engine/appender assertion because a consumer without an SLF4J backend uses NOP logging and `Loggers` performs backend level filtering. Provider, engine, serializer, helper, and appender behavior remain covered.
- `TestLogHelper.stop()` collapses a stopped helper chain only when that wrapper is still the global root. If ownership has moved externally, it removes its capture appender but intentionally leaves the newer owner's appender graph otherwise untouched.
- `updateGlobalAppender` executes the caller's transform while holding the private engine monitor; transforms must not call appender mutation APIs that re-enter that monitor.
- No Task18 benchmark, `team4u-id`, or KV work is included.
