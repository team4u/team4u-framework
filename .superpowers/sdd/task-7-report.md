# Task 7 Report: Serializer Provider Contracts and Upstream Cleanup

## Outcome

Task 7 is complete. The serializer API is provider-free, its fail-fast error names both supported remedies, the Jackson provider is verified through its ServiceLoader resource rather than package scanning alone, and the Jackson provider is absent from every audited upstream runtime tree.

No Task 8 implementation is included.

## Changes

- Added provider-free API contracts:
  - `JsonUtilNoProviderContractTest`
  - `SerializerQuickstartTest`
- Changed `JsonUtil.getPolicy()`'s no-provider message. It still throws `IllegalStateException`, and null/empty inputs still short-circuit without selecting a policy.
- Added `JacksonServiceProviderContractTest`, asserting:
  - `META-INF/services/com.team4u.framework.serializer.json.JsonSerializerPolicy` exists and names `JacksonSerializerPolicy`
  - `JsonUtil.getPolicy()` is a `JacksonSerializerPolicy`
  - a POJO roundtrip works
- Moved config-core's `team4u-serializer-jackson` dependency from compile to test scope.
- Added `consumer-serializer-api` to both `consumer-it` and `release-contracts`.
- Documented explicit provider ownership in serializer, config, retry, KV, lease, router, translator, mask, and log docs; updated migration and breaking-change records.
- Corrected convergence-plan evidence and expiry language:
  - config-core's external guard is now RED for `team4u-proxy` only
  - mask/log keep direct Jackson until Tasks 15/17 and never own the serializer provider
  - retry-lease-runtime is a permanent direct-Jackson integration, not a Task 11 expiry
- Tracked Task 7 as complete.

## RED Evidence

Provider-free module contract, before changing production code:

```text
mvn -pl :team4u-serializer-json -am \
  -Dtest=JsonUtilNoProviderContractTest,SerializerQuickstartTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

JsonUtilNoProviderContractTest.noProviderFailsFastWithInstallGuidance:
expected:<No JsonSerializerPolicy is available. Add com.team4u:team4u-serializer-jackson, or register/provide a custom JsonSerializerPolicy via ServiceLoader.>
but was:<未在类路径下找到可用的 JSON 序列化策略实现（JsonSerializerPolicy）。>

SerializerQuickstartTest.noProviderFailureExplainsHowToInstallOne:
expected:<No JsonSerializerPolicy is available. Add com.team4u:team4u-serializer-jackson, or register/provide a custom JsonSerializerPolicy via ServiceLoader.>
but was:<未在类路径下找到可用的 JSON 序列化策略实现（JsonSerializerPolicy）。>
```

`SerializerQuickstartTest.nullInputsShortCircuitWithoutAProvider` passed during the same run, confirming useful provider-free behavior was already present and isolated from the RED guidance assertions.

Strict serializer-api Invoker, before production/message and POM changes:

```text
mvn -Pconsumer-it -Dinvoker.test=consumer-serializer-api -DskipTests verify

java.lang.IllegalStateException:
No-provider error does not explain the Jackson provider or a custom JsonSerializerPolicy:
未在类路径下找到可用的 JSON 序列化策略实现（JsonSerializerPolicy）。
```

Exit code was 1.

## GREEN Evidence

Focused serializer modules:

```bash
mvn -q -pl :team4u-serializer-json,:team4u-serializer-jackson -am test
```

Exit 0. This includes both provider-free API tests, the existing Jackson tests, and the new SPI/policy/POJO provider contract test.

Strict serializer-api Invoker after installing the updated artifacts:

```text
mvn -q -Pconsumer-it -Dinvoker.test=consumer-serializer-api -DskipTests verify
```

Exit 0. `target/it/consumer-serializer-api/build.log` contains:

```text
No JsonSerializerPolicy is available. Add com.team4u:team4u-serializer-jackson, or register/provide a custom JsonSerializerPolicy via ServiceLoader.
```

Default consumer profile:

```bash
mvn -q -Pconsumer-it -DskipTests verify
```

Exit 0. Four trees and mains ran:

- `consumer-minimal`
- `consumer-serializer-api`
- `consumer-serializer-jackson`
- `consumer-interface-proxy`

Release contracts:

```bash
mvn -q -Prelease-contracts -DskipTests verify
```

Exit 0. The same four consumers each generated a runtime dependency tree under `target/release-contracts-it`.

Affected/upstream module tests:

```bash
mvn -q -pl :team4u-config-core,:team4u-retry-core,:team4u-kv-core,:team4u-kv-lifecycle,:team4u-lease-jdbc,:team4u-router,:team4u-translator,:team4u-mask,:team4u-log,:team4u-retry-lease-runtime -am test -DskipTests=false
```

Exit 0.

Final root clean test:

```bash
mvn clean test
```

Exit 0; all 31 reactor modules succeeded.

Release packaging:

```bash
mvn -q -Prelease -DskipTests package
```

Exit 0.

Java 8 evidence: the root Enforcer Java-8 bytecode rule passed for every module, and `javap -verbose` reports `major version: 52` for both `JsonUtil` and `JacksonSerializerPolicy`. No separate JDK 8 installation exists in this environment, so a separate literal JDK 8 runtime execution was not possible.

Diff hygiene: `git diff --check` exited 0.

## Runtime Dependency Trees

Command:

```bash
mvn -pl :team4u-config-core,:team4u-retry-core,:team4u-kv-core,:team4u-kv-lifecycle,:team4u-lease-jdbc,:team4u-router,:team4u-translator,:team4u-mask,:team4u-log,:team4u-retry-lease-runtime \
  dependency:tree -Dscope=runtime \
  -Dincludes=com.team4u:team4u-serializer-jackson,com.fasterxml.jackson*
```

Exit 0.

Strict ordinary modules had empty filtered runtime trees:

- `team4u-config-core`: empty
- `team4u-retry-core`: empty
- `team4u-kv-core`: empty
- `team4u-kv-lifecycle`: empty
- `team4u-lease-jdbc`: empty
- `team4u-router`: empty
- `team4u-translator`: empty

Recorded direct-Jackson exceptions:

```text
com.team4u:team4u-mask:jar:1.0.0-SNAPSHOT
\- com.fasterxml.jackson.core:jackson-databind:jar:2.16.1:compile
   +- com.fasterxml.jackson.core:jackson-annotations:jar:2.16.1:compile
   \- com.fasterxml.jackson.core:jackson-core:jar:2.16.1:compile
```

```text
com.team4u:team4u-log:jar:1.0.0-SNAPSHOT
\- com.fasterxml.jackson.core:jackson-databind:jar:2.16.1:compile
   +- com.fasterxml.jackson.core:jackson-annotations:jar:2.16.1:compile
   \- com.fasterxml.jackson.core:jackson-core:jar:2.16.1:compile
```

```text
com.team4u:team4u-retry-lease-runtime:jar:1.0.0-SNAPSHOT
\- com.fasterxml.jackson.core:jackson-databind:jar:2.16.1:compile
   +- com.fasterxml.jackson.core:jackson-annotations:jar:2.16.1:compile
   \- com.fasterxml.jackson.core:jackson-core:jar:2.16.1:compile
```

None of the ten modules contains `team4u-serializer-jackson` in its runtime tree. Mask/log direct Jackson is temporary and expires in Tasks 15/17. Retry-lease-runtime direct Jackson is a permanent explicit production integration exception: `LeaseRetryRecordSerializer` uses the Jackson tree API for a versioned durable schema, field-level validation, and a throwable allowlist, requirements the generic serializer SPI intentionally does not model.

## Config-Core Exact RED

After moving its provider to test scope:

```text
mvn -Pconsumer-it -Dinvoker.test=consumer-config-core -DskipTests verify
```

Exit 1. Exact runtime tree:

```text
com.team4u.it:consumer-config-core:jar:1.0.0-SNAPSHOT
\- com.team4u:team4u-config-core:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-base:jar:1.0.0-SNAPSHOT:compile
   |  \- org.slf4j:slf4j-api:jar:1.7.36:compile
   +- com.team4u:team4u-proxy:jar:1.0.0-SNAPSHOT:compile
   +- com.team4u:team4u-serializer-json:jar:1.0.0-SNAPSHOT:compile
   \- com.team4u:team4u-policy:jar:1.0.0-SNAPSHOT:compile
```

The only banned edge is `team4u-proxy:compile`. There is no `team4u-serializer-jackson`, `com.fasterxml.jackson`, ByteBuddy, or Spring runtime edge. Task 9 therefore starts from proxy only.

## Dependency Cleanup Audit

- `config-core`: compile provider changed to test scope.
- `retry-core`, `kv-core`, `kv-lifecycle`, `lease-jdbc`, `router`: direct test-scope provider declarations remain. Their production code calls `JsonUtil`/serializer APIs and current tests execute those paths, so the providers are live test dependencies.
- `translator`: removed its direct test-scope provider declaration. Neither production nor test source currently references `JsonUtil` or another path that selects a `JsonSerializerPolicy`; Task 14's planned integration quickstart will add the dependency it actually needs.
- `mask`: removed its direct test-scope provider declaration. Current tests use the direct Jackson adapter API without executing `JsonUtil`; Task 15's planned `team4u-mask-jackson` quickstart will own its provider dependency. Its published direct `jackson-databind` edge remains `<optional>true</optional>` until Task 15, so consumers choose the adapter/Jackson dependency explicitly.
- `log`: provider remains test scope because current tests execute `JsonUtil`; direct Jackson remains nonoptional compile because production source imports Jackson APIs. Consumers of the current monolith therefore receive Jackson until the Task 17 split.
- `retry-lease-runtime`: direct Jackson compile dependency remains explicit and nonoptional because `LeaseRetryRecordSerializer` uses the Jackson tree API for a versioned durable schema, field-level validation, and a throwable allowlist; the generic serializer SPI intentionally cannot model that contract. This is the release allowlist's sole pre-split explicit integration exception. No provider or runtime-scope workaround was added.

## Residual Risks

- Mask/log still publish direct Jackson dependencies until their planned splits.
- Retry-lease-runtime permanently carries direct, nonoptional Jackson as the durable-record integration exception.
- No standalone JDK 8 is installed; Java 8 evidence is Enforcer plus bytecode major version 52.
