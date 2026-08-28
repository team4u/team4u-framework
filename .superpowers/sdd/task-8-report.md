# Task 8 Report: Config Dependency Inversion and Global Initialization Fix

## Base

- Task8 implementation commit: `8bebace4cba1d0dbf119755f481d0ab6d21c3063`
- First concurrency/provider remediation commit: `c280f08afe12a78505eaefa41d4e47687262c419`
- Worktree: `.worktrees/framework-convergence`
- Current fix commit: the dirty changes after `c280f08`, committed as `fix(config): preserve refresh and repeated debounce`

## Exact RED At c280

The disposable baseline worktree `/tmp/task8-red` ran the new retained tests against the unchanged `c280f08` implementation.

`ConfigGlobalLifecycleConcurrencyTest.lateRegistrationWaitsForInitializationAndRefreshesSameGlobal` failed after 10.02 seconds with:

```text
java.lang.AssertionError: registration refresh must block on the global monitor
```

The late source was already registered while global initialization was blocked in its source load; `refreshGlobalIfInitialized()` read a still-null global and returned instead of waiting for initialization and refreshing that same manager.

`internal.HotReloadManagerTest` ran 5 tests with 2 failures and 0 errors:

- `twoSequentialPositiveDelayReloadsBothCommit`: timed out waiting for the second commit because `pendingTaskAssigned` was never cleared after the first debounce task ran.
- `newerSignalInvalidatesRunningReloadAndKeepsNewPendingHandle`: the stale running reload committed (`expected:<0> but was:<1>`), and the newer pending task handle was not preserved.

The baseline worktree was removed after these results were recorded.

## Production Behavior

### Serialized Global Refresh

`ConfigManager.class` now serializes absent-global initialization, initialized-global refresh, test read/discard of the global reference, and bootstrap reset:

- `DefaultConfigManager.global()` initializes only under the class monitor.
- `refreshGlobalIfInitialized()` reads and refreshes `global` under the same class monitor. A late bootstrap registration during initialization waits, then refreshes the manager created by that initialization rather than silently returning on a stale null read.
- `globalOrNullForTests()` reads under the class monitor.
- `discardGlobalForTests()` clears the reference under the class monitor after the production reset path has destroyed the manager.
- Manager `refresh()` remains serialized separately by the manager lifecycle monitor.

### Reload-Token Algorithm

`HotReloadManager` replaces the one-shot pending-task latch with monotonic invalidation:

- `signalChange()` allocates `token = ++reloadToken` under the manager monitor and chooses the current debounce delay.
- A positive delay calls `scheduleReload(token, delay)`. If acceptance or token currency changed while scheduling, the signal does nothing. Otherwise it cancels any prior pending task without interrupting a running body, records `pendingReloadToken`, schedules the new task, and stores `pendingReloadTask`.
- `cancelPendingReload()` increments `reloadToken`, stops accepting, and clears the pending handle.
- `resumeAcceptingReloads()` increments `reloadToken`, resumes acceptance, and clears any stale pending handle before later signals.
- `runReload(token)` executes under a separate reload-execution monitor. It rechecks currency before aggregation and before commit. A stale running body returns without loading again, committing, or clearing a newer pending handle. Its cleanup clears the handle only when `token == pendingReloadToken`.
- The manager committer performs the final currency check under the manager lifecycle monitor, swaps the snapshot, and fires listeners only after manager and reload monitors are released.

Consequences:

- Sequential positive-delay changes can each commit after their debounce windows; completing a task no longer permanently disables future debounce.
- A later pending signal cancels only the earlier queued body and aggregates once with the latest state.
- Reset/resume invalidates both a running aggregation and a queued task.
- A stale running reload cannot overwrite a newer signal, and it cannot erase the newer pending task handle.

## Provider Contracts

- `ConfigProxyCreator.create(ConfigProxyContext, String, Class<T>)` is the core-owned proxy boundary.
- `ConfigProxyContext` exposes the owning `ConfigManager` and exact manager `PropertyConverterRegistry`.
- Creator resolution is explicit builder value, then at most one `ServiceLoader<ConfigProxyCreator>` provider, then absent. Two providers fail fast with both implementation names.
- Provider construction/discovery failures are wrapped in an `IllegalStateException` naming provider discovery and retaining the original `ServiceConfigurationError`/cause and provider implementation name.
- A missing creator makes `createProxy` fail fast with the `team4u-config-proxy` guidance and never substitutes a bound POJO.
- `ConfigProxyFactory implements ConfigProxyCreator` remains the temporary Task8 bridge and requires a non-null context with the context converter registry.
- `DefaultConfigBinder.bind(...)` remains explicit snapshot binding only; final prefix composition happens before proxy caching/creator invocation.

## Retained Tests

Exact current counts:

- `ConfigGlobalLifecycleConcurrencyTest`: 7 tests.
  - `resetOfAbsentGlobalDoesNotInitializeIt`
  - `lateRegistrationWaitsForInitializationAndRefreshesSameGlobal`
  - `normalResetPreservesGlobalInstance`
  - `resetWaitsForConcurrentInitializationAndClearsItsResult`
  - `resetInvalidatesInFlightReloadAndRefreshResumesAcceptance`
  - `resetCancelsQueuedReload`
  - `synchronousReloadListenerCanResetManagerWithoutDeadlock`
- `internal.HotReloadManagerTest`: 5 tests.
  - `queuedCancellationPreventsReloadBodyFromExecuting`
  - `resetThenResumeAcceptsANewSignal`
  - `twoSequentialPositiveDelayReloadsBothCommit`
  - `laterPendingSignalCancelsEarlierQueuedReloadBody`
  - `newerSignalInvalidatesRunningReloadAndKeepsNewPendingHandle`

The single reflective read in `HotReloadManagerTest` is intentionally narrow: after proving the stale running reload cannot commit, it verifies that the stale cleanup did not null the newer `pendingReloadTask` handle. It does not drive production behavior through reflection.

Other retained Task8 coverage:

- `ConfigPureJavaQuickstartTest`: 3 tests.
- `ConfigProxyProviderContractTest`: 4 tests.
- `ConfigProxyCreatorResolutionTest`: 1 test.
- `ConfigGlobalInitializationTest`: 3 tests.
- `ConfigProxyCreatorServiceLoaderTest`: 4 tests.
- `ConfigProxyFactoryContextTest`: 1 test.

Focused command and result:

```bash
mvn -q -Dtest=ConfigGlobalLifecycleConcurrencyTest,internal.HotReloadManagerTest test
```

Result: 12/12 passed, exit 0.

## Verification

All commands below ran at dirty Task8 fix state after `c280f08`, before the final commit:

- Config-core full module: `mvn -q clean test` passed; 26 suites, 108 tests, 0 failures, 0 errors, 0 skipped.
- Config acceptance: `mvn -q -pl :team4u-config-core,:team4u-config-db,:team4u-config-test -am test` passed.
- Worktree artifact install for external consumers: `mvn -q -DskipTests install` passed.
- Affected downstream: `mvn -q -pl :team4u-log,:team4u-router,:team4u-retry-core -am test` passed; log 97/97, router 69/69, retry-core 72/72.
- Explicit config consumer standalone: `mvn -q clean verify` ran Java 8 compilation and `ConfigCoreMain` successfully. The Invoker fixture is the required RED path.
- Exact proxy-only RED: `mvn -q -Pconsumer-it -Dinvoker.test=consumer-config-core -DskipTests verify` exited 1 as required. The generated runtime tree has exactly one banned edge: `com.team4u:team4u-proxy:jar:1.0.0-SNAPSHOT:compile`; no ByteBuddy, Jackson, or Spring edge.
- Active consumers: `mvn -q -Pconsumer-it -DskipTests verify` passed for minimal, serializer-api, serializer-jackson, and interface-proxy.
- Active release contracts: `mvn -q -Prelease-contracts -DskipTests verify` passed for the same four consumers.
- Full reactor: `mvn -q clean test` passed; 219 suites, 1,439 tests, 0 failures, 0 errors, 0 skipped.
- Release packaging: `mvn -q -Prelease -DskipTests clean package` passed.
- Java 8 bytecode: all 659 production class files report class-file major version 52.
- Effective root POM: 742,201 bytes; no `maven.aliyun.com` repository.
- `git diff --check` passed.
- Post-transient-edit compile recheck: config-core `mvn -q -DskipTests compile` passed after restoring the intended dirty diff exactly.

## Documentation And Boundaries

- The Java 8 quick-start transition example remains valid and unchanged by this final fix.
- No Task9 `team4u-config-proxy` module, service registration, Spring split, or `team4u-id` code was introduced.
- `consumer-config-core` remains the intentional proxy-only Task9 RED guard; it joins active gates after Task9 removes the proxy edge from config-core.
- Current dirty files before commit: `DefaultConfigManager`, `HotReloadManager`, the two retained config-core tests above, and new internal `HotReloadManagerTest`; tracked progress records the remediation.
