# 扩展机制与 SPI

`team4u-flow` 采用高度模块化和 SPI 解耦设计，核心纯轻量，所有持久化、编解码、监控与结构分析均可通过标准接口灵活扩展。

---

# 1. 状态编解码扩展 (`StateMapper`)

### 接口定义
在 `DurableFlow` 中，节点活动值和输入输出需要被序列化为 `StoredValue`。系统默认提供支持基础类型与 `Serializable` 的 `DefaultStateMapper`。

```java
public interface StateMapper {
    StoredValue encode(Object value) throws Exception;
    Object decode(StoredValue storedValue) throws Exception;
}
```

### 实战：集成 Jackson JSON 编解码器

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team4u.framework.flow.durable.StateMapper;
import com.team4u.framework.flow.durable.StoredValue;

public class JacksonStateMapper implements StateMapper {

    private final ObjectMapper objectMapper;

    public JacksonStateMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredValue encode(Object value) throws Exception {
        if (value == null) {
            return new StoredValue("null", new byte[0]);
        }
        byte[] jsonBytes = objectMapper.writeValueAsBytes(value);
        return new StoredValue(value.getClass().getName(), jsonBytes);
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        if ("null".equals(storedValue.typeId())) {
            return null;
        }
        Class<?> targetClass = Class.forName(storedValue.typeId());
        return objectMapper.readValue(storedValue.data(), targetClass);
    }
}
```

在初始化 `DurableRuntime` 时注入：

```java
DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(new JacksonStateMapper(new ObjectMapper()))
        .build();
```

---

# 2. 持久化存储扩展 (`DurableStore`)

### 接口定义
`DurableStore` 是快照持久化的唯一接口，仅需提供按 ID 查询和乐观锁 CAS 写入：

```java
public interface DurableStore {
    DurableSnapshot load(String flowId, String executionId);
    boolean save(DurableSnapshot snapshot, long expectedRevision);
}
```

### 实战：基于 JDBC 的数据库存储实现

通过数据库表的唯一键与 `revision` 字段实现原子 CAS：

```sql
CREATE TABLE flow_durable_snapshot (
    flow_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(128) NOT NULL,
    flow_version INT NOT NULL,
    format_id VARCHAR(64) NOT NULL,
    format_version INT NOT NULL,
    revision BIGINT NOT NULL,
    lifecycle VARCHAR(32) NOT NULL,
    frame_state TEXT NOT NULL,
    slots_payload MEDIUMTEXT NOT NULL,
    failure_payload TEXT,
    stop_reason_payload TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (flow_id, execution_id)
);
```

```java
public class JdbcDurableStore implements DurableStore {

    private final DataSource dataSource;

    public JdbcDurableStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public DurableSnapshot load(String flowId, String executionId) {
        String sql = "SELECT * FROM flow_durable_snapshot WHERE flow_id = ? AND execution_id = ?";
        // 查询数据库并反序列化为 DurableSnapshot
        return querySnapshot(sql, flowId, executionId);
    }

    @Override
    public boolean save(DurableSnapshot snapshot, long expectedRevision) {
        if (expectedRevision == 0) {
            // 首次插入
            String insertSql = "INSERT INTO flow_durable_snapshot (flow_id, execution_id, flow_version, format_id, format_version, revision, lifecycle, frame_state, slots_payload) " +
                               "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            return executeInsert(insertSql, snapshot);
        } else {
            // CAS 更新：仅当当前 revision 等于 expectedRevision 时更新成功
            String updateSql = "UPDATE flow_durable_snapshot SET revision = ?, lifecycle = ?, frame_state = ?, slots_payload = ?, failure_payload = ? " +
                               "WHERE flow_id = ? AND execution_id = ? AND revision = ?";
            int rows = executeUpdate(updateSql, snapshot.revision(), snapshot.lifecycle().name(),
                                     snapshot.frameState(), snapshot.slots(), snapshot.failure(),
                                     snapshot.flowId(), snapshot.executionId(), expectedRevision);
            return rows > 0;
        }
    }
}
```

---

# 3. 步骤拦截器扩展 (`StepInterceptor`)

`StepInterceptor` 提供环绕拦截（Around Advice）能力，可用于链路耗时统计、分布式 Tracing（SkyWalking/OpenTelemetry 埋点）、全局异常审计或限流治理：

```java
public class PerformanceTraceInterceptor implements StepInterceptor {

    @Override
    public <I, O> O intercept(Chain<I, O> chain) throws Exception {
        StepContext ctx = chain.context();
        long start = System.nanoTime();
        try {
            O output = chain.proceed(chain.input());
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (costMs > 500) {
                System.out.printf("[SLOW STEP] Node [%s] in execution [%s] took %d ms%n",
                        ctx.nodeId(), ctx.executionId(), costMs);
            }
            return output;
        } catch (Exception e) {
            System.err.printf("[STEP ERROR] Node [%s] failed with: %s%n", ctx.nodeId(), e.getMessage());
            throw e;
        }
    }
}
```

应用到 Flow 定义：

```java
Flow<Order, Receipt> flow = Flows.<Order>begin("checkout")
        .interceptor(new PerformanceTraceInterceptor())
        .step("step-1", ...)
        .step("step-2", ...)
        .build();
```

---

# 4. 只读结构投影访问 (`Flow.Projection<R>`)

`Flow.Projection` 是核心暴露的只读访问者（Visitor）SPI。外部工具可通过实现该接口遍历不可变流程逻辑树，实现静态分析、导出图表或编译为自定义执行计划，**无需使用反射或侵入核心内部包**：

```java
public class StepNameCollector implements Flow.Projection<List<String>> {

    @Override
    public List<String> projectSequence(Flow.SequenceInfo info, List<List<String>> children) {
        List<String> result = new ArrayList<>();
        for (List<String> child : children) {
            result.addAll(child);
        }
        return result;
    }

    @Override
    public <T, R1> List<String> projectStep(Flow.StepInfo info, Step<T, R1> step, Step.Contextual<T, R1> contextualStep, List<StepInterceptor> interceptors) {
        return Collections.singletonList(info.id());
    }

    // 其他节点的 project 方法省略...
}

// 调用投影
List<String> stepNames = flow.project(new StepNameCollector());
```
