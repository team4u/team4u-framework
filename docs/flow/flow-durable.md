# Durable 持久化执行

`team4u-flow-durable` 是针对长时间运行、跨进程恢复、网络超时容错场景的持久化执行器。

---

# 1. 核心理念

1. **完全复用同一份 `Flow` 定义**：不另造 DSL，本地纯内存测试通过的流程可直接注册为 DurableFlow。
2. **零 Lambda 序列化**：快照中仅保存已执行的游标、分支选择和业务状态值（`StoredValue`），**绝不序列化 Java 代码、Step 实例或 Lambda 表达式**。
3. **节点级 CAS 检查点**：每个节点执行前后以乐观锁推进快照版本（`revision`），避免多实例并发执行冲突。
4. **版本强隔离**：以 `flowId + flowVersion` 显式标识，不支持结构猜测与自动迁移，保障运行稳定性。

---

# 2. 状态机与生命周期

```
start:             absent -> ACTIVE
normal execution:  ACTIVE -> COMPLETED | STOPPED | FAILED
retry:              FAILED -> ACTIVE   (从最后成功快照反序列化新对象重试)
cancel:             ACTIVE | FAILED -> CANCELLED
```

---

# 3. 核心 API

### 3.1 运行时初始化与流程注册

```java
// 1. 初始化持久化存储与编解码器
DurableStore store = new InMemoryDurableStore(); // 生产环境可替换为 JDBC / Redis 存储
DurableRuntime runtime = DurableRuntime.builder(store)
        .stateMapper(DefaultStateMapper.INSTANCE)
        .build();

// 2. 注册指定版本的 Flow
DurableFlow<OrderContext, Receipt> checkoutV1 = runtime.register(checkoutFlow, 1);
```

### 3.2 启动新执行 (`start`)

```java
// 首次调用将落入初始快照（revision=1, lifecycle=ACTIVE），随后推进执行
DurableResult<Receipt> result = checkoutV1.start("ORDER-20260830", order);

if (result.isCompleted()) {
    System.out.println("成功: " + result.value());
} else if (result.isStopped()) {
    System.out.println("正常终止: " + result.stopReason());
} else if (result.isFailed()) {
    System.out.println("失败: " + result.failure());
}
```

### 3.3 崩溃恢复 (`recover`)

当服务因重启、断网等意外中断时，调用 `recover` 会从最后一次 CAS 成功的快照节点继续向后执行：

```java
// 恢复处于 ACTIVE 状态的执行；已完成或已停止的执行直接返回结果
DurableResult<Receipt> recResult = checkoutV1.recover("ORDER-20260830");
```

### 3.4 失败重试 (`retry`)

当节点出现临时网络错误导致流程转为 `FAILED` 时，可手动或通过调度任务触发 `retry`：

```java
// CAS 将快照转回 ACTIVE，从最后成功快照重新解码出 Java 业务对象并重试失败节点
DurableResult<Receipt> retryResult = checkoutV1.retry("ORDER-20260830");
```

### 3.5 流程取消 (`cancel`)

```java
// 将 ACTIVE 或 FAILED 状态的执行直接置为 CANCELLED（后续 retry 将被拒绝）
boolean cancelled = checkoutV1.cancel("ORDER-20260830");
```

---

# 4. 快照存储与扩展 (`DurableStore`)

`DurableStore` 仅需实现两个方法：

```java
public interface DurableStore {
    // 按 flowId + executionId 加载快照
    DurableSnapshot load(String flowId, String executionId);

    // 以 CAS 乐观锁保存快照（expectedRevision 匹配时才更新，返回 false 表示冲突）
    boolean save(DurableSnapshot snapshot, long expectedRevision);
}
```

---

# 5. 状态编解码与扩展 (`StateMapper`)

默认提供基于基础类型与 Java Serializable 的 `DefaultStateMapper`。生产环境推荐集成 JSON 编解码：

```java
public class JacksonStateMapper implements StateMapper {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public StoredValue encode(Object value) throws Exception {
        if (value == null) return new StoredValue("null", new byte[0]);
        byte[] bytes = mapper.writeValueAsBytes(value);
        return new StoredValue(value.getClass().getName(), bytes);
    }

    @Override
    public Object decode(StoredValue storedValue) throws Exception {
        if ("null".equals(storedValue.typeId())) return null;
        Class<?> clazz = Class.forName(storedValue.typeId());
        return mapper.readValue(storedValue.data(), clazz);
    }
}
```
