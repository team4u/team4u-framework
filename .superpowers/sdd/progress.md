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
| 18. JMH Evidence, Performance Copy Cleanup, Release Gate | Complete |

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

Task 15 complete: split mask core, Jackson adapter, and config-driven rules with unchanged adapter FQCNs, fail-closed policy resolution, and deterministic global resolver installation. Review remediation made explicit null initial/hot/manual rules fail closed, added ownership-aware atomic global uninstall, restored `FastMasker` and Jackson `MaskConfig` APIs, broadened the Spring Enforcer patterns with negative probes, and corrected the quickstart/full-reactor evidence. Final remediation commit: `fix(mask): close dynamic rule and lifecycle gaps`. Final review remediation rejects manual null class/field maps before publication, deep snapshots caller maps, and verifies the 54 split-module tests; final review approved.

Task 16 complete: split the unchanged-FQCN Spring adapter into plain `team4u-bean-spring`, kept bean core Spring-free, and moved retry-spring to shared explicit configuration. Full clean tests, consumer/release contracts, release packaging, Java 8 bytecode, dependency guards, and exact Git rename checks passed; one unrelated KV timing test passed on focused rerun after a transient consumer-it verify failure. Report: `.superpowers/sdd/task-16-report.md`.

Task 17 complete: split provider-free log core from governance runtime with unchanged moved FQCNs, explicit Jackson/provider ownership in governance, a single-dependency external consumer, migration and RAW/UNMASKED log-doc warnings, atomic helper/engine ownership remediation, identity-safe interceptor management, per-engine appender locking, and final transfer-race remediation: current-global instance set/CAS serialize under global ownership with lock-free detached fast path, eliminating install-snapshot lost updates while preserving independent detached writes. Serializer binding and global transform callbacks are documented nonblocking and non-reentrant under ownership/local synchronization. Live FinOps hot-update proof, full clean tests (1,562/1,562), consumer/release gates, release packaging, and Java 8 bytecode are recorded in `.superpowers/sdd/task-17-report.md`.

Task 18 complete: four Java 8 JMH benchmark classes cover five advertised hot-path methods, raw one-fork GC evidence is committed, and performance copy now uses only supported wording. Final local clean install passed 1,565/1,565 tests; six consumer contracts, six release-contract consumers, 40-leaf artifact/dependency gates, release packaging, and Java 8 bytecode audits passed. The full sequential JDK matrix was additionally executed locally in Docker (`maven:3.9.11-eclipse-temurin` 8/11/17/21, Temurin `1.8.0_472`/`11.0.29`/`17.0.17`/`21.0.9`): per JDK 1,565/1,565, bench 5 methods, 6+6 consumers, claims gate, release package, 40×3 manifest, 678 major-52 classfiles, and the release script 40 trees / 22 shapes on all four JDKs after a one-line JDK 8 fix (`javac` on Java 8 does not create the `-d` output directory; `mkdir -p` added before compile, RED reproduced with the unmodified script, fixed script rerun green in the same container; one preexisting scheduler-sensitive KV heartbeat transient in a JDK 8 test stage passed on full rerun — environmental record only, no code change). Hosted GitHub Actions JDK 8/11/17/21 execution remains required before release or changing `1.0.0-SNAPSHOT`. Report: `.superpowers/sdd/task-18-report.md`.

Task 18 review remediation: complete. Added the trial-level TieredStore L2-zero-calls teardown proof, softened the last two production Javadoc performance claims (comments only), hardened the performance claims gate to case-insensitive Chinese/English variants over docs plus all non-target `src/main/java` sources with raw-evidence checks (the prior gate was a recorded false-green), added the `team4u-base-jdbc` representative shape and `.worktrees`-safe find fallback, and reran the official KV JMH benchmark (`45.978 ± 1.279 ns/op`). Commit: `fix(performance): close benchmark claim gaps`. GitHub Actions matrix evidence is still outstanding.

Task 18 final residual claim remediation: complete. The review's minor findings (residual `无 GC 开销`/`零对象创建`/`零开销`-family wording in active sources and docs) were promoted to required fixes; a full variant rescan found four production comment lines and seven active doc files still carrying absolute claims; all were softened to mechanism descriptions, the claims gate was extended (spaced `无 GC`, `零/0 对象(创建)`, `零/0 开销`, zero/no object-creation/overhead, `*-free` families, boundary-guarded `0 GCC`) with sandbox RED/GREEN proof and a false-green replay on a restored-claims worktree copy; gate GREEN, full clean install, release javadoc rebuild, 40-jar banned-variant scan (zero hits), and major-52 bytecode audit re-verified; JMH and consumers not rerun (comments/docs only). Commit: `docs(performance): remove residual absolute claims`. Report: `.superpowers/sdd/task-18-report.md`.

Task 18 semantic scanner round: complete. The broken Bash Unicode postfilter was replaced by a Java 8 semantic scanner (`scripts/PerformanceClaimScanner.java`: UTF-8, locale-independent java.util.regex, subclause splitting at Chinese/ASCII punctuation, region matching with transparent bounds) driven by a thin `scripts/check-performance-claims.sh` wrapper (mktemp/trap, `javac -source 8 -target 8`, README/raw-evidence checks retained). TDD: the finished gate was run on the unsoftened tree first — RED with exactly 20 hits (15 doc lines in 12 files: benchmarks README `zero-allocation` caveat, base README/base-sample/quick-start 零正则, config-instance/config-proxy 无…开销, criterion-compiler 无任何…开销, kv-decorators/kv-lifecycle/kv-tiered/log-sample/mask-annotation 零X开销 family; plus 5 comment-only production lines: LogicCriterionCompiler 无迭代器对象生成, SmartCompare `0 内存分配`, TieredStore/ExpiringValue 零X开销, KeyedPolicyRegistry 彻底消除 ArrayList 创建). Sandbox corpus: 40/40 positives RED and 47/47 negatives GREEN under both the default UTF-8 locale and `LC_ALL=C`; `GC-freeware`, `GC freelance`, `0 GCC`, `0GCC`, `x0GC`, `10GC`, `1.0GC` verified negative; 无锁 alone, 无 BigDecimal, 无反射/无正则 alone, `zero get calls`, `gc.alloc.rate.norm`, `TimeUnit.NANOSECONDS` verified allowed. All 20 real claims then softened to mechanism wording (docs + comments only; behavior/API/version/POM/raw JMH/KV untouched). Verification this round: full clean install 1,565/1,565; `-Prelease package` rebuilt 40 javadoc jars, semantic scan of all 40 unpacked javadocs = 0 banned variants; DOM manifest 40/40 identical; benchmark jar `-l` = exactly 5 methods in 4 classes; 678 production classfiles in the 40 binary JARs, 678/678 major 52; `bash -n`; gate GREEN under default/C/zh_CN.UTF-8 locales and false-green replay RED on re-added claim. JMH, consumers, and the release script were not rerun (no runtime or packaging inputs changed; the release script was freshly run by the independent review after 8c). GitHub Actions JDK 8/11/17/21 matrix remains pending. Commit subject: `docs(performance): close semantic claim variants`.

Task 18 final scanner hardening: complete. The final review's three Important findings (lowercase-gc Chinese/digit/frequent-GC families, idiom-guard whole-match skip swallowing `无锁零GC`/`无锁零分配`, and docs `.html` never scanned) were reproduced RED in a new built-in `PerformanceClaimScanner --self-test` corpus (43 positive / 34 negative static cases, no file dependencies, mismatch prints case/expected and exits non-zero; the thin wrapper runs self-test before every root scan): 12 mismatches before the fixes, all as the review predicted, all 34 negatives already correct. Fixes: case-insensitive GC-keyword families (quantifier prefix keeps `gc.alloc.*` safe), idiom-guard region restart after the rejected quantifier codepoint (strictly advancing, digit head never restarts, transparent bounds kept), `free` separator space/hyphen/underscore 0..2 with trailing word boundary plus the `garbage([ _-]+collection)?` noun (`GC-freeware`/`GC freelance` stay negative), and case-insensitive `.md/.markdown/.html/.htm` docs collection (`docs/index.html` now scanned; superpowers still excluded; sandbox html injection RED). Unnecessary `System.setErr` wrapping removed (UTF-8 diagnostics verified in zh/C locales). Gate GREEN under default/`LC_ALL=C`/`C.UTF-8`/`zh_CN.UTF-8` (124 docs files + 514 sources); fresh-sandbox docs+source RED, no-sources RED, bad-args exit 2; source-8 compile clean, `bash -n`, `git diff --check` clean; full reactor clean install rerun 1,565/1,565 (0 failures/errors/skipped, 253 report files). Release packaging, JMH, consumers, and the release script not rerun (inputs unchanged; earlier 40x3/678/40-tree evidence stands). Matrix still pending. Commit: `fix(performance): harden semantic claim scanner`. Report: `.superpowers/sdd/task-18-report.md`.

Final audit README grouping labels corrected.
