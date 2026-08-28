# Task 8 Report: Config Dependency Inversion and Global Initialization Fix

## Base

- Task8 implementation commit: `8bebace4cba1d0dbf119755f481d0ab6d21c3063`
- First concurrency/provider remediation commit: `c280f08afe12a78505eaefa41d4e47687262c419`
- Repeated-debounce remediation commit: `8592aedc8335634c7e5fbcd25647788edd39ed93`
- Worktree: `.worktrees/framework-convergence`
- Final callback-lock fix commit: `fix(config): dispatch reload listeners outside locks`

## Callback Lock Split

`ReloadCommitter` now atomically commits a reload and returns a `ReloadEvent`; stale reloads return `null`. `DefaultConfigManager.commitReload(...)` performs the final currency check, swaps the snapshot, and constructs the event under `lifecycleMonitor`, but never dispatches listeners.

`HotReloadManager` owns a separate `ReloadNotifier`. It releases `reloadExecutionMonitor` and its own synchronized monitor before invoking the notifier. Therefore a blocked or slow listener cannot hold:

- the reload execution monitor,
- the manager lifecycle monitor,
- the `HotReloadManager` monitor, or
- global initialization/reset serialization.

Notifier failure is caught and logged separately after a successful commit. Pending token cleanup remains in `finally`, and clears the pending handle only when the reload token still owns it.

## Retained Tests

Exact final focused counts:

- `ConfigGlobalLifecycleConcurrencyTest`: 8 tests.
- `internal.HotReloadManagerTest`: 8 tests.

Focused callback/lifecycle command passed once with 16 tests, 0 failures, 0 errors, and 0 skipped. It deliberately does not use high-count repetition.

The retained tests prove each of the following:

- A blocked reload listener does not block a second reload commit or manager reset.
- A blocked notifier does not block a second reload execution or notification.
- A committed reload returns old/new snapshots and notifies outside the execution lock.
- A stale reload returns no event and never notifies.
- A notifier exception does not roll back or destabilize the committed snapshot.
- Reset still serializes with concurrent global initialization and clears its result.

Creator/provider focused coverage also passed once: `ConfigProxyCreatorResolutionTest` plus `ConfigProxyCreatorServiceLoaderTest`, 5 tests total, 0 failures/errors/skipped.

## Verification

All Maven commands used `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

- Config-core full module: passed with 112 tests, 0 failures/errors/skipped.
- Config acceptance/downstream: `:team4u-config-core,:team4u-config-db,:team4u-config-test -am test` passed; its 9 module results total 409 tests, 0 failures/errors/skipped.
- Exact proxy-only consumer `consumer-config-core`: exited 1 as required. Its only banned runtime edge was `com.team4u:team4u-proxy:jar:1.0.0-SNAPSHOT:compile`.
- Active consumer contracts: `consumer-minimal`, `consumer-serializer-api`, `consumer-serializer-jackson`, and `consumer-interface-proxy` all passed (4/4 invoker projects).
- Active release contracts: the same four projects all passed (4/4 invoker projects).
- Final root clean test: all 31 reactor modules passed; 28 module test results total 1,443 tests, 0 failures/errors/skipped.
- Release packaging: `-Prelease -DskipTests clean package` passed across the reactor.
- Java 8 bytecode: all 660 production classes in `target/classes` report class-file version 52.0.
- Dependency check: root `dependency:tree` completed successfully with no `maven.aliyun.com` occurrence.
- Effective POM: 742,201 bytes with no `maven.aliyun.com` repository.
- `git diff --check` passed before commit.

The disposable `/tmp/task8-callback-red` worktree was inspected, removed explicitly with `git worktree remove --force`, and pruned. The parent checkout and convergence worktree were not modified by that cleanup; `git worktree list` now contains only those two.

## Boundaries

- No Task9 `team4u-config-proxy` module was introduced.
- No ServiceLoader provider registration for the future proxy artifact was added.
- No Spring split or `team4u-id` code was introduced.
- `consumer-config-core` remains the intentional Task9 proxy-only RED guard.
