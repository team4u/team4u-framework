# 装饰器

三个横切装饰器与 [TieredStore](kv-tiered.md) 一样，都直接实现 `KvStore`，可任意嵌套组合：

```java
KvStore kv = new ObservedStore(                    // 最外层：观测所有操作（含下层耗时）
        new TieredStore(                           // 中间层：本地缓存
                new RetryableStore(redisStore),    // 最内层：重试包裹真实存储
                30_000, new TieredStore.Config()));
```

## ObservedStore：审计与脱敏

为每次操作输出结构化审计日志（操作、键、值长度、脱敏值摘要、耗时、结果），超阈值升级为 warn，失败记 error：

```java
KvStore observed = new ObservedStore(
        delegate,
        new ObservedStore.Config()
                .setMaxValueLogLength(100)      // 日志中值摘要最大长度（FinOps 截断）
                .setSlowOpThresholdMillis(200), // 慢操作阈值
        (key, value) -> "****");                // 值脱敏器（ValueMasker 函数接口）
```

日志级别约定：常规操作 debug、慢操作 warn、失败 error——生产环境按需调整 `com.team4u.framework.kv.observed` 的日志级别即可开关审计。`ValueMasker` 是函数接口，可与脱敏组件（`FastMasker`）以适配器桥接，本模块保持零强依赖。

## RetryableStore：存储抖动治理

复用 `team4u-retry` 的 INLINE 模式，重试策略完全开放：

```java
KvStore retryable = new RetryableStore(delegate, RetryPolicy.builder()
        .maxRetries(3)
        .backoff(Backoffs.exponentialJitter(100, 2.0, 5000))
        .retryOn(KvStoreException.class)   // 仅基础设施异常重试
        .build());

// 或使用默认策略：重试 2 次、指数退避、仅 KvStoreException
KvStore retryable = new RetryableStore(delegate);
```

默认策略仅在 `KvStoreException` 上重试——`put(IF_ABSENT)` 返回 false 是业务语义（键已存在），不属于基础设施故障，不会触发重试。

## HotSwapStore：在线换后端

基于 proxy 组件的热交换能力（volatile delegate 替换，所有线程立即可见），业务持有的引用不变：

```java
KvStore kv = HotSwapStore.wrap(jdbcStore);   // 包装初始存储

// 运行期切换（如存储迁移、故障转移）
HotSwapStore.swapAndCloseQuietly(kv, redisStore);            // 立即关闭旧存储
HotSwapStore.swap(kv, redisStore, 30_000);                   // 30 秒宽限期后关闭（在途调用收尾）
KvStore old = HotSwapStore.swap(kv, redisStore, false);      // 不关闭，由调用方管理
```

Safe Swap 约定与边界：

- 调用方应**先构建并验证新存储可用，再执行交换**（新实例建成功才换，失败保留旧实例）；
- 交换瞬间在途调用仍持有旧存储引用，立即关闭可能中断它们——连接池型后端用宽限期重载；
- `swapAndCloseQuietly` 返回的旧存储**已被关闭**，不要再使用；
- 对非热交换代理调用 `swap` 快速失败（`IllegalArgumentException`）；
- 换掉 TieredStore 的 L2 后记得 `evictAll()`（见[分层存储](kv-tiered.md)）。

## 组合建议

| 场景 | 推荐组合（外→内） |
| :--- | :--- |
| 读多写少 + 可审计 | Observed → Tiered → 存储 |
| 存储抖动大 | Observed → Tiered → Retryable → 存储 |
| 在线迁移/故障转移 | HotSwap(Observed → Tiered → 存储) |
| 敏感数据（手机号等） | Observed(脱敏 ValueMasker) → Tiered → 存储 |

装饰器对能力接口是**透明的吗**——不是：装饰器仅保证 `KvStore` 四操作，`instanceof CasCapable` 探测会落在装饰器上而失败。需要能力协商时（如锁），把锁/订阅指向内层真实存储，或由装饰器自行透传能力（当前版本不透传，保持最小）。
