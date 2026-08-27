# 装饰器

装饰器解决的问题是：**分层缓存、审计日志、故障重试、在线换存储**这些能力和「用什么存储」没有关系。如果把它们写进每个存储实现，memory/jdbc/redis 要各写一遍；装饰器把它们抽成一层「包装纸」——包在任何 `KvStore` 外面，API 不变，业务代码无感知。

## 装饰器是什么

一个装饰器就是「实现了 `KvStore` 接口、内部持有另一个 `KvStore`、在转发操作前后做自己的事」的类。可以像洋葱一样层层包裹：

```text
业务代码
  └─ ObservedStore        ← 记录每次操作的日志与耗时（它看到的是整棵洋葱的耗时）
       └─ TieredStore     ← 先查本地缓存，未命中才往下走
            └─ RetryableStore  ← 失败了自动重试
                 └─ RedisKvStore   ← 真正干活的地方
```

业务代码拿到的仍然是一个普通 `KvStore`，`put`/`get` 照常调用——区别只在行为上：读得快了、有日志了、抖动不怕了。

```java
// 组装一次，处处使用
KvStore kv = new ObservedStore(
        new TieredStore(
                new RetryableStore(redisStore),
                30_000, new TieredStore.Config()));
```

三个装饰器（[TieredStore](kv-tiered.md) 有独立篇章）可以任意组合、任意次序，但次序有讲究，见文末[组合规约](#组合规约)。

## ObservedStore：审计与脱敏

「这个键被谁读了多少次？值是不是太大？为什么这次读这么慢？」——ObservedStore 让每次操作留下结构化日志。

### 基本用法

```java
KvStore observed = new ObservedStore(delegate);   // 最简：默认配置 + 不脱敏
```

### 完整配置

```java
KvStore observed = new ObservedStore(
        delegate,
        new ObservedStore.Config()
                .setMaxValueLogLength(100)        // 日志里值摘要的最大长度，超出截断
                .setSlowOpThresholdMillis(200),   // 超过该耗时升级为 warn
        (key, value) -> FastMasker.mask(value, MaskType.MOBILE));  // 值脱敏器
```

| 配置 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `maxValueLogLength` | 200 | 日志中值摘要的最大长度。值本身不受影响，只截断**日志里展示的部分**（借鉴 log 组件的 FinOps 成本保护，防止大值刷爆日志） |
| `slowOpThresholdMillis` | 100 | 慢操作阈值，达到即以 warn 级别输出 `KV_SLOW` |
| `ValueMasker` | 不脱敏 | 日志输出前的值视图变换函数，只影响日志，**不影响存储与返回值** |

### 日志长什么样

每条日志都是 `|` 分隔的结构化字段，便于日志平台检索聚合：

```text
# 常规操作（debug 级）：操作|键|是否命中|值长度|值摘要|耗时毫秒
KV|get|user:phone|true|138****8000|costMs=3
KV|put:if_absent|idem:o-1001|true|9|****|costMs=8

# 慢操作（warn 级）：同一格式，前缀 KV_SLOW
KV_SLOW|get|user:big|true|12000|{"header":...(12012)|costMs=235

# 失败（error 级）：操作|键|耗时|错误信息，附完整堆栈
KV_FAIL|get|user:u1|costMs=2|connection refused
```

生产环境按需调整 `com.team4u.framework.kv.observed` 的日志级别即可开关审计：`debug` 看全量操作，`warn` 只收慢操作与失败，`error` 只收失败。日志未启用时脱敏与截断计算自动跳过（热路径零浪费）。

### 值脱敏

`ValueMasker` 是函数接口 `(SpaceKey key, String value) -> String`，典型用法是把手机号、身份证等敏感值打码后再进日志。它**只作用于日志展示**——存储里和 `get` 返回的仍是原值：

```java
// 与脱敏组件桥接（本模块不强制依赖 mask 组件，保持轻量）
ObservedStore.ValueMasker mobileMasker =
        (key, value) -> key.getSpace().startsWith("user.")
                ? FastMasker.mask(value, MaskType.MOBILE)
                : value;
```

### 看清下层耗时的小技巧

把 ObservedStore 放在**最外层**，它记录的 `costMs` 就是整棵洋葱（缓存+重试+存储）的耗时；再在内层包一个 ObservedStore，两个耗时相减即可定位缓存层自身开销——白盒排查无需改代码。

## RetryableStore：存储抖动治理

网络闪断、连接池瞬时耗尽——这类**瞬时故障**重试一两次就恢复，不值得让业务请求失败。RetryableStore 把重试包在存储操作上，复用 `team4u-retry` 的 INLINE 模式。

### 基本用法

```java
// 默认策略：最多重试 2 次，指数退避抖动（100ms 起、2 倍、上限 5s），仅重试 KvStoreException
KvStore retryable = new RetryableStore(delegate);
```

### 自定义策略

策略完全开放，退避曲线、重试上限、异常范围都可调（详见[重试组件](../retry/retry-strategy.md)）：

```java
KvStore retryable = new RetryableStore(delegate, RetryPolicy.builder()
        .maxRetries(3)                                        // 最大重试次数（不含首次）
        .backoff(Backoffs.exponentialJitter(100, 2.0, 5000))  // 指数退避+抖动，多实例首选
        .retryOn(KvStoreException.class)                      // 仅基础设施异常触发重试
        .build());
```

### 什么会重试、什么不会

| 情况 | 是否重试 | 原因 |
| :--- | :--- | :--- |
| 连接失败、超时（`KvStoreException`） | ✅ | 瞬时故障，重试通常可恢复 |
| `put(IF_ABSENT)` 返回 `false` | ❌ | 键已存在是**业务语义**，不是故障 |
| 值超过存储限制（如 JDBC 列长） | ❌ | 确定性失败，重试只会浪费——注意这类异常若以 `KvStoreException` 抛出仍会被重试，应依赖退避上限兜底 |

> 各存储实现把基础设施故障统一包装为 `KvStoreException`（异常契约），默认策略因此开箱即用。反过来，不包装异常的存储与 RetryableStore 组合会静默失效——异常不在重试白名单内，重试永远不会触发。

### 放在哪一层

放在**最内层、紧贴真实存储**：缓存命中的读不需要重试（没有触达存储），写直通时由内层重试保护 L2。放在 TieredStore 外面则连 L1 命中也会走重试逻辑，白付一层开销。

## HotSwapStore：在线换后端

存储迁移、故障转移的共同诉求：**换底层存储，业务代码手里的引用不能变**。HotSwapStore 基于代理组件的热交换能力（volatile 替换，对所有线程立即可见）实现。

### 基本用法

```java
// 1. 包装初始存储——返回的对象业务侧当普通 KvStore 用
KvStore kv = HotSwapStore.wrap(jdbcStore);

// 2. 任意时刻原子切换（三条重载，按关闭策略选择）
HotSwapStore.swapAndCloseQuietly(kv, redisStore);        // 立即静默关闭旧存储
HotSwapStore.swap(kv, redisStore, 30_000);               // 宽限 30 秒后关闭（等在途调用收尾）
KvStore old = HotSwapStore.swap(kv, redisStore, false);  // 不关闭，调用方自行管理
```

三条重载都返回被换下的旧存储；前两条返回的旧存储**已被（或将被）关闭**，不要再使用。

### Safe Swap：先建好，再换

换存储的正确姿势是三步，失败不影响线上：

```java
// ① 先构建并验证新存储（连通性探测、预热都通过）
KvStore newStore = new RedisKvStore(newTemplate);
if (!healthCheck(newStore)) {
    return;   // 新存储未就绪，不换——旧存储继续服务，验证失败零影响
}

// ② 原子交换（宽限期给在途调用留收尾时间）
HotSwapStore.swap(kv, newStore, 30_000);

// ③ 若换的是 TieredStore 的 L2，必须清掉本代本地缓存（含墓碑）
//    tiered.evictAll();   // 否则旧代墓碑会屏蔽新存储的数据（见 kv-tiered.md）
```

### 宽限期为什么存在

交换瞬间，正在执行的调用仍持有**旧存储**的引用——立刻关闭它，这些调用可能带着连接一起失败。宽限期重载让旧存储延后关闭（由单线程守护调度器 `kv-hotswap-closer` 执行），给在途调用留出收尾窗口。内存实现关不关无所谓；连接池型后端（JDBC/Redis）建议始终带宽限。

### 与其他装饰器的位置关系

HotSwapStore 通常放**最外层**或直接包住「整棵洋葱」——换掉的是整棵实现：

```java
KvStore kv = HotSwapStore.wrap(
        new ObservedStore(new TieredStore(jdbcStore, 30_000, new TieredStore.Config())));
// 迁移时一次性换成以 Redis 为核心的整棵新洋葱
HotSwapStore.swap(kv, new ObservedStore(new TieredStore(redisStore, 30_000,
        new TieredStore.Config())), 30_000);
```

## 组合规约

### 次序建议

| 次序 | 原因 |
| :--- | :--- |
| ObservedStore 最外层 | 记录整棵洋葱的真实耗时；脱敏覆盖所有路径 |
| TieredStore 中间层 | 缓存命中时跳过下层一切（包括重试与真实存储） |
| RetryableStore 最内层 | 只保护真实存储访问；缓存命中的读零重试开销 |
| HotSwapStore 包整棵洋葱 | 换代换的是完整实现，粒度最大 |

### 能力自动透传

装饰器只实现 `KvStore` 接口，对装饰过的存储直接做 `instanceof CasCapable` 探测的是装饰器对象本身，永远为 false。因此装饰器（TieredStore/ObservedStore/RetryableStore）统一实现 `StoreWrapper` 暴露内层，`KvStores` 沿链解析出真正实现能力接口的存储：

```java
// 以下都能直接工作——锁管理器、清理器、轮询订阅在构造期沿装饰链解析
KvLockManager m = new KvLockManager(new ObservedStore(new TieredStore(redisStore, ...)));
KvCleaner cleaner = new KvCleaner(60_000, 500).addStore(tieredStore).addSpace("task");
PollingWatcher watcher = new PollingWatcher(tieredStore, 200);
```

要点：

- 锁操作**直达解析后的底层存储**（不经过缓存/观测装饰层）——缓存层插在续约读与存储之间会让续约读到陈旧令牌，破坏续约正确性；
- 轮询订阅的 scan/get 同样直达底层，保证读取新鲜度（不被 L1 缓存延迟到缓存 TTL 之后）；
- 通用业务代码可用 `KvStores.capabilityOf(kv, CasCapable.class)` 自行解析（返回 null 表示整条链均不支持），`KvStores.innermost(kv)` 剥出最内层真实存储；
- 边界：经 `HotSwapStore.wrap` 包装的存储会随初始委托透传 `unwrap()`，但**交换到未实现 StoreWrapper 的存储后**，`unwrap()` 调用会以 `ProxyException` 失败——需要长期能力解析的场景应始终交换装饰过的存储。

### 关闭语义

所有装饰器（TieredStore/ObservedStore/RetryableStore）与各存储实现均实现 `AutoCloseable`，**关闭最外层即级联释放整棵洋葱**——TieredStore 先清空 L1 再关 L2，ObservedStore/RetryableStore 直接关内层，底层连接/资源沿链直达释放，无需为关闭内层单独持有引用。

- 关闭均为**尽力而为**：异常记 warn 不抛出（统一走 `KvStores.closeQuietly`），重复调用安全；
- HotSwapStore 代理的 `close()` 关闭**当前**存储（鸭子类型转发；初始委托实现 `AutoCloseable` 时代理才暴露该接口），换下的旧洋葱由 Safe Swap 的交换重载自动关闭；
- 所有权约定：内层存储被多方共享时**不要关闭外层装饰器**——谁创建整棵洋葱谁负责关闭。

被 HotSwapStore 换下的旧洋葱若含 TieredStore，Safe Swap 的自动关闭会连带释放 L2 连接，迁移时无需手工清理。

### 常见组合速查

| 场景 | 组合（外→内） |
| :--- | :--- |
| 读多写少 + 可观测 | Observed → Tiered → 存储 |
| 存储抖动大 | Observed → Tiered → Retryable → 存储 |
| 在线迁移/故障转移 | HotSwap(Observed → Tiered → 存储) |
| 敏感值审计 | Observed(脱敏 ValueMasker) → Tiered → 存储 |
| 无缓存直连 + 熔抖动 | Observed → Retryable → 存储 |
