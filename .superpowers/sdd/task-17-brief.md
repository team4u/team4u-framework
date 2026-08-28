### Task 17: Log Core/Governance Split with Injected Serializer and Interceptors

**Files:**

- Create:
  - `team4u-log-core/pom.xml`
  - `team4u-log-governance/pom.xml`
- Move to core:
  - `LogContext`, `LogSpan`, `Loggers`
  - `core/**`
  - `appender/**`
  - `support/**`
  - generic pipeline APIs and non-config interceptors
- Move to governance:
  - `LogBootstrap`
  - `proxy/**`
  - `config/**`
  - `jackson/**`
  - `spring/**`
  - `TargetedDyeingInterceptor`
- Delete or replace `team4u-log/pom.xml`.
- Modify root BOM/module list, log docs, and migration documents.

**Interfaces:**

```java
public final class LogEngine {
    public static Builder builder();

    public static final class Builder {
        public Builder serializer(LogSerializer serializer);
        public Builder interceptor(LogInterceptor interceptor);
        public Builder interceptors(Iterable<? extends LogInterceptor> interceptors);
        public LogEngine build();
    }
}
```

- Core default serializer is explicit plain text/`toString`, never Jackson.
- Governance supplies Jackson serialization and governance interceptors through explicit assembly.
- Core bans Jackson, Spring, ByteBuddy, Mask, Mask Config, Config, Criterion, and Proxy.
- Transition expiry: the old `team4u-log` monolith keeps its direct Jackson production dependency from Task 7 only until this split; `team4u-serializer-jackson` is test-only only during that transition. After the split, `team4u-log-governance` is the explicit runtime owner of `team4u-serializer-jackson` and its Jackson dependency, as required by the Task 17 dependency graph.
- Governance owns `FastMasker` from mask core, `MaskRuleRepository` from mask-config, and `JacksonMaskModule` from mask-jackson.

**Dependency graph:**

```text
log-core -> base, policy, slf4j
log-governance -> log-core, serializer-jackson, config-core, criterion, mask, mask-config, mask-jackson, proxy, Spring
```

**Checklist:**

- [ ] Step 1: Test-first RED. Add core/governance test selections before module creation; expected compilation failure proves the current single module is still entangled.
- [ ] Step 2: Move packages, inject serializer/interceptors, move Jackson/config/proxy/masking integration, and update explicit Spring imports.
- [ ] Step 3: GREEN:

```bash
mvn -q -pl :team4u-log-core -am -Dtest=LogQuickstartTest,LogEngineTest test
mvn -q -pl :team4u-log-governance -am \
  -Dtest=LogGovernanceQuickstartTest,LogMaskingTest,LogConfigReloadTest,DynamicLogProxyIntegrationTest \
  test
```

- [ ] Step 4: Dependency guard:

```bash
mvn -q -pl :team4u-log-core dependency:tree \
  -Dincludes=com.fasterxml.jackson,org.springframework,com.team4u:team4u-config-core,com.team4u:team4u-mask,com.team4u:team4u-proxy
```

Expected: no matching production edge.

- [ ] Step 5: Module acceptance and full reactor:

```bash
mvn -q -pl :team4u-log-core,:team4u-log-governance -am test
mvn -q clean test
```

- [ ] Step 6: Document new artifacts, injected defaults, and governance assembly.
- [ ] Step 7: Review and commit `git commit -m "refactor(log): split core and governance runtime"`.

**Rollback:** Revert the complete log split; earlier modules do not depend on the new artifacts.

---
