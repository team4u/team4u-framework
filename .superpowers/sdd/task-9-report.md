# Task 9 Report: Split config-proxy and config-spring

## Base

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Input HEAD: `e832af3d`
- Implementation commit: `refactor(config): split proxy and spring adapters` (final SHA reported outside the commit; a commit cannot embed its own immutable SHA)

## RED Evidence

- Existing external boundary RED:
  `mvn -f <worktree>/pom.xml -Pconsumer-it -Dinvoker.test=consumer-config-core/pom.xml -DskipTests verify`
  exited 1. `verify.groovy` reported exactly one banned edge:
  `com.team4u:team4u-proxy:jar:1.0.0-SNAPSHOT:compile`.
- New-module source RED:
  `mvn ... -pl :team4u-config-proxy -am test` failed test compilation with seven
  `cannot find symbol: ServiceLoaderConfigProxyCreator` errors.
- Spring source RED:
  `mvn ... -pl :team4u-config-spring -am -DskipTests test` failed test compilation with
  `cannot find symbol: Team4uConfigConfiguration`.
- These RED tests were added before production classes were moved or created.

## Moves And Modules

- New leaves: `team4u-config-proxy` and `team4u-config-spring`.
- Preserved proxy FQCNs moved with `git mv`:
  `ConfigProxyFactory`, `ConfigMethodInterceptor`, and `SnapshotAware`.
- Moved actual proxy implementation tests: `ConfigProxyTest`, `ConfigAnnotationTest`,
  `ConfigBeanProxyTest`, `ConfigConverterTest`, `ConfigPrefixTest`,
  `ConfigProxyFactoryContextTest`, and `DefaultConfigManagerCacheTest`.
- Sole real-implementation test fixture `TestConfigProxyCreator` moved to config-proxy.
- Core retains pure tests, custom fake/no creator contracts, provider ServiceLoader isolation,
  scalar/binder tests, global initialization/reset/concurrency, reload, and debounce coverage.
- `Team4uConfigAutoConfiguration` moved and renamed to `Team4uConfigConfiguration`;
  its test now imports the configuration explicitly and proves the old class/name and adapter
  `META-INF/spring.factories` metadata are absent.
- Root module list and dependency management contain 32 module entries. Parent POM paths are flat.

## Provider And Runtime

- `ServiceLoaderConfigProxyCreator` is public, final, concrete, and no-arg constructible.
- Its only service resource contains exactly:
  `com.team4u.framework.config.core.proxy.ServiceLoaderConfigProxyCreator`
- It uses `context.manager()` and `context.converterRegistry()` exactly, preserving manager-owned
  custom converter identity and nested proxies.
- `ConfigProxyFactory(PropertyConverterRegistry)` remains unchanged and is not registered as a service.
- Config-proxy runtime tree:
  `config-proxy -> config-core, proxy, net.bytebuddy:byte-buddy:runtime`.
- Runtime ByteBuddy is explicit because config proxies always construct concrete-class proxies.
  `team4u-proxy` remains interface-light with optional ByteBuddy. No second dependency is needed
  by config-proxy consumers; the adapter test creates a concrete class proxy from that runtime edge.

## GREEN And Verification

All commands used `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

- Focused core selection: `ConfigPureJavaQuickstartTest` (3), `ConfigProxyProviderContractTest` (4),
  and `ConfigGlobalInitializationTest` (3): 10 tests, 0 failures/errors/skipped.
- Config proxy/spring focused run: 25 tests, 0 failures/errors/skipped, including the 5 new provider
  contract tests and 2 explicit Spring import tests.
- Five-module config acceptance:
  `:team4u-config-core,:team4u-config-proxy,:team4u-config-spring,:team4u-config-db,:team4u-config-test -am test`
  passed.
- Full clean reactor: `mvn -q clean test` passed across all 32 modules.
- Install and release artifact build: `mvn -q -DskipTests clean install` and
  `mvn -q -Prelease -DskipTests clean package` passed.
- Default consumer contracts: `mvn -q -Pconsumer-it -DskipTests verify` passed all five projects.
- Release contracts: `mvn -q -Prelease-contracts -DskipTests verify` passed all five projects.
- Explicit config-core consumer passed. Its current runtime tree contains only config-core,
  base/slf4j-api, serializer-json, and policy; no proxy, ByteBuddy, Jackson, or Spring.
- Source audit: config-core production/tests have no `config.core.proxy`, `ConfigProxyFactory`,
  `SnapshotAware`, or Spring imports.
- Effective POM: 801,246 bytes with no `maven.aliyun.com` repository.
- Java 8 audit: 681 production class files; all major-version bytes are `0034` (52.0).
- Root/BOM/module audit: 32 `<module>` entries and 32 primary reactor leaf jars with current
  release artifacts present.
- `git diff --check` passed.

## Documentation

Updated `MIGRATION-1.0.md`, `docs/breaking-changes-1.0.md`, config README/quick-start/proxy/sample
docs, the approved convergence design/plan graphs, and Task 9 progress. The stale plan claim that
the pre-Task9 guard failed on proxy plus Jackson was corrected to proxy-only after Tasks 6 and 7.
The dependency graph now documents `config-proxy -> config-core, proxy, ByteBuddy(runtime)`.

## Concerns

- Spring Framework itself contains `META-INF/spring.factories`; the adapter test checks the
  config-spring artifact's own code source rather than making a false classpath-global assertion.
- No Task10 work was included.
