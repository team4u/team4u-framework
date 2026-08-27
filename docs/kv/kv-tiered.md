# 分层存储

`TieredStore` 是 L1 本地缓存 + L2 远程存储的装饰器：L1 复用 base 的 `Cache` 抽象（默认 `TimedCache`，也可传 LRU/LFU 等任意实现），L2 是任意 `KvStore`。API 与核心接口完全一致，业务无感知。

```java
// 推荐构造：TimedCache L1 + 墓碑
KvStore tiered = new TieredStore(
        jdbcStore,                                 // L2 权威存储
        60_000,                                    // L1 条目有效期（毫秒）
        new TieredStore.Config().setTombstoneTtlMillis(5_000));

// 自定义 L1（容量淘汰）+ 注入时钟
KvStore tiered = new TieredStore(
        jdbcStore,
        CacheUtil.newLRUCache(10_000),
        new TieredStore.Config(),
        Clock.systemUTC());
```

## 三条路径

| 路径 | 行为 |
| :--- | :--- |
| **读** | L1 命中直接返回（零远程开销）；未命中穿透 L2，命中后回填 L1 |
| **写** | 写直通（write-through）：先写 L2，成功后同步更新 L1；`IF_ABSENT` 失败不触碰 L1 |
| **删** | 删除 L2 后在 L1 写入带有效期的**墓碑**，窗口内读取直接判空，不再访问 L2 |

正确性兜底：即使 L1 缓存实现尚未淘汰条目，读取也按记录自身的 `expireAt` 判定，**绝不返回已过期数据**（契约测试 `NeverEvictCache` 桩专门验证：永不清理的 L1 下过期数据依然不可见）。

## 墓碑与负缓存

`Config` 两个开关均默认 0（关闭）：

| 配置 | 语义 | 代价 |
| :--- | :--- | :--- |
| `tombstoneTtlMillis` | 删除后在 L1 写墓碑：窗口内同键读取不访问 L2，阻止 L2 副本延迟期间同键旧值「死灰复燃」；窗口结束自动回源 L2 | 窗口内 L2 的新写入本实例不可见 |
| `negativeTtlMillis` | L2 未命中时缓存「不存在」：窗口内同键读取不再穿透 L2（防击穿） | 窗口内外部直写 L2 的数据本实例不可见 |

覆盖写（`put` SET）会同时清除墓碑；`expire` 成功后失效 L1 待下次读取回填，键不存在时保留既有 L1 状态。

## 并发契约（重要）

- 对 L2 的单次操作由底层存储保证原子性；**跨层组合为尽力而为**：并发 get 回填与并发 remove 的墓碑可能极端交错（回填前会检查墓碑缩小窗口，但不消除）。因此**强烈建议 `l1TtlMillis > 0`**，使任何陈旧数据的驻留时间有上界；
- `l1TtlMillis <= 0` 且记录永不过期时，L1 无时间淘汰也无容量上限，存在无界增长风险，仅应在键集有限且无删除语义时使用；
- L1 操作失败（自定义 Cache 实现抛出等）降级为**失效该键**（失效优于留旧值），不阻断 L2 已成功的写入；
- L2 异常原样穿透（fail-closed），调用方可区分「键不存在」与「存储不可用」；
- 多实例间**无 L1 失效广播**，跨实例一致性窗口 = L1 TTL。需要更强一致性时缩短 L1 TTL 或直接绕过分层。

## 生命周期

```java
tiered.evictAll();   // 清空 L1（不影响 L2 数据）
tiered.close();      // 清空 L1 + 静默关闭 L2（若 AutoCloseable）
```

两个必须记住的组合规约：

1. 通过 `HotSwapStore` 更换 L2 底层存储后，**必须调用 `evictAll()`**——旧代墓碑会屏蔽新存储的真实数据（最多一个墓碑窗口）；
2. `close()` 会级联关闭 L2：把 TieredStore 包进 HotSwapStore 再 swap 出来时，旧实例由 Safe Swap 自动 `close()`，L2 不会泄漏。

## 效果示例

```java
CountingL2 l2 = ...;                       // 统计 L2 读取次数
TieredStore tiered = new TieredStore(l2, 60_000, new TieredStore.Config());

l2.put(key, KvRecord.of("v1"), PutMode.SET);
tiered.get(key);   // 未命中 → 穿透 L2 → 回填
tiered.get(key);   // 命中 L1，L2 读取次数不变
```

典型的读多写少场景下，L2 读压力下降一个数量级以上；配合 `negativeTtlMillis` 可同时防「不存在的键」被恶意或失误打穿。
