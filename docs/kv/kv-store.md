# 核心抽象

`KvStore` 是组件的唯一核心接口，刻意保持最小；其余能力通过可选能力接口声明，由存储按实际能力实现。

## 四个原子操作

```java
public interface KvStore {

    /** 读取记录；键不存在或已过期返回 null */
    KvRecord get(SpaceKey key);

    /** 写入；mode 为 SET 或 IF_ABSENT，IF_ABSENT 失败返回 false */
    boolean put(SpaceKey key, KvRecord record, PutMode mode);

    /** 删除；返回是否删除了存活记录 */
    boolean remove(SpaceKey key);

    /** 为存活记录设置新有效期（值不变）；ttlMillis <= 0 表示改为永不过期 */
    boolean expire(SpaceKey key, long ttlMillis);
}
```

为什么是这四个？它们恰好是锁（tryLock=`put(IF_ABSENT)`、心跳=`expire`、unlock=`remove`）、幂等控制（SETNX）和 TTL 缓存的最小完备集，且每个操作都能无损映射到 Redis 原生命令与单条 SQL——没有一个是外部存储做不动的。

`expire` 独立成一级操作（而非折叠进 `put`）是因为锁心跳需要**原子改 TTL 而不读值**：折叠进 put 会迫使调用方读-改-写，引入竞争。

## 实现契约

所有实现必须遵守（完整版见 `KvStore` 接口 Javadoc）：

| 契约 | 说明 |
| :--- | :--- |
| **异常** | 基础设施故障（连接失败、序列化失败）抛 `KvStoreException`（非受检）；「键不存在或已过期」不是异常，以 `null` / `false` 表达。调用方可据此区分「无数据」与「存储不可用」 |
| **过期精度** | `get` 返回的 `expireAt` 必须精确到 epoch 毫秒，`0` 仅表示永不过期。这是跨实现一致性契约——分层存储依赖它做过期兜底；Redis 实现以 `GET + PTTL` 换算，不得图省事返回 0 |
| **原子性** | `put(IF_ABSENT)` 必须原子：Redis 用 SETNX，数据库用唯一索引；已过期的同键数据不阻塞写入 |
| **expire 语义** | `ttlMillis <= 0` 表示改为永不过期（对应 Redis PERSIST），与 Redis 原生「负 TTL 即删除」不同，实现者注意映射 |
| **值域** | 值限定 `String`（JSON 等文本负载）；二进制负载规划由后续字节值域能力接口扩展 |

## 键与记录模型

```java
// 键标识：space 与 key 均不允许为空或包含 ':'（Redis 实现以 "space:key" 作为物理键）
SpaceKey key = SpaceKey.of("user.session", "u1");
key.toString();   // "user.session:u1"

// 记录：不可变，expire() 返回新实例
KvRecord record = KvRecord.of("token", 3600_000, System.currentTimeMillis());
record.getValue();          // "token"
record.getExpireAt();       // 过期时间戳，0 为永不过期
record.isExpired(now);      // 是否已过期
record.expire(60_000, now); // 续期后的新记录，原记录不变
```

`SpaceKey` 的 hash 在构造时预计算（每次 L1 缓存查找都会调用 `hashCode()`，避免热路径分配）；`KvRecord.of` 的 ttl 溢出时饱和为极大值，不会回绕成「立即过期」。

## 能力协商

实现按实际能力声明接口，调用方 `instanceof` 探测。做不到原子性的能力**不实现**该接口，而不是实现了但语义错误：

| 能力接口 | 方法 | 典型实现 | 用途 |
| :--- | :--- | :--- | :--- |
| `CasCapable` | `compareAndSet(key, expectedValue, update)`<br/>`compareAndRemove(key, expectedValue)` | memory（compute）、jdbc（条件 UPDATE）、redis（Lua） | 锁的 fencing 安全续期/释放 |
| `CounterCapable` | `incrementAndGet(key, delta)` | memory（`AtomicLong`）、jdbc（`SELECT FOR UPDATE` 行锁）、redis（`INCRBY`） | 序号生成（team4u-id）、计数器 |
| `ScanCapable` | `scan(space)`<br/>`pruneExpired(space, maxBatch)` | memory、jdbc、redis（SCAN） | 轮询订阅、过期清理 |
| `WatchCapable` | `watch(space, listener)` | memory（写入路径同步分发） | 变更订阅 |
| `NativeTtlCapable` | 标记接口 | redis | 清理器跳过该存储 |

CAS 匹配语义：按**存活记录值的精确字符串相等**判定。锁场景中值是持有者令牌，因此「值匹配 = 是我的锁」，这是 fencing 正确性的根基。

```java
// 例子：所有权安全的续期（KvLockManager 内部即此模式）
if (store instanceof CasCapable) {
    CasCapable cas = (CasCapable) store;
    cas.compareAndSet(key, "my-token", KvRecord.of("my-token", 30_000, now)); // 仅自己的锁被续期
    cas.compareAndRemove(key, "my-token");                                    // 仅自己的锁被删除
}
```

装饰器实现 `StoreWrapper` 暴露内层，`KvStores` 提供沿链解析（`innermost` 剥出最内层真实存储、`capabilityOf` 查找链上首个能力实现）——对装饰过的存储做 `instanceof` 探测前先经它解析；另有 `closeQuietly` 静默关闭存储（异常记 warn 不抛出），装饰器的级联关闭统一走此入口。

## 内存实现：InMemoryKvStore

基于 `ConcurrentHashMap`，声明 CAS / 计数 / 扫描 / 订阅全部能力（无原生 TTL，靠惰性判定），时间源可注入：

```java
// 默认系统时钟
KvStore kv = new InMemoryKvStore();

// 测试注入虚拟时钟
KvStore kv = new InMemoryKvStore(new SettableClock(0L));
```

行为细节：

- `put(IF_ABSENT)` 基于 `compute` 保证原子，同键已过期数据视为不存在；
- 过期采用读取时惰性判定：`get` 读到过期条目顺手删除并返回 `null`；`size()` 统计前先清理（与 base 的 `TimedCache.size()` 行为对齐）；
- 写多读少的冷键由 `pruneExpired(space, maxBatch)` 主动回收（清理器挂载点）；
- `watch` 为同步分发：监听器异常被隔离（记日志），不影响存储操作与其他监听器；惰性过期同样产生 `REMOVE` 事件；
- 实现 `AutoCloseable`，`close()` 清空全部数据。

互斥范围：仅当前进程。跨实例互斥使用 JDBC / Redis 等共享存储。
