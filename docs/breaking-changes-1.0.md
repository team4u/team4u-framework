# Team4u 1.0 Breaking Changes

| Version | Breaking change | Migration |
| --- | --- | --- |
| 1.0 | Removed pure grouping artifacts team4u-config/kv/lease/retry/serializer | Depend directly on concrete artifacts managed by the root BOM. |
| 1.0 | Root POM is the only BOM | Import com.team4u:team4u-framework:type=pom; do not import a separate BOM. |
| 1.0 | JDBC utilities moved from team4u-base to team4u-base-jdbc | Add com.team4u:team4u-base-jdbc; all JDBC FQCNs are unchanged. |
| 1.0 | Deleted com.team4u.framework.base.util.SpringUtil | Register beans with BeanManager-compatible providers and call BeanManager.getInstance().getBean(...). |
| 1.0 | Consumer gates are staged during convergence | Run consumer-it for the three currently green consumers; serializer-api and config-core transition guards are explicit until Tasks 7 and 9. |
