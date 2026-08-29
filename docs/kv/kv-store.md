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

为什么是这四个？它们恰好是锁（tryLock=`put(IF_ABSENT)`、unlock=`remove`）、幂等控制（SETNX）和 TTL 缓存的最小完备集，且每个操作都能无损映射到 Redis 原生命令与单条 SQL——没有一个是外部存储做不动的。（锁的心跳续约用的是能力接口 `CasCapable.compareAndExpire`——它需要连带令牌校验，裸 `expire` 做不到不误续他人的锁。）

`expire` 独立成一级操作（而非折叠进 `put`）是因为续期场景需要**原子改 TTL 而不读值**：折叠进 put 会迫使调用方读-改-写，引入竞争。锁心跳因为还要连带令牌校验，用的是 `CasCapable.compareAndExpire`（见下文能力协商）；裸 `expire` 服务于「值不变、仅改有效期」的普通 TTL 调整（如 `Space.expire` 门面）。

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
| `CasCapable` | `compareAndSet(key, expectedValue, update)`<br/>`compareAndRemove(key, expectedValue)`<br/>`compareAndExpire(key, expectedValue, newExpireAtMillis)` | memory（compute）、jdbc（条件 UPDATE）、redis（Lua） | 锁的 fencing 安全续期/释放、令牌桶状态提交 |
| `CounterCapable` | `incrementAndGet(key, delta, ttlMillis)` | memory（`AtomicLong`）、jdbc（`SELECT FOR UPDATE` 行锁）、redis（`INCRBY` + 首次 `PEXPIRE`） | 序号生成（team4u-id）、固定窗口限流（team4u-ratelimiter） |
| `ScoredWindowCapable` | `offer(key, offer)`：原子「裁剪 → 计数 → 条件添加」 | memory（独立窗口结构）、redis（ZSET + 单 Lua 脚本） | 精确滑动窗口限流（team4u-ratelimiter）；**JdbcKvStore 暂未实现** |
| `ScanCapable` | `scan(space)`<br/>`pruneExpired(space, maxBatch)` | memory、jdbc、redis（SCAN） | 轮询订阅、过期清理 |
| `WatchCapable` | `watch(space, listener)` | memory（写入路径同步分发） | 变更订阅 |
| `NativeTtlCapable` | 标记接口 | redis | 清理器跳过该存储 |

CAS 匹配语义：按**存活记录值的精确字符串相等**判定。锁场景中值是持有者令牌，因此「值匹配 = 是我的锁」，这是 fencing 正确性的根基。

```java
// 例子：所有权安全的续期与释放（KvLockManager 内部即此模式）
if (store instanceof CasCapable) {
    CasCapable cas = (CasCapable) store;
    cas.compareAndExpire(key, "my-token", now + 30_000); // 仅自己的锁被续约：单往返「校验 + 改过期时间」，晚到心跳不缩短租约
    cas.compareAndRemove(key, "my-token");                                    // 仅自己的锁被删除
    cas.compareAndSet(key, "my-token", KvRecord.of("my-token", 30_000, now));  // 仅自己的锁被改写（如令牌轮换）
}
```

`compareAndExpire` 的保序语义：仅当新过期时间**晚于**当前过期时间时才生效（`0` 表示永不过期、视为无穷大），乱序到达的延迟心跳不会回缩租约；返回 `true` 表示持有者校验通过且记录存活（含因保序保护未变更过期时间的情形）。实现方要求「校验 + 更新」一次存储往返原子完成，不得组合 `get` + `compareAndSet` 两段式实现——两段式在窗口期内会用陈旧快照续约。

装饰器实现 `StoreWrapper` 暴露内层，`KvStores` 提供沿链解析（`innermost` 剥出最内层真实存储、`capabilityOf` 查找链上首个能力实现）——对装饰过的存储做 `instanceof` 探测前先经它解析；另有 `closeQuietly` 静默关闭存储（异常记 warn 不抛出），装饰器的级联关闭统一走此入口。

下面展开两个有 TTL/窗口语义的能力契约（其余能力见接口 Javadoc）。

### CounterCapable：带 TTL 的原子计数

> **破坏性变更**：`incrementAndGet` 签名新增 `ttlMillis` 参数（原为 `incrementAndGet(key, delta)`）。`ttlMillis <= 0` 保持旧语义（永不过期），存量调用方传 `0` 即可无缝迁移。

```java
long incrementAndGet(SpaceKey key, long delta, long ttlMillis);
```

TTL 契约要点：

- `ttlMillis > 0` 时计数键在 `ttlMillis` 毫秒后过期，**过期后的首次递增从 0 重新开始**（返回值等于 `delta`），适合「窗口内配额」语义；
- TTL 的设置与过期判定在递增的**同一原子操作**内完成，不出现「重置与累积分离」的中间态；
- TTL 在键创建（或过期重置）时设置，**后续递增不刷新**——窗口长度固定为首个请求起算的 `ttlMillis`（浮动窗口语义，固定窗口限流据此实现）；
- 存量无 TTL 键首次遇到 `ttlMillis > 0` 的递增时补充设置 TTL；
- 周期重置也可以不依赖 TTL：调用方按日期拼接新键（team4u-id 的 DATE 分组即此做法）。

### ScoredWindowCapable：计分窗口

键级有序计分窗口（对应 Redis ZSET），支撑滑动窗口限流等「按 score 裁剪 + 上限准入 + 原子计数」场景。score 通常为时间戳等单调递增量，窗口语义完全由调用方定义，实现只关心大小关系：

```java
Verdict offer(SpaceKey key, Offer offer);

Offer  = { cutoffScore, memberScore, members, maxCount, ttlMillis }
Verdict = { accepted, count, oldestScore }
```

- **原子性**：整个「裁剪 → 计数 → 条件添加」在一次原子操作内完成，并发调用不产生中间态、不丢失成员；
- **裁剪**：score 严格大于 `cutoffScore` 的成员存活，等于 `cutoffScore` 的成员视为过期被裁剪；
- **条件添加**：members 非空且「裁剪后计数 + members 数量」超过 `maxCount` 时**不添加任何成员**并返回 `accepted=false`（全有或全无）；未超限全部添加返回 `true`；
- **窥探**：members 为空表示仅裁剪与计数、不添加成员，永不拒绝；
- **TTL**：`ttlMillis > 0` 时每次成功操作（含窥探）刷新整个键的过期时间，键过期后整键消失、窗口从零重来——TTL 是键卫生手段（清理零流量残留键），与按 score 裁剪是两套独立机制；
- **oldestScore**：裁剪后现存成员中的最小 score，窗口为空时为 `null`。

双实现：`InMemoryKvStore`（独立窗口结构 + 锁内单线程操作）与 `RedisKvStore`（`ZREMRANGEBYSCORE` 裁剪 → `ZCARD` 计数 → 条件 `ZADD` 准入 → `PEXPIRE` 刷新，单 Lua 脚本原子完成）。**`JdbcKvStore` 暂未实现**该接口——做不到整体原子性的实现不应实现它（SQL 上无 ZSET 等价物，多语句原子需重量级锁），需要滑动窗口的场景请使用内存或 Redis 后端。

## 内存实现：InMemoryKvStore

基于 `ConcurrentHashMap`，声明 CAS / 计数 / 计分窗口 / 扫描 / 订阅全部能力（无原生 TTL，靠惰性判定），时间源可注入：

```java
// 默认系统时钟
KvStore kv = new InMemoryKvStore();

// 测试注入虚拟时钟
KvStore kv = new InMemoryKvStore(new SettableClock(0L));
```

行为细节：

- `put(IF_ABSENT)` 基于 `compute` 保证原子，同键已过期数据视为不存在；
- 过期采用读取时惰性判定：`get` 读到过期条目顺手删除并返回 `null`；`size()` 统计前先清理（与 base 的 `TimedCache.size()` 行为对齐）；
- 计数器与计分窗口为独立存储结构（与记录值域互不干扰），同样惰性判定：计数键到期后首次递增先重置为 0 再累加，窗口键到期后整键消失重来；
- 写多读少的冷键由 `pruneExpired(space, maxBatch)` 主动回收（清理器挂载点），同批清扫已过期的记录、计数器与计分窗口键；
- `watch` 为同步分发：监听器异常被隔离（记日志），不影响存储操作与其他监听器；惰性过期同样产生 `REMOVE` 事件；
- 实现 `AutoCloseable`，`close()` 清空全部数据。

互斥范围：仅当前进程。跨实例互斥使用 JDBC / Redis 等共享存储。
