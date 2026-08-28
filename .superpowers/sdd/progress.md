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
| 9. Split config-proxy and config-spring | Pending |
| 10. Lease Boundary Before Retry Migration | Pending |
| 11. Retry Core/Managed/Dynamic Split Without Cycles | Pending |
| 12. KV Space Split and Proxy-Free HotSwapStore | Pending |
| 13. Router Core and Router Proxy Split | Pending |
| 14. Translator Convergence After Router | Pending |
| 15. Split Mask Core, Jackson Adapter, and Dynamic Config | Pending |
| 16. Split bean-spring as Plain Spring Configuration | Pending |
| 17. Log Core/Governance Split with Injected Serializer and Interceptors | Pending |
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
