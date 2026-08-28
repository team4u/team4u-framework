# Task 15 Report: Split Mask Core, Jackson Adapter, and Dynamic Config

## Base

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Input HEAD: `6133301f`
- Implementation HEAD: `8ee8c551e7b2de76122bbf02d131738b1d56e8ed`
- Implementation commit subject: `refactor(mask): split adapters and fail fast on unknown policies`
- Review remediation commit subject: `fix(mask): close dynamic rule and lifecycle gaps`

## Changes

- Split the mask artifact into three modules:
  - `team4u-mask`: annotation/core APIs, `FastMasker`, built-in policies, policy registry, and neutral `MaskRuleResolver`.
  - `team4u-mask-jackson`: unchanged `com.team4u.framework.mask.jackson` FQCNs and direct Jackson adapter implementation.
  - `team4u-mask-config`: unchanged `com.team4u.framework.mask.config.MaskRuleRepository` plus moved `com.team4u.framework.mask.config.MaskBootstrap`.
- Kept the existing public policy contract intact: `MaskPolicy` extends `KeyedPolicy<String>`, and the core reuses the thread-safe `KeyedPolicyRegistry`/ServiceLoader behavior from `team4u-policy`.
- Added a neutral global resolver with a deterministic lifecycle:
  - Defaults to `MaskRuleResolver.NO_OP`.
  - `MaskRuleRepository.init(...)` validates and activates a new registry before installing the repository globally.
  - Invalid initial config destroys the candidate registry and leaves both the repository's previous registry and the global resolver unchanged.
  - Restart publishes the new registry reference before destroying the prior registry.
  - `reset()` removes rules/listeners and uninstalls the repository only while it still owns the global resolver.
- Changed Jackson bean/map serialization to read dynamic rules through `MaskRuleResolver.Global`, removing all mask-config imports from the Jackson adapter.
- Implemented fail-closed policy resolution:
  - Unknown keys throw exactly `Unknown mask policy: <key>`.
  - Null, empty, and whitespace string keys throw `IllegalArgumentException`.
  - Null enum keys throw `IllegalArgumentException`.
  - Resolution happens before null/empty input-value short-circuits.
  - Only explicit `NONE` intentionally returns original input.
- Updated `team4u-log` minimally to consume all three split artifacts:
  - Changed `MaskBootstrap` import to `com.team4u.framework.mask.config.MaskBootstrap`.
  - Added explicit `team4u-mask-jackson` and `team4u-mask-config` dependencies.
  - Tests install the repository as the core global resolver when injecting manual rules.
- Added focused quickstarts and security coverage:
  - `MaskQuickstartTest` (7 tests)
  - `MaskSecurityContractTest` (8 tests)
  - `JacksonMaskQuickstartTest` (1 test)
  - `MaskConfigQuickstartTest` (4 tests)
  - `MaskConfigSecurityLifecycleTest` (5 tests)
  - `MaskConfigCompatibilityTest` (1 test)
- Updated root/BOM, migration, breaking-change, root README/docs index, and mask/log documentation.

## Review Remediation

- Explicit null dynamic rules now fail closed:
  - Initial configuration validates the complete candidate rule map before replacing the active registry or installing the global resolver.
  - A failed hot update is retained by `ConfigDrivenRegistry`, so the last valid rules continue to resolve.
  - Manual `setRuleCache` rules distinguish an absent field from an explicit null value. Explicit null fails on lookup; an absent field remains no-op and does not fall through to the `*` wildcard after an exact class/field miss.
  - A null class-rule map is also rejected during configured rule decoding.
- Global resolver ownership is now explicit:
  - `MaskRuleResolver.Global` stores its resolver in an `AtomicReference`.
  - Install and explicit administrative/test reset use volatile publication through `AtomicReference.set`.
  - `uninstall(expectedResolver)` uses compare-and-set from only that expected owner to `NO_OP`; it cannot remove a newer resolver installed by another owner.
  - `MaskRuleRepository` publishes its active registry through a volatile field and reset/stop call ownership-aware uninstall.
- Restored two public API surfaces inadvertently narrowed by the split:
  - `FastMasker` is again non-final and retains its public no-argument constructor.
  - Jackson `MaskConfig` again provides Lombok-data-compatible chained access, `canEqual`, `equals`, `hashCode`, and `toString`.
- Broadened the three mask Enforcer Spring group patterns from `org.springframework` to `org.springframework*`, covering subgroups such as `org.springframework.security` while preserving the existing scope-aware five-field pattern form.
- Documented configured explicit-null rejection and hot-update retention in `docs/mask/mask-dynamic.md`.

## RED Evidence

Initial Task 15 implementation:

- The inherited dirty implementation already contained the Task 15 RED-to-GREEN work for fail-closed masking and module quickstarts.
- The first quality audit found an unintended API break: `MaskPolicy` no longer extended `KeyedPolicy<String>`, and the POM banned `base`/`policy` despite the Task 15 graph permitting them. A compatibility assertion was added first and failed compilation because `com.team4u.framework.policy.api` was absent.
- Initial focused `team4u-log` migration testing produced three real failures because direct repository rule injection no longer affects Jackson serialization through the neutral resolver. The tests were changed to install the repository resolver explicitly, matching the new lifecycle contract.

Review remediation:

- `fastMaskerKeepsPublicExtensionSurface` failed against the split implementation's final class/private constructor before the API was restored (`/tmp/task15-red.log`, 2026-08-28 14:29:25Z).
- The ownership assertion failed compilation before `MaskRuleResolver.Global.uninstall` existed (`/tmp/task15-red-cas.log`, 2026-08-28 14:30:15Z).
- `MaskConfigCompatibilityTest` failed compilation before `MaskConfig.canEqual` returned a primitive boolean (`/tmp/task15-red-config.log`, 2026-08-28 14:29:31Z).
- The new five-test config security lifecycle suite had three real failures before rule validation/lookup changed: initial explicit null was accepted and installed, hot update replaced valid rules with null, and manual explicit null fell through to wildcard resolution (`/tmp/task15-red-config-security.log`, 2026-08-28 14:29:43Z).
- The corresponding focused GREEN run completed at 2026-08-28 14:35:16Z. It was superseded by the full reactor and by the final focused rerun recorded below.

## Dependency Boundary

Production compile graph from the worktree reactor:

```text
team4u-mask
+- team4u-base
\- team4u-policy

team4u-mask-jackson
+- team4u-mask
|  +- team4u-base
|  \- team4u-policy
\- jackson-databind

team4u-mask-config
+- team4u-mask
|  +- team4u-base
|  \- team4u-policy
+- team4u-config-core
\- team4u-serializer-json
```

Filtered checks add no forbidden mask-core production edge. `mask-jackson` contains direct Jackson leaves only. `mask-config` contains its intended config-core/serializer-json leaves only.

Source checks:

- `team4u-mask/src/main/java` has no Jackson, Spring, config-core, config-proxy, or serializer imports.
- `team4u-mask-jackson/src/main/java` has no mask-config, config, serializer, or Spring imports; its framework imports are mask core plus Jackson.
- `team4u-mask-config/src/main/java` has no mask-jackson, Jackson, config-proxy, or Spring imports and decodes JSON through `JsonUtil`.
- Old `com.team4u.framework.mask.MaskBootstrap` production imports: none.

Each module bans forbidden edges with valid five-field `group:artifact:version:type:scope` patterns (classifier omitted, as in Tasks 10-14). `/tmp` noncyclic probes:

- Initial compile injections of `config-core` into mask, `mask-config` into mask-jackson, and direct `jackson-databind` into mask-config all failed Enforcer with their configured boundary messages.
- The same injections in test scope passed, proving the scope-aware rules permit legitimate tests.
- The first isolated mask-jackson test proof saw stale pre-split mask snapshot metadata in the local repository. Refreshing the worktree mask snapshot made the test-scope proof pass without changing a rule.
- Review remediation injected `org.springframework.security:spring-security-crypto` into each module at compile scope; all three failed with the module boundary message, while test-scope injections passed.
- A final direct negative probe injected `org.springframework:spring-context` into a copied `team4u-mask` POM and failed with `mask core must remain a pure Java artifact with no adapter dependencies`.

Root reactor integrity:

- 38 modules, no duplicates.
- 52 managed dependency coordinates, no duplicates.
- `team4u-mask-jackson` and `team4u-mask-config` are represented in both module and dependency-management sections.

## Verification

All worktree Maven commands used `-f /root/code/team4u-framework/.worktrees/framework-convergence/pom.xml`. `/tmp` Enforcer probes used their copied absolute module POMs as stated below.

Checks run for this final record:

- Focused security/lifecycle/API rerun: `mvn -q -pl :team4u-mask,:team4u-mask-jackson,:team4u-mask-config -Dtest=MaskSecurityContractTest,MaskQuickstartTest,MaskConfigSecurityLifecycleTest,MaskConfigCompatibilityTest test`: exit 0.
  - `MaskSecurityContractTest`: 8 tests, 0 failures/errors/skipped.
  - `MaskQuickstartTest`: 7 tests, 0 failures/errors/skipped.
  - `MaskConfigSecurityLifecycleTest`: 5 tests, 0 failures/errors/skipped.
  - `MaskConfigCompatibilityTest`: 1 test, 0 failures/errors/skipped.
- Exhaustive Java 8 bytecode audit ran `javap -verbose` on every `.class` file under each module's absolute `target/classes` path and parsed each `major version:` line:
  - `team4u-mask`: 23 class files, 23 major-version lines, 0 non-52.
  - `team4u-mask-jackson`: 6 class files, 6 major-version lines, 0 non-52.
  - `team4u-mask-config`: 3 class files, 3 major-version lines, 0 non-52.
  - Total: 32 class files, 32 major-version lines, 0 non-52.
- `git diff --check`: passed.

Inherited remediation evidence retained from the prior agent (not rerun here):

- Full reactor `mvn clean test` completed in the 2026-08-28 14:37:20-14:38:25 UTC window. Its 237 Surefire XML files total 1,512 tests, 0 failures, 0 errors, 0 skipped.
- Consumer gate `mvn -Pconsumer-it -DskipTests verify` produced five successful invoker builds in 14:38:40-14:38:55 UTC; every retained consumer `build.log` ends in `BUILD SUCCESS`.
- Release contracts `mvn -Prelease-contracts -DskipTests verify` produced five successful invoker builds in 14:39:24-14:39:40 UTC; every retained contract `build.log` ends in `BUILD SUCCESS`.
- Release packaging `mvn -Prelease -DskipTests package` completed by 14:40:32 UTC, including binary/source/Javadoc artifacts for all three mask modules.
- The retained root logs for the inherited consumer, release-contract, and packaging commands are empty; no root exit line was preserved. Their pass status above is based on retained invoker build logs and generated artifacts, not on a retained root exit line.
- Earlier remediation focused/module/log runs left output-only quiet logs without exit markers. They are not cited as final proof; the full reactor artifacts and final focused rerun above cover the same code.

## Concerns

- `MaskBootstrap` intentionally has no old-package compatibility class; migration requires the documented import and dependency change.
- Unknown mask policies are a breaking behavioral change. Applications that relied on accidental plaintext fallback must use explicit `NONE` or register a real policy.
- `team4u-log` is a transitional consumer until Task 17. It declares the split mask artifacts explicitly instead of relying on one aggregate mask artifact.
- `MaskRuleResolver.Global.install` and `reset()` remain unconditional administrative operations by contract. Ownership protection applies to automatic repository teardown.
- No standalone JDK 8 runtime is installed; Java 8 evidence is compiler configuration plus class-file major version 52.
- No master, `team4u-id`, KV heartbeat, or Task16 work was included.
