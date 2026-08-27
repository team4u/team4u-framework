# Team4u 1.0 Breaking Changes

| Version | Breaking change | Migration |
| --- | --- | --- |
| 1.0 | team4u-proxy no longer passes ByteBuddy transitively | Interface-only consumers need no change. Concrete-class proxy consumers must add `net.bytebuddy:byte-buddy` explicitly; without it, class proxy creation fails with a two-line ProxyException. |
| 1.0 | Removed pure grouping artifacts team4u-config/kv/lease/retry/serializer | Depend directly on concrete artifacts managed by the root BOM. |
| 1.0 | Root POM is the only BOM | Import com.team4u:team4u-framework:type=pom; do not import a separate BOM. |
| 1.0 | Serializer API no longer implies a Jackson runtime provider | Add team4u-serializer-jackson explicitly or provide/register a custom JsonSerializerPolicy. Task 7 makes the no-provider error name these choices. |
| 1.0 | JDBC utilities moved from team4u-base to team4u-base-jdbc | Add com.team4u:team4u-base-jdbc; all JDBC FQCNs are unchanged. |
| 1.0 | Criterion wildcard matching moved from Spring AntPathMatcher to Base PathPatternMatcher | DSL behavior and the locked 53-case matrix are unchanged; call Base `PathPatternMatcher` directly for pure Java path matching. |
| 1.0 | Removed Team4uCriterionAutoConfiguration | The criterion artifact no longer carries Spring integration; expose required global singletons with application configuration. |
| 1.0 | Consumer gates are staged during convergence | Run consumer-it for the three currently green consumers; serializer-api and config-core transition guards are explicit until Tasks 7 and 9. |
