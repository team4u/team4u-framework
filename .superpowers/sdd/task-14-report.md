# Task 14 Report: Translator Convergence After Router

## Base

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Input HEAD: `29c9b5c8`
- Implementation commit subject: `test(translator): lock post-router boundary`
- Review remediation commit subject: `test(translator): strengthen fallback quickstart`

## Changes

- Added `team4u-translator/src/test/java/com/team4u/framework/translator/TranslatorQuickstartTest.java`.
  - Uses `TestConfigContext` to build an isolated `ConfigManager` and `RoutingManager`; the builder disables the global interceptor registry, and the test does not mock `RoutingManager`.
  - Loads an actual expression router JSON policy through test-scoped `team4u-serializer-jackson`.
  - Covers route selection into `ErrorDef`, translated code/message, template args plus `rawCode`/`rawMessage`, trace propagation, independent message-only and code-only fallback behavior, unmatched-route passthrough, and missing-route passthrough.
  - Destroys the test config context after each test and does not scan or mutate shared router interceptor state.
- Added only actual test dependencies:
  - `team4u-config-test` (`test`, excluding `team4u-config-proxy`)
  - `team4u-serializer-jackson` (`test`)
  - Existing JUnit and Mockito declarations are explicitly pinned to `test` scope.
- Added the module Enforcer execution `enforce-translator-boundary`. It bans compile/runtime edges for:
  - `team4u-router-proxy`
  - `team4u-proxy`
  - `team4u-bean`
  - `team4u-config-proxy`
  - `team4u-serializer-jackson`
  - `com.fasterxml.jackson*`
  - `net.bytebuddy`
- Updated the translator overview, translator quick start, migration guide, and breaking-change record for the router-core-only and explicit-provider boundary.

## RED Evidence

The quickstart was added before POM changes. Its first focused run failed in test compilation because `com.team4u.framework.config.test` was not a translator dependency. The expected provider edge was then supplied as test scope. No fake compile shortcut or production provider was introduced.

A second real RED came from the new Enforcer guard: a stale pre-Task13 `team4u-router` snapshot POM in the local Maven repository advertised `team4u-bean` and `team4u-proxy`. Reinstalling the current router POM removed that local-metadata-only edge; no source-tree router change was needed.

## Dependency Boundary

Translator runtime production graph is exactly:

- `team4u-translator`
- `team4u-serializer-json`
- `team4u-base` / `slf4j-api`
- `team4u-policy`
- `team4u-criterion`
- `team4u-router`
- `team4u-config-core` (through router core)

There is no compile/runtime `router-proxy`, proxy, bean, config-proxy, serializer-jackson, Jackson, or ByteBuddy edge. Test scope contains config-test, serializer-jackson and its Jackson leaves, Mockito, and JUnit as intended.

Enforcer patterns use valid scope-aware five-field shorthand with the classifier omitted, matching the Task 13 convention. Examples are `com.team4u:team4u-router-proxy:*:*:compile` and `com.fasterxml.jackson*:*:*:*:runtime`.

Proof in `/tmp`:

- A noncyclic copy of the translator POM injected compile dependencies for all forbidden Team4u adapters/provider plus direct `net.bytebuddy:byte-buddy`. Validation failed and named every injected artifact.
- The same copy changed all six injected dependencies to test scope. Validation passed, proving the scope-aware rules permit legitimate test usage.
- The exact brief's filtered runtime tree command produced an empty output file.
- A second filtered runtime tree for proxy, bean, config-proxy, and ByteBuddy was also empty.
- Translator production source has no proxy/bean reference.

## Verification

All commands used the framework-convergence worktree.

- Focused quickstart: `mvn -q -pl :team4u-translator -am -Dtest=TranslatorQuickstartTest -Dsurefire.failIfNoSpecifiedTests=false test`: passed.
- Module acceptance: `mvn -q -pl :team4u-translator -am test`: passed; translator reports 20 tests (15 prior, 5 new), all green.
- Full reactor: `mvn -q clean test`: passed across all modules; Surefire reports 1,486 tests, 0 errors, 0 failures, 0 skipped.
- Consumer gate: `mvn -q -Pconsumer-it -DskipTests verify`: passed.
- Release contracts: `mvn -q -Prelease-contracts -DskipTests verify`: passed.
- Release packaging: `mvn -q -Prelease -DskipTests package`: passed, including translator binary/source/Javadoc packaging.
- Java 8 evidence: every class in the translator binary reports major version 52.
- `git diff --check`: passed.
- No generated `target/` content is tracked.

## Concerns

- `team4u-config-test` is a test-framework artifact and is correctly excluded from runtime publication. It carries no forbidden proxy edge here because `team4u-config-proxy` is explicitly excluded.
- The local Maven repository initially held stale router snapshot metadata from before Task 13. A clean environment resolves reactor artifacts directly and is unaffected; refreshing local snapshot installation resolves a developer machine.
- No Task15, master, or `team4u-id` work was included.
