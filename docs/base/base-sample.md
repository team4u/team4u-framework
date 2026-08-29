# 实战案例

本章介绍 `team4u-base` 核心工具在高性能系统与基础设施中的综合落地实践。

---

## 基于 `TextTemplate` 的多维度 Kafka Topic 路由计算

### 业务场景
消息路由网关需根据租户 ID、业务类型与部署环境动态计算 Kafka Topic：`app.topic.${env}.${tenantId}.${bizType}`。

### 代码实现
```java
import com.team4u.framework.base.util.TextTemplate;
import java.util.HashMap;
import java.util.Map;

public class DynamicTopicResolver {

    // 预解析模板（渲染时不做正则解析）
    private static final TextTemplate TOPIC_TEMPLATE = 
            new TextTemplate("app.topic.${env}.${tenantId}.${bizType}");

    public static String resolveTopic(String tenantId, String bizType) {
        String env = System.getProperty("app.env", "prod");

        Map<String, Object> context = new HashMap<>();
        context.put("env", env);
        context.put("tenantId", tenantId);
        context.put("bizType", bizType);

        return TOPIC_TEMPLATE.render(context);
    }
}
```

---

## 基于 `DynamicInstanceProvider` 的动态规则引擎加载流水线

### 业务场景
从配置中心接收 JSON 格式的流控规则，并将其转换为可执行的 `RateLimitExecutor` 实例。要求：
1. 相同 JSON 配置不重复反序列化与编译；
2. 高并发 Cache Miss 时防并发穿透；
3. 配置变更时能安全加载新规则实例。

### 代码实现
```java
import com.team4u.framework.base.instance.DynamicInstanceProvider;
import com.team4u.framework.serializer.json.JsonUtil;

public class DynamicRateLimiterManager {

    public static class LimiterConfig {
        private String resource;
        private int qps;
        public String getResource() { return resource; }
        public int getQps() { return qps; }
    }

    public static class RateLimitExecutor {
        private final LimiterConfig config;
        public RateLimitExecutor(LimiterConfig config) {
            this.config = config;
        }
        public boolean tryAcquire() {
            return true;
        }
    }

    // 基于 String LRU 缓存的实例提供者
    private final DynamicInstanceProvider<String, LimiterConfig, RateLimitExecutor> provider =
            DynamicInstanceProvider.createStringLru(
                    500, // 最大缓存 500 条不同规则
                    json -> JsonUtil.toBean(json, LimiterConfig.class), // Input -> Config
                    RateLimitExecutor::new                             // Config -> Instance
            );

    public RateLimitExecutor getExecutor(String ruleJson) {
        // 内部自动执行两级缓存与 128 分段锁防穿透
        return provider.get(ruleJson);
    }
}
```

---

## 基于 `JdbcUtil` 与 `SqlBuilder` 的轻量级数据同步

### 业务场景
开发轻量级后台同步任务，根据动态过滤条件从数据表中拉取分页数据，并将处理结果批量入库。

### 代码实现
先引入独立模块 `team4u-base-jdbc`：

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-base-jdbc</artifactId>
</dependency>
```

```java
import com.team4u.framework.base.jdbc.InsertBuilder;
import com.team4u.framework.base.jdbc.JdbcUtil;
import com.team4u.framework.base.jdbc.SqlBuilder;
import javax.sql.DataSource;
import java.util.List;

public class OrderSyncTask {

    public static class SyncRecord {
        private Long id;
        private String orderNo;
        private String status;
        public Long getId() { return id; }
        public String getOrderNo() { return orderNo; }
        public String getStatus() { return status; }
    }

    public void processPendingSync(DataSource ds, List<String> targetStatuses) throws Exception {
        // 1. 流式动态查询
        SqlBuilder query = new SqlBuilder("SELECT id, order_no, status FROM t_order WHERE 1=1")
                .inIfNotEmpty(" AND status IN ", targetStatuses)
                .append(" ORDER BY id ASC LIMIT 100");

        List<SyncRecord> records = JdbcUtil.queryList(ds, query.getSql(), SyncRecord.class, query.getParams());

        // 2. 批量记录同步日志
        for (SyncRecord record : records) {
            InsertBuilder insertLog = new InsertBuilder("t_order_sync_log")
                    .column("order_id", record.getId())
                    .column("order_no", record.getOrderNo())
                    .column("status", "SYNCED");

            JdbcUtil.execute(ds, insertLog.getSql(), insertLog.getParams());
        }
    }
}
```
