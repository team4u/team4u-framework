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
- `LogEngine.reset()` does not stop governance. It resets the appender, generic interceptor state, and serializer state while retaining the engine's explicitly injected serializer and interceptor composition.
- Engine appender ownership is covered by install/restore tests: a replacement receives the prior appender, serializer-aware appenders rebind, restore transfers the appender back, and stale ownership restoration fails without changing the newer owner.
- Rate behavior is covered by the default-threshold and reset tests. The first configured-threshold error passes, the second is suppressed, an explicit counter reset plus hot update to a higher limit admits new signatures, and core reset/stop ignore configured zero limits and restore the default of 10.

These behaviors are locked by `LogQuickstartTest` (7 tests), `LogBootstrapTest` (7 tests), `RateLimitInterceptorTest` (8 tests), `LogGovernanceQuickstartTest` (2 tests), and related integration tests.

## Source Boundary

`team4u-log-core` contains 23 production files and 13 test files. It has no governance/Jackson/Spring/ByteBuddy/Config/Mask/Criterion/Proxy/provider source reference or dependency. Its only production dependency tree is `team4u-base`, `team4u-policy`, and `slf4j-api`.

`team4u-log-governance` contains 19 production files and 11 test files. It directly uses `JsonUtil`, direct Jackson APIs, Config, Criterion, Mask, Proxy, and plain Spring configuration. It has an explicit runtime `team4u-serializer-jackson` edge plus direct Jackson edges. It has no Boot dependency, Boot metadata, factory file, or auto-configuration naming; its Enforcer rule rejects Boot in compile/runtime/test scopes.

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

Git detects all moved old log files under 90% rename similarity. Of the 63 moved production/test paths:

- 62 are detected as 100% renames.
- `LogProxyTest.java` is detected as a 90% rename because Phase B adjusted its lifecycle cleanup along with the module move.
- The old `team4u-log` directory has no remaining source or POM path.
- Current split source counts are core 23 production/13 test and governance 19 production/11 test, which account for all 63 moved files plus the four Phase B quickstart/serializer additions and the quickstart file now stored as a similarity-based move.

No production or test file from the old monolith is unaccounted for.

## Documentation

Active log documentation now states:

- Core defaults to plain `toString` output and is provider-free.
- `LogEngine.builder()` injects serializers and interceptors.
- Governance is the explicit owner of Jackson, Config, Mask, Proxy, Criterion, and Spring integration.
- Governance transitively supplies the serializer provider and Jackson runtime.
- Bootstrap start/reconfigure/stop engine ownership semantics are explicit.
- `TestLogHelper.lastJson()` uses the active serializer and may be plain in core.
- Core `toJson(LogEvent)` may be plain text.
- The old monolith is removed with no compatibility artifact.
- `LogEngine.reset()` no longer stops governance.
- Explicit artifact migration is documented in `MIGRATION-1.0.md` and `docs/breaking-changes-1.0.md`.

Root and docs index dependency snippets now show `team4u-log-core` and optional `team4u-log-governance`. Criterion cross-references governance rather than implying core owns the DSL edge. Historical plan references to the old artifact remain intentional; active instructions no longer direct users to add `team4u-log`.

## Test Counts

Fresh full clean test evidence:

- 243 Surefire report files.
- 1,527 tests, 0 failures, 0 errors, 0 skipped.
- `team4u-log-core`: 55 tests, 0 failures/errors/skipped.
- `team4u-log-governance`: 45 tests, 0 failures/errors/skipped.

Phase B focused counts remain:

- `LogBootstrapTest`: 7 tests.
- `LogQuickstartTest`: 7 tests.
- `LogGovernanceQuickstartTest`: 2 tests.
- `LogMaskingTest`: 3 tests.
- `LogProxyTest`: 3 tests.
- `RateLimitInterceptorTest`: 8 tests.

## Verification

All Maven commands used the absolute root POM `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

1. `mvn -DskipTests clean install`: exit 0.
2. Focused `consumer-it -Dinvoker.test=consumer-log-governance`: exit 0 after the consumer fixture compile defect was corrected.
3. Full `mvn clean test`: exit 0, 1,527 tests / 0 failures / 0 errors / 0 skipped.
4. `mvn -Pconsumer-it -DskipTests verify`: exit 0; all six consumers passed sequentially.
5. `mvn -Prelease-contracts -DskipTests verify`: exit 0; all six consumers passed sequentially and separately from consumer-it.
6. `mvn -Prelease -DskipTests package`: exit 0.
7. `git diff --cached --check`: exit 0 after whitespace cleanup.

No KV heartbeat modification was made. The KV heartbeat test passed in the full reactor; no flake or focused rerun was required.

## Java 8 and Release Artifacts

Every production class in both new artifacts has classfile major version 52:

- `team4u-log-core`: 27 classes, 0 non-52.
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

## Concerns

- The external consumer uses `LogEngine.processAndOutput` rather than `Loggers.log()` for its deterministic engine/appender assertion because a consumer without an SLF4J backend uses NOP logging and `Loggers` performs backend level filtering. Provider, engine, serializer, helper, and appender behavior remain covered.
- The first focused consumer run exposed a missing fixture helper/variable and the second exposed NOP level filtering. Both were fixture defects; no core redesign was needed.
- No Task18 benchmark, `team4u-id`, or KV work is included.
