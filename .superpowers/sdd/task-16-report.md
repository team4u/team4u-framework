# Task 16 Report: Split bean-spring as Plain Spring Configuration

## Status

Complete.

## Result

`team4u-bean` is now a pure Java bean-core module. `team4u-bean-spring` owns the unchanged `com.team4u.framework.bean.provider.SpringBeanContainer` and exposes it through the plain `Team4uBeanConfiguration`. `team4u-retry-spring` consumes that shared configuration through its existing public `@EnableRetry` entry and no longer declares a local adapter bean.

## Scope

- Created `team4u-bean-spring` and added it to the reactor and Team4u dependency management.
- Moved `SpringBeanContainer` with a 100% Git rename while preserving its FQCN.
- Added `Team4uBeanConfiguration` with one `@Bean SpringBeanContainer`.
- Added bean-core and bean-spring configuration tests.
- Added retry-spring boundary/runtime tests.
- Removed Spring dependencies and source use from bean core.
- Removed the local retry adapter bean and imported `Team4uBeanConfiguration`.
- Updated bean/retry and migration documentation.
- Tightened the bean-core Enforcer patterns to type-agnostic five-field forms:
  - `org.springframework*:*:*:*:compile`
  - `org.springframework*:*:*:*:runtime`
- Tightened the retry runtime fixture so `@EnableRetry`, not a redundant direct import, supplies `RetrySpringConfiguration`.
- Removed the unused `TaskSixteenSharedService` fixture from the bean quickstart.

## Design Audit

- FQCN move: `git diff --cached --find-renames --summary` reports `SpringBeanContainer.java` as a 100% rename from bean core to bean-spring.
- Plain configuration: `Team4uBeanConfiguration` matches the task interface exactly.
- Bean core isolation:
  - No `org.springframework` dependency edge in the filtered dependency tree.
  - No Spring reference in `team4u-bean/src/main/java`.
  - Production POM has no Spring dependency; only the Enforcer ban mentions Spring.
- Bean-spring boundary:
  - Direct Team4u dependency is `team4u-bean`.
  - Direct external dependency is `spring-context`; `spring-test` is test-scoped.
  - No Boot dependency in the filtered graph.
  - No `src/main/resources`, `spring.factories`, Boot import file, or `AutoConfiguration` naming.
  - The Boot Enforcer guard uses five-field compile/runtime/test patterns.
- Retry integration:
  - `RetrySpringConfiguration` imports `Team4uBeanConfiguration`.
  - It declares no method returning `SpringBeanContainer`.
  - Its POM depends on `team4u-bean-spring`, not bean core, for the adapter.
  - The runtime test registers only `@EnableRetry`; the redundant fixture-level `@Import(RetrySpringConfiguration.class)` was removed. The Java `Import` import remains for the reflection assertion against production wiring.
- Documentation:
  - Active instructions use `team4u-bean-spring` plus explicit `@Import(Team4uBeanConfiguration.class)`.
  - Manual production examples no longer instruct users to construct `new SpringBeanContainer()`.
  - The migration "Before" fragment retains old construction only to explain what to remove.
  - The unchanged `SpringBeanContainer` FQCN remains documented.
- Prohibited Task17/log scope is absent. There is no `team4u-id`, KV, or logging refactor work.

## Verification

All Maven invocations used the absolute root POM `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`.

1. Focused tests:
   - `mvn -q -pl :team4u-bean -am -Dtest=BeanQuickstartTest test` passed: 3 tests, 0 failures/errors/skipped.
   - `mvn -q -pl :team4u-bean-spring -am -Dtest=Team4uBeanConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed: 4 tests, 0 failures/errors/skipped. The override is needed because `-am` sends the module-specific test selector to upstream modules.
   - `mvn -q -pl :team4u-retry-spring -am -Dtest=RetrySpringConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test` passed: 2 tests, 0 failures/errors/skipped.
   - `mvn -q -pl :team4u-retry-spring -am test` passed: 15 tests across three test classes, 0 failures/errors/skipped.
2. Selected module acceptance:
   - `mvn -q -pl :team4u-bean,:team4u-bean-spring,:team4u-retry-spring -am test` exited 0.
3. Dependency and content audit:
   - `:team4u-bean dependency:tree -Dincludes=org.springframework*` produced no Spring edge.
   - `:team4u-bean-spring dependency:tree` filtered to Team4u and Spring showed bean core, `spring-context`, transitive Spring 5.3 jars, and test-only `spring-test`.
   - `:team4u-retry-spring dependency:tree` filtered to Team4u and Spring showed the retry dependencies, bean-spring, and Spring context; no Boot edge.
   - Source/POM/resource scans found no bean-core production Spring use and no bean-spring Boot/auto-configuration resources or naming.
4. Enforcer probes:
   - Production bean-core guard passed with the requested five-field compile/runtime patterns.
   - An isolated `/tmp` non-jar Spring compile dependency was rejected by the exact compile pattern; a test-scoped dependency passed.
   - An isolated `/tmp` bean-spring Boot compile dependency was rejected by the plain-Spring Boot guard; the production bean-spring guard passed.
5. Root structure:
   - 39 unique concrete reactor modules.
   - 53 unique artifacts in all dependency management.
   - 39 Team4u-managed concrete leaves when the root `team4u-framework` aggregate is excluded; the raw Team4u dependency-management entry count is 40 with that aggregate included.
6. Full clean reactor:
   - `mvn clean test` passed: 240 Surefire XML files, 1,524 tests, 0 failures, 0 errors, 0 skipped.
7. Consumer and release contracts:
   - `consumer-it` nested projects all passed. The first surrounding root `verify` run hit the unrelated `KvLockManagerTest.heartbeatIntervalAdaptsToShortLease` timing test; focused rerun passed. `-DskipTests -Pconsumer-it verify` then passed all five consumers and the root build.
   - `-DskipTests -Prelease-contracts verify` passed all five consumers and the root build.
8. Release package:
   - `mvn -DskipTests -Prelease package` passed, including bean-spring binary, source, and javadoc jars.
9. Java 8 verification:
   - Both bean-spring production classfiles have major version 52: `SpringBeanContainer.class` and `Team4uBeanConfiguration.class`.
10. Git verification:
   - `git diff --check` passed.
   - Exact 100% rename detected for `SpringBeanContainer.java`.
   - No build outputs or `/tmp` probe files entered the worktree.

## Concerns

- The `KvLockManagerTest.heartbeatIntervalAdaptsToShortLease` test is an existing unrelated timing-sensitive failure. It failed once during the initial consumer-it root verify, passed on focused rerun, and the complete clean test run passed both before and after that event.
- Surefire's `-Dtest` selector with `-am` requires `-Dsurefire.failIfNoSpecifiedTests=false` when no class of that name exists in upstream modules. This is command behavior, not a module defect.

## Commit

`refactor(bean): split plain spring adapter`
