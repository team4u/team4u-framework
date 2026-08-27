# 契约测试

「内存实现与生产实现行为一致」不能靠文档承诺。`team4u-kv-test` 提供行为契约测试基类，任何新存储实现继承后即可跑同一套契约（对齐 `team4u-lease-test` 的惯例）。

## 契约内容

`AbstractKvStoreContractTest` 固化了 13 项行为契约：

| 类别 | 契约 |
| :--- | :--- |
| 基础读写 | put/get 往返；键空间隔离；remove 返回存活删除语义 |
| 过期语义 | 过期数据不可见；`get` 返回的 `expireAt` 精确（±50ms）；过期数据不阻塞 `IF_ABSENT`；`expire` 续期保值 |
| 原子性 | **并发 `IF_ABSENT` 恰好一个胜者**（8 线程竞争） |
| CAS | 值匹配替换/删除；值不匹配失败；键过期后 CAS 失败 |
| 扫描 | `scan` 过滤键空间与过期；`pruneExpired` 批量清理 |
| 订阅 | `watch` 收到 PUT/REMOVE 事件 |

能力类契约按 `instanceof` 自动跳过——内存实现全量执行，只有 `ScanCapable` 的后端才跑扫描契约。

## 为新存储接入

```java
public class MongoKvStoreContractTest extends AbstractKvStoreContractTest {

    private final SettableClock clock = new SettableClock(0L);

    @Override
    protected KvStore createStore() {
        return new MongoKvStore(mongoClient, clock);   // 注入虚拟时钟
    }

    @Override
    protected long nowMillis() {
        return clock.millis();
    }

    @Override
    protected void advanceMillis(long millis) {
        clock.advance(millis);    // 无法虚拟时间的实现可覆写为真实 sleep
    }
}
```

时间控制是契约测试的关键：TTL 语义验证依赖**精确推进时间**而非等待真实时间。无法注入时钟的存储可覆写 `advanceMillis` 为 sleep（测试变慢但语义不变）。

## TestKvContext：业务单测上下文

业务代码依赖 `KvStore` 时，用 `TestKvContext` 替代真实存储：

```java
TestKvContext kv = TestKvContext.create();

kv.store();               // 内存存储（虚拟时钟驱动）
kv.clock();               // 虚拟时钟，可传给锁管理器等组件
kv.advanceSeconds(60);    // 推进时间，验证 TTL/租约语义

// 结合 Spaces 快速搭建类型化环境
Spaces.global().register(new SpacePolicy()
        .setName("user.session").setValueType(Session.class).setDefaultTtlMillis(60_000));
Space<Session> sessions = Spaces.global().use("user.session", kv.store());
```

`TestKvContext.SettableClock` 同时是下游模块（lock/lifecycle/jdbc/redis）测试的共享时钟来源。

## CI 中的既有覆盖

| 实现 | 契约执行位置 |
| :--- | :--- |
| `InMemoryKvStore` | `team4u-kv-test` 模块（行为基准） |
| `JdbcKvStore` | `team4u-kv-store-jdbc`（H2 MySQL 模式，含建表 DDL 验证） |
| `RedisKvStore` | 单元测试基于 Mockito 验证命令映射；契约测试需真实 Redis 环境（未来接入） |
