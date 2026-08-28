# Team4u 1.0 Convergence Progress

- Worktree: `/root/code/team4u-framework/.worktrees/framework-convergence`
- Branch: `refactor/framework-convergence`
- Worktree baseline: `11e1eec32a2704befa1976c1dd4d828011b24f21`
- Functional baseline: `a4f9d9d2dc0f1aecd86f4a48769a4365ff9beb4a`
- Plan: `docs/superpowers/plans/2026-08-27-team4u-1.0-convergence.md`
- Design: `docs/superpowers/specs/2026-08-27-team4u-1.0-convergence-design.md`

| Task | Status |
| --- | --- |
| 0. Convergence plan acceptance checkpoint | Complete |
| 1. Root Maven Foundation, Flatten Aggregators, License | Complete |
| 2. Matrix CI, External Consumers, Dependency Baselines | Complete |
| 3. Split base-jdbc and Delete Base SpringUtil | Complete |
| 4. Lock Current Ant Wildcard Semantics | Complete |
| 5. Base PathPatternMatcher and Criterion Adapter | Complete |
| 6. Proxy One Artifact and Optional ByteBuddy | Complete |
| 7. Serializer Provider Contracts and Upstream Cleanup | Complete |
| 8. Config Dependency Inversion and Global Initialization Fix | Complete |
| 9. Split config-proxy and config-spring | Complete |
| 10. Lease Boundary Before Retry Migration | Complete |
| 11. Retry Core/Managed/Dynamic Split Without Cycles | Complete |
| 12. KV Space Split and Proxy-Free HotSwapStore | Complete |
| 13. Router Core and Router Proxy Split | Complete |
| 14. Translator Convergence After Router | Complete |
| 15. Split Mask Core, Jackson Adapter, and Dynamic Config | Complete |
| 16. Split bean-spring as Plain Spring Configuration | Complete |
| 17. Log Core/Governance Split with Injected Serializer and Interceptors | Complete |
| 18. JMH Evidence, Performance Copy Cleanup, Release Gate | Pending |

Review remediation: complete. Commit subject: `docs: fix convergence plan review findings`.
Second review remediation: complete (docs: remove remaining provider leakage ambiguity).
Task 2 review remediation: complete (fix(ci): track serializer provider transition).
Task 3 review remediation: complete (fix(base): preserve retry fallback and migration records).
Task 4 complete: locked the 53-case Spring Ant matrix and Criterion adapter/null behavior; commit `test(criterion): characterize wildcard semantics`.
Task 4 review remediation: complete; corrected the local record confusion without changing the committed 53-case matrix or tests.
Task 5 complete: added pure Java Base matcher, preserved Criterion adapter semantics, and removed Criterion Spring production coupling.

Task 6 complete: kept the one-artifact public ProxyBuilder API, made ByteBuddy optional and reflectively loaded, added isolated/consumer proofs, and committed `refactor(proxy): make bytebuddy optional`.

Task 7 complete: enforced serializer provider boundaries, cleaned unused translator/mask test providers, and recorded retry-lease-runtime as the permanent Jackson durable-schema integration exception; review remediation complete.

Task 8 complete: creator resolution and lazy global initialization landed at `8bebace`; review remediation serialized global/reset and reload lifecycle operations, added ServiceLoader failure coverage, and verified Java 8 quick start. Final operational remediation added callback split, repeated-debounce tokens, retained 16 focused lifecycle/reload tests, and confirmed the intentional proxy-only Task9 RED. Final fix commit: `fix(config): dispatch reload listeners outside locks`.

Task 9 complete: split config proxy/Spring adapters, kept proxy creation explicit, and verified active consumers plus release packaging.

Task 10 complete: added deterministic memory/JDBC lease quickstarts, enforced lease-core and lease-jdbc runtime boundaries, documented explicit JSON provider choice, and verified the full reactor plus release gates. Review remediation corrected the Enforcer patterns to scope-aware five-field form; the prior failed probe was a four-field construction, not a wildcard limitation. Final correction commit: `fix(lease): use scope-aware enforcer patterns`.

Task 11 complete: split managed governance and config-driven retry policies out of retry-core, replaced `Retries.managed(...)` with `ManagedRetries.with(...)`, and kept retry-core free of managed/config dependencies. Full reactor, consumer/release gates, and release packaging passed after one unrelated KV timing rerun; commit `refactor(retry): split managed governance without core cycles`.

Task 12 complete: moved typed JSON Spaces to `team4u-kv-space`, moved HotSwapStore to a KV-local `HotSwap` contract with JDK proxies and stable creation-time capability interfaces, and removed proxy/policy/serializer production coupling from kv-core. No KV heartbeat work was included.

Task 13 complete: moved the declarative proxy adapter to `team4u-router-proxy` with unchanged FQCNs, kept router core free of proxy/bean/config-proxy/Jackson compile/runtime edges, and documented the explicit adapter dependency and concrete-class ByteBuddy fallback. Review remediation corrected the Enforcer field-order record to valid scope-aware five-field shorthand (classifier omitted); compile/runtime rejection and test allowance were independently proven, and the working POM rules were unchanged.

Task 14 complete: added a real JSON-policy translator quickstart without router mocks, test-scoped config-test and the Jackson provider, enforced the router-core-only compile/runtime boundary, and documented the explicit provider/adapter split. Commit `test(translator): lock post-router boundary`.

Task 15 complete: split mask core, Jackson adapter, and config-driven rules with unchanged adapter FQCNs, fail-closed policy resolution, and deterministic global resolver installation. Review remediation made explicit null initial/hot/manual rules fail closed, added ownership-aware atomic global uninstall, restored `FastMasker` and Jackson `MaskConfig` APIs, broadened the Spring Enforcer patterns with negative probes, and corrected the quickstart/full-reactor evidence. Final remediation commit: `fix(mask): close dynamic rule and lifecycle gaps`. Final review remediation rejects manual null class/field maps before publication, deep snapshots caller maps, and verifies the 54 split-module tests; publication remains pending.

Task 16 complete: split the unchanged-FQCN Spring adapter into plain `team4u-bean-spring`, kept bean core Spring-free, and moved retry-spring to shared explicit configuration. Full clean tests, consumer/release contracts, release packaging, Java 8 bytecode, dependency guards, and exact Git rename checks passed; one unrelated KV timing test passed on focused rerun after a transient consumer-it verify failure. Report: `.superpowers/sdd/task-16-report.md`.

Task 17 complete: split provider-free log core from governance runtime with unchanged moved FQCNs, explicit Jackson/provider ownership in governance, a single-dependency external consumer, migration and log-doc updates, full clean tests, consumer/release gates, release packaging, and Java 8 bytecode. Report: `.superpowers/sdd/task-17-report.md`.
