# Task 11 Report: Retry Core/Managed/Dynamic Split Without Cycles

## Base

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Input HEAD: `4c6a61f4b3fb686380b4b13417b8ae5d4a627390`
- Implementation commit subject: `refactor(retry): split managed governance without core cycles`

## RED Evidence

- Before adding the new modules to the reactor, both module selections failed because the artifacts did not exist:
  `mvn -q -f <worktree>/pom.xml -pl :team4u-retry-managed -am test`
  failed with `Could not find the selected project in the reactor: :team4u-retry-managed`, and the equivalent
  `:team4u-retry-config` selection failed with the same reactor error.
- Added tests initially exposed incomplete migration while the modules were being wired:
  `ManagedRetriesTest` failed compilation because `ManagedSubmitResult` had not yet moved to
  `com.team4u.framework.retry.managed`, and `DynamicRetryQuickstartTest` failed compilation because
  `DynamicRetryPolicyRegistry` was not yet available in `com.team4u.framework.retry.dynamic`.
- The pre-split retry-core selection still passed after adding only the INLINE quickstart because core retained
  managed APIs at that point. This was not a valid managed RED and is not claimed as one; the real module-missing RED
  above was deliberate and occurred before the new module POMs were registered.

## Moves And Modules

- New leaves: `team4u-retry-managed` and `team4u-retry-config`.
- Moved from retry-core to retry-managed using tracked renames:
  - `managed/client/*`, including `ManagedRetryClient`, `DefaultManagedRetryClient`, and `DurableSuccessWriteException`
  - `managed/dispatch/*`
  - `managed/model/*`
  - `managed/recovery/*`
  - `managed/store/*`, including records and serializers
  - `managed/submit/RetryTaskSpec.java`
  - `ManagedSubmitResult.java`: `com.team4u.framework.retry.api` -> `com.team4u.framework.retry.managed`
  - `DefaultManagedRetryClientTest.java` and `RecoveryExecutionContextTest.java`
- Moved to retry-config:
  - `DynamicRetryPolicyRegistry.java`: `com.team4u.framework.retry.config` -> `com.team4u.framework.retry.dynamic`
  - `DynamicRetryIntegrationTest.java` to the same dynamic package
- Retained in retry-core: `RetryPolicyConfig`, `BackoffConfig`, `RetryPolicyParser`, `RetryPolicy`, and `RecoverySpec`.
- Added `ManagedRetries.with(ManagedRetryClient)` and public nested `ManagedRetries.ManagedExecution`; the DSL
  preserves taskType/idempotencyKey/payload/policy validation, `toSpec`, and submit semantics formerly exposed by
  `Retries.managed(...)`.
- Updated proxy, lease-runtime, Spring, and test call sites to the moved packages and API.
- The only retry ServiceLoader resource remains proxy-owned:
  `com.team4u.framework.retry.managed.recovery.RecoveryHandler -> com.team4u.framework.retry.proxy.InvocationReplay`.
  No service registration moved into retry-core.

## Dependency And Cycle Guards

Observed dependency graphs:

- `retry-core -> base, criterion, policy, serializer-json`; test-only Jackson remains outside runtime.
- `retry-managed -> retry-core, serializer-json`; no config-core or managed-to-core cycle.
- `retry-config -> retry-core, config-core`; config-test/serializer-Jackson are test-only.
- `retry-proxy -> retry-core, retry-managed, retry-config, proxy, bean, serializer-json`.
- `retry-lease-runtime -> retry-core, retry-managed, lease-core, direct Jackson`; the Jackson edge is the documented
  permanent durable-schema integration exception.
- `retry-spring -> retry-core, retry-managed, retry-proxy, bean, Spring`.
- Root module list and root BOM dependency-management list each resolve to the same 34 unique published leaves;
  `team4u-framework` is the BOM and is not included as one of its own modules.
- Source audit found no retry-core reference to `team4u-retry-managed` or `team4u-retry-config`, and no production
  use of the removed `com.team4u.framework.retry.api.ManagedSubmitResult` or old retry-config registry FQCN.

## Verification

All commands used `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

- Focused retry-core selection `RetriesTest,RetryQuickstartTest,RetryPolicyParserTest`: passed.
- Focused retry-managed selection `ManagedRetriesTest,DefaultManagedRetryClientTest`: passed; 24 tests,
  0 failures/errors/skipped.
- Focused retry-config selection `DynamicRetryIntegrationTest,DynamicRetryQuickstartTest`: passed; 2 tests,
  0 failures/errors/skipped.
- Six-module retry acceptance:
  `:team4u-retry-core,:team4u-retry-managed,:team4u-retry-config,:team4u-retry-proxy,:team4u-retry-lease-runtime,:team4u-retry-spring -am test`
  exited 0.
- Full clean reactor `mvn -q clean test`:
  - First run exited 1 only on unrelated `KvLockManagerTest.heartbeatIntervalAdaptsToShortLease`
    (`team4u-kv-lock`, assertion at line 242).
  - Focused rerun `mvn -q -pl :team4u-kv-lock -Dtest=KvLockManagerTest test` exited 0; all 17 tests passed.
  - Fresh full `mvn -q clean test` then exited 0 across all 34 modules; current Surefire reports total 1,462 tests,
    0 failures/errors/skipped.
  - Classification: unrelated, timing-sensitive KV lock flake, not a retry regression. No retry or KV production/test
    code was changed for it, and the same test had already been recorded as a prior full-run flake in Task 2.
- Consumer contracts: `mvn -q -Pconsumer-it -DskipTests verify` exited 0 for all five active invoker projects.
- Release contracts: `mvn -q -Prelease-contracts -DskipTests verify` exited 0 for all five active invoker projects.
- Release packaging: `mvn -q -Prelease -DskipTests package` exited 0 and produced binary/source/Javadoc artifacts for
  retry-core, retry-managed, retry-config, retry-proxy, retry-spring, and retry-lease-runtime.
- Java 8 evidence: all 682 production class files under `target/classes` report class-file major version 52.0
  (`0x34`); release Javadoc packaging also completed successfully.
- API evidence: compiled retry-core exposes only `Retries.inline()` as the facade entry; compiled managed API exposes
  `ManagedRetries.with(...)` and public fluent `ManagedRetries.ManagedExecution`.
- `git diff --check` passed before commit.

## Documentation And Scope

- Added the three required retry migration entries to `MIGRATION-1.0.md`.
- Added the retry module split to `docs/breaking-changes-1.0.md`.
- Updated retry README, quick-start, managed/proxy/sample/Spring/strategy docs and the convergence plan boundary
  wording (34 leaves at Task11; future leaves arrive in later tasks).
- No generated `target/` content is tracked, no generated Javadoc/API files are tracked, and no unrelated docs were
  deleted.
- No Task12 KV split, `team4u-id`, master integration, or future module was introduced.

## Self-Review

- The package move is intentionally breaking and is covered by both migration and breaking-change documents.
- `ManagedRetries` retains the old managed DSL validation and result behavior without reintroducing managed types
  into retry-core.
- The dynamic package rename preserves core-owned parser/DTO FQCNs while moving only registry integration to
  retry-config.
- Dependency directions and test-only edges match the approved Task11 graph.
- The retry-contract and release-contract profiles validate the new leaves without weakening their Jackson/provider
  guards; the lease-runtime Jackson exception remains explicit.
- Remaining concern: the KV short-lease heartbeat test is timing-sensitive and has now failed in unrelated full runs
  more than once. It is outside Task11 and should be stabilized before the final convergence release.
