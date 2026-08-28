# Task 8 Report: Config Dependency Inversion and Global Initialization Fix

## Base

- Base commit: `ed6c2cfa43c3b23ccdf00df53ce8aa6edc39e95a`
- Task8 implementation commit under review: `8bebace4cba1d0dbf119755f481d0ab6d21c3063`
- Worktree: `.worktrees/framework-convergence`
- Original RED worktree: `/tmp/team4u-task8-red`, removed after its two untracked RED tests were superseded by retained GREEN coverage.

## Review REDs And Remediation

The post-review fix addressed these concrete findings without changing the Task8 architecture:

- `docs/config/quick-start.md` used a nonexistent `new ConfigProxyFactory()` constructor. The transition example now uses a Java 8 anonymous `ConfigProxyCreator` with the generic `<T> create(...)` method, constructs `ConfigProxyFactory(context.converterRegistry())`, and delegates to `createLiveProxy(context.manager(), prefix, configType)`. The complete snippet was compiled with `javac -source 8 -target 8` against the current worktree classes.
- Bootstrap methods no longer retain the bootstrap monitor while refreshing a global manager. Registration remains serialized on the bootstrap instance; global initialization, refresh, and reset serialize on `ConfigManager.class`; manager lifecycle operations use the manager lifecycle monitor.
- Reload aggregation no longer holds the `HotReloadManager` monitor while entering manager lifecycle code. A manager-owned committer switches snapshots under the lifecycle monitor, and listener callbacks run after manager and reload monitors are released.
- Reload generations invalidate both in-flight aggregation and queued debounce tasks. Reset cancels acceptance, destroys watchers, clears runtime state, and a later refresh resumes reload acceptance under the lifecycle monitor.
- No `GlobalLifecycleMonitor` file was retained, `ConfigProxyFactory` has no unsafe no-arg constructor, and all lifecycle helpers introduced by remediation are used by production reset/refresh paths or the retained concurrency test. `DefaultConfigManager.discardGlobalForTests()` is test-only, used only after production reset, and is called by the dedicated concurrency test cleanup.
- `ServiceLoader` iteration failures are wrapped in an `IllegalStateException` naming provider discovery, preserving the original `ServiceConfigurationError` cause and provider implementation name.

## API And Behavior

- `ConfigProxyCreator.create(ConfigProxyContext, String, Class<T>)` is the core-owned proxy boundary.
- `ConfigProxyContext` exposes the owning `ConfigManager` and exact manager `PropertyConverterRegistry`.
- `Builder.configBinder(...)` was replaced by `Builder.proxyCreator(...)`.
- Creator resolution is explicit builder value, then at most one `ServiceLoader<ConfigProxyCreator>`, then absent. Multiple providers fail fast with both implementation names.
- A missing creator makes `createProxy` fail fast with the `team4u-config-proxy` guidance and never substitutes a bound POJO.
- `DefaultConfigBinder.bind(...)` remains explicit snapshot binding only.
- Final prefix composition happens before caching and creator invocation.
- `ConfigManager.global()` is lazy and resettable. Bootstrap registration and `lock()` refresh an initialized global; reset does not initialize an absent global.
- `ConfigProxyFactory implements ConfigProxyCreator` is the temporary Task8 bridge. Its creator method requires a non-null context and uses the context converter registry, rather than adding a no-arg constructor.

## Concurrency Semantics

- `ConfigManager.class` serializes absent-global initialization and bootstrap reset. A reset entering during initialization waits, then clears the newly initialized result.
- Normal reset preserves the global manager instance while destroying watchers, invalidating reload state, clearing listeners/proxy cache, clearing registries, unlocking bootstrap, and rebuilding an empty snapshot.
- An in-flight reload may finish source aggregation after reset, but its generation is stale: it cannot switch the snapshot or fire listeners.
- A queued debounce reload is canceled by generation invalidation; a later `refresh()` resumes acceptance under the manager lifecycle monitor.
- A synchronous change listener may reset the same manager without deadlock because callbacks do not own manager lifecycle or reload monitors.

## Retained Tests

Original Task8 GREEN coverage:

- `ConfigPureJavaQuickstartTest`
- `ConfigProxyProviderContractTest`
- `ConfigProxyCreatorResolutionTest`
- `ConfigGlobalInitializationTest`

Final review coverage:

- `ConfigGlobalLifecycleConcurrencyTest`: 6 tests covering absent reset, instance-preserving reset, reset serialization with initialization, in-flight reload invalidation and refresh resumption, queued reload cancellation, and listener-triggered reset without deadlock.
- `ConfigProxyCreatorServiceLoaderTest`: 4 tests covering zero, one, and two providers plus wrapped provider construction failure.
- `ConfigProxyFactoryContextTest`: verifies the interim factory bridge uses the converter registry supplied by `ConfigProxyContext` rather than a captured factory registry.
- `ConfigProxyProviderContractTest.creatorNullResultFailsFastWithTypeAndPrefix` verifies null creator results fail fast.

Handoff-focused tests had already passed before final wrap-up: selected focused tests 22/22 and config-core 102/102. The final full reactor below also contains all retained config-core tests.

## Verification

All final commands ran at dirty Task8 HEAD `8bebace` with remediation changes present:

- Java 8 quick-start snippet compile: passed with `-source 8 -target 8` (only modern-JDK source-option warnings).
- Affected downstream: `mvn -q -pl :team4u-log,:team4u-router,:team4u-retry-core -am test` passed; log 97/97, router 69/69, retry-core 72/72.
- Worktree install for consumer resolution: `mvn -q -DskipTests install` passed.
- Explicit config consumer: main and Java 8 compile passed. Its runtime tree remains the intentional proxy-only RED guard and contains `com.team4u:team4u-proxy:1.0.0-SNAPSHOT:compile`; applying the fixture predicate reports that single banned edge.
- Active consumers: `mvn -q -Pconsumer-it -DskipTests verify` passed for minimal, serializer-api, serializer-jackson, and interface-proxy.
- Release contracts: `mvn -q -Prelease-contracts -DskipTests verify` passed for the same four active consumers.
- Full reactor: `mvn -q clean test` passed with 218 suites, 1,433 tests, 0 failures, 0 errors, 0 skipped.
- Release packaging: `mvn -q -Prelease -DskipTests clean package` passed.
- Effective POM: 742,201 bytes and no `maven.aliyun.com` repository.
- Java 8 bytecode: all 659 production class files report class-file version 52.
- `git diff --check` passed.

## Documentation And Boundaries

- Quick-start proxy example is complete and Java 8-valid; no quick-start sections were deleted.
- Existing migration/breaking-change ledger rows remain unchanged by this review fix.
- No Task9 `team4u-config-proxy` module, service registration, Spring split, or `team4u-id` code was introduced.
- The explicit config consumer remains RED for `team4u-proxy` until Task9 removes the proxy implementation from config-core.

## Files

- Production: `ConfigBootstrap`, `ConfigManager`, `DefaultConfigManager`, `HotReloadManager`, `ConfigProxyFactory`.
- Tests: concurrency, ServiceLoader, factory-context, provider contract, creator resolution, and global initialization updates.
- SDD records: this report and convergence progress.
- User-facing docs: corrected config quick-start transition example.
