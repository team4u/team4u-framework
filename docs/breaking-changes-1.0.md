# Team4u 1.0 Breaking Changes

| Version | Breaking change | Migration |
| --- | --- | --- |
| 1.0 | team4u-proxy no longer passes ByteBuddy transitively | Interface-only consumers need no change. Concrete-class proxy consumers must add `net.bytebuddy:byte-buddy` explicitly; this also applies to team4u-log and retry-proxy. `team4u-config-proxy` owns ByteBuddy at runtime, so adding that adapter alone is sufficient for config class proxies. |
| 1.0 | Removed pure grouping artifacts team4u-config/kv/lease/retry/serializer | Depend directly on concrete artifacts managed by the root BOM. |
| 1.0 | Managed retry and config-driven retry policies moved out of retry-core | Add team4u-retry-managed for ManagedRetries, ManagedRetryClient, managed records/store APIs, and ManagedSubmitResult; add team4u-retry-config for DynamicRetryPolicyRegistry. Retries now supports INLINE only, and the moved retry FQCNs change as documented in MIGRATION-1.0.md. |
| 1.0 | Root POM is the only BOM | Import com.team4u:team4u-framework:type=pom; do not import a separate BOM. |
| 1.0 | ConfigManager.Builder.configBinder(...) removed; ConfigProxyCreator is required for createProxy | Use DefaultConfigBinder.bind(...) directly for pinned POJOs, and add team4u-config-proxy for automatic ServiceLoader creator discovery or provide a ConfigProxyCreator. createProxy no longer falls back to binding. |
| 1.0 | Config proxy and Spring adapters moved out of config-core | Add team4u-config-proxy for ConfigProxyFactory/SnapshotAware. Add team4u-config-spring and explicitly import Team4uConfigConfiguration; no Boot auto-configuration metadata is provided. |
| 1.0 | ConfigManager.global() is lazily initialized and ConfigBootstrap refreshes initialized globals on registration/lock | Keep bootstrap registrations before application use; late registrations before lock are reflected without caller-side refresh. |
| 1.0 | Serializer API no longer implies a Jackson runtime provider | Add com.team4u:team4u-serializer-jackson explicitly, or register/provide a custom JsonSerializerPolicy via ServiceLoader. The Task 7 no-provider error names both choices; upstream libraries own no runtime provider. |
| 1.0 | JDBC utilities moved from team4u-base to team4u-base-jdbc | Add com.team4u:team4u-base-jdbc; all JDBC FQCNs are unchanged. |
| 1.0 | Criterion wildcard matching moved from Spring AntPathMatcher to Base PathPatternMatcher | DSL behavior and the locked 53-case matrix are unchanged; call Base `PathPatternMatcher` directly for pure Java path matching. |
| 1.0 | Removed Team4uCriterionAutoConfiguration | The criterion artifact no longer carries Spring integration; expose required global singletons with application configuration. |
| 1.0 | Consumer gates are staged during convergence | consumer-it/release-contracts run minimal, config-core, serializer-api, serializer-jackson, and interface-proxy. The config-core consumer now proves the proxy-free core boundary. |
