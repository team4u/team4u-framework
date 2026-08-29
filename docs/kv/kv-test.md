# 契约测试

## 为什么需要契约测试

组件承诺「换存储不改业务代码」，前提是所有实现行为一致。但「一致」不能靠文档承诺——`IF_ABSENT` 是否真原子、`get` 返回的过期时间戳是否精确，这些只有跑起来才知道。

契约测试的思路：把**每个实现都必须满足的行为**固化成一套公共测试（即「契约」）；任何存储实现（现有的或你新写的）继承基类、提供自己的存储实例，就自动跑同一套用例。实现行为跑偏，CI 当场红——而不是在生产环境被用户撞见。

`team4u-kv-test` 提供的正是这套基类（对齐 `team4u-lease-test` 的惯例）。

## 契约内容

`AbstractKvStoreContractTest` 固化了 15 项行为契约：

| 类别 | 契约 |
| :--- | :--- |
| 基础读写 | put/get 往返；键空间隔离；remove 返回存活删除语义 |
| 过期语义 | 过期数据不可见；`get` 返回的 `expireAt` 精确（±50ms）；过期数据不阻塞 `IF_ABSENT`；`expire` 续期保值 |
| 原子性 | **并发 `IF_ABSENT` 恰好一个胜者**（8 线程竞争） |
| CAS | 值匹配替换/删除；值不匹配失败；键过期后 CAS 失败 |
| 计数 | 键不存在从 0 开始；返回递增后精确值；计数与值域互不干扰；**并发递增不丢失**（8 线程 × 100 次） |
| 扫描 | `scan` 过滤键空间与过期；`pruneExpired` 批量清理 |
| 订阅 | `watch` 收到 PUT/REMOVE 事件 |

能力类契约按 `instanceof` 自动跳过——内存实现全量执行，只有 `ScanCapable` 的后端才跑扫描契约。

## 契约层次

`team4u-kv-test` 的基类是分层继承的，新存储按需继承到最深层级（不实现的能力自动跳过）：

```text
AbstractKvStoreContractTest               # 基础 15 项契约（四操作/过期/原子性/CAS/计数/扫描/订阅）
└── AbstractCounterTtlContractTest        # + 计数 TTL 契约 3 项（instanceof CounterCapable 才执行）
    └── AbstractScoredWindowCapableContractTest   # + 计分窗口契约 7 项（instanceof ScoredWindowCapable 才执行）
```

| 基类 | 补充契约 |
| :--- | :--- |
| `AbstractCounterTtlContractTest` | `incrementAndGet(key, delta, ttlMillis)` 的 TTL 语义：过期后从 0 重新计数；后续递增不刷新 TTL；`ttlMillis <= 0` 永不过期 |
| `AbstractScoredWindowCapableContractTest` | `offer` 的窗口语义：score 等于 cutoff 被裁剪；maxCount 内条件添加；超限**整体拒绝且不添加**；members 为空的窥探永不拒绝；键 TTL 到期整键消失重来；每次成功操作刷新 TTL；oldestScore 为最老成员 |

计数与窗口是有 TTL/裁剪语义的能力，仅靠基础契约覆盖不到，因此独立成层——计数型后端（如 JDBC）继承 `AbstractCounterTtlContractTest`，窗口型后端（如 Redis）继承最底层的 `AbstractScoredWindowCapableContractTest` 一次拿全三套契约。

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
