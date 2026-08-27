# 值生命周期

`team4u-kv-lifecycle` 管理三类「随时间变化」的关注点：过期值续期（ExpiringValue）、变更订阅（PollingWatcher）、过期清理（KvCleaner）。

## ExpiringValue：过期值源

解决**有有效期的外部凭证**续期问题（典型如第三方 `access_token`）。业务只声明「值怎么取、有效期怎么算」，取值统一走 `get()`：

| `get()` 时的状态 | 行为 |
| :--- | :--- |
| 未过期且未进刷新窗口 | 直接返回缓存值，零加载开销 |
| 进入刷新窗口（剩余时间 <= `refreshAhead`） | 默认本线程同步续期；并发请求经 singleflight 等待同一次结果，不重复加载。**续期失败不影响返回旧值**（记 warn 并进入失败冷却，下次 `get()` 自动重试） |
| 冷却期内（上次失败后） | 跳过加载尝试，直接返回旧值——源端故障时不会形成顺序请求风暴 |
| 不存在 / 已过期 | 加载新值并写入；singleflight 保证并发下仅加载一次（硬死期路径不软化，失败抛给调用方） |

```java
ExpiringValue<Token> token = ExpiringValue.<Token>builder(Token.class)
        .store(kvStore)
        .key("auth", "wechat_token")
        .loader(() -> wechatClient.getAccessToken())     // 怎么取新值
        .ttlOf(t -> (t.getExpiresIn() - 300) * 1000L)    // 有效期怎么算（预留余量）
        .refreshAhead(600_000)                           // 提前 10 分钟刷新
        .scope(ExpiringValue.Scope.CLUSTER)              // 跨实例 singleflight
        .lockManager(lockManager)
        .refreshLockMillis(30_000)                       // 刷新锁租约
        .acquireTimeoutMillis(30_000)                    // 刷新锁等待超时
        .clock(clock)                                    // 测试注入
        .build();

Token t = token.get();      // 业务取值入口
Token fresh = token.refresh();   // 强制刷新（忽略窗口判断）
```

构建参数说明（`refreshLockMillis` / `acquireTimeoutMillis` 默认均为 30 秒，仅 CLUSTER 作用域使用；`clock` 仅测试注入）。另有两组可选参数：

```java
// 失败冷却：第 k 次连续失败 → 冷却 min(initial × 2^(k-1), max)，成功即清零
// 默认 1 秒 ~ 60 秒，无需显式配置；源端故障时刷新窗口内的连续 get() 不会反复打第三方
.cooldown(1_000, 60_000)

// 异步提前刷新：刷新窗口内 get() 立即返回旧值，续期提交给指定线程池（须显式传入，不引入隐式线程）
// 不配置则维持默认的同步续期
.refreshAheadAsync(executor)
```

```java
// 最简配置：LOCAL 作用域（默认），三件套只声明「取值 + 有效期」
ExpiringValue<String> apiKey = ExpiringValue.<String>builder(String.class)
        .store(kvStore)
        .key("auth", "api_key")
        .loader(() -> authService.issueKey())   // 怎么取
        .fixedTtl(300_000)                      // 有效期固定 5 分钟（也可以 ttlOf 按值计算）
        .build();
```

`get()` 内聚了检测-加载-保存全流程，**业务侧无需定时任务兜底触发**。刷新失败不影响返回旧值（旧值在过期前仍可用），下次 `get()` 自动重试。

singleflight 两种作用域：

| Scope | 机制 | 适用 |
| :--- | :--- | :--- |
| `LOCAL`（默认） | 进程内 future 式单飞：并发等待者共享**同一次**加载结果或异常，唤醒后不二次重试 | 单实例，或允许各实例各自加载一次 |
| `CLUSTER` | 基于 `KvLockManager` 的 KV 锁跨实例互斥 | 多实例共享一份凭证，全局仅一个加载者（防止重复消耗第三方配额） |

CLUSTER 作用域的刷新锁正常路径随 try-with-resources 释放；进程崩溃后靠锁租约 TTL 自动失效自愈。

> 选型提示：值需要**跨实例共享**、全局仅一个加载者时用 `ExpiringValue`；**单进程内**的远端影子（值住内存、零序列化开销、含后台刷新与变更回调）用 [team4u-base 的 RefreshableValue](../base/base-refresh.md)。

## PollingWatcher：轮询订阅

为不支持 `WatchCapable` 的存储（JDBC、Redis）提供订阅降级：基于 `ScanCapable` 周期对比快照差异，产生 PUT / REMOVE 事件（支持装饰过的存储，构造期自动解析内层 ScanCapable）：

```java
try (PollingWatcher watcher = new PollingWatcher(jdbcStore, 200)) {  // 200ms 轮询
    try (AutoCloseable sub = watcher.watch("task.result", event -> {
        if (event.getType() == KvEvent.Type.PUT) {
            System.out.println("任务完成: " + event.getKey().getKey());
        }
    })) {
        // ... 另一线程/实例写入 task.result 空间的键，最多 200ms 后收到事件
    }
}
```

要点与边界：

- 订阅从**当前时刻**开始生效（以空快照为基准），只推送订阅后的增量——订阅前已存在的键不会补发事件；
- 轮询周期即事件延迟上界（上例 200ms）；
- 两次轮询之间同键多次变更合并为一次事件（只见最终状态）；
- 扫描成本与键量成正比，适合键量可控的键空间（任务结果、配置型数据）；
- 轮询读取直达解析后的底层存储，不被 L1 缓存延迟事件新鲜度；
- 支持装饰过的存储（自动解析内层 ScanCapable；轮询读取直达底层保证新鲜度，不被 L1 缓存延迟）；
- 内存实现请直接用原生 `WatchCapable`（写入即分发，零延迟、零扫描成本）。

## KvCleaner：过期清理

惰性过期使「读取永不返回脏数据」，清理只是**回收存储空间**（写多读少的冷键）。实现 `NativeTtlCapable` 的存储（Redis）自动跳过：

```java
// 完整构造（四参）：用于多实例共享存储时全局互斥
KvCleaner cleaner = new KvCleaner(
        "shared",      // 清理锁名前缀（配合 lockManager 区分业务域）
        60_000,        // 清理间隔
        500,           // 单键空间单次最大删除量（防长事务）
        lockManager)   // 传入后同一时刻全局仅一个实例执行清理
        .addStore(jdbcStore)   // 待清理存储，须实现 ScanCapable
        .addStore(memoryStore)
        .addSpace("task")      // 显式注册待清理键空间（必填）
        .addSpace("auth");

// 简化构造（两参）：单实例部署、或各实例独立清理时使用
KvCleaner local = new KvCleaner(60_000, 500)
        .addStore(memoryStore).addSpace("task");

cleaner.runOnceQuietly();   // 单轮清理；也可不依赖内置线程，挂外部调度平台定期调用
...
cleaner.close();            // 停止后台线程
```

后台线程为守护线程：JVM 退出不阻塞；清理动作幂等，多实例并发清理无害（传锁只是为了省重复功）。

要点：

- 键空间**显式注册**（`addSpace`）——清理是后台写操作，作用域必须显式声明，不做隐式全量扫描；支持装饰过的存储（自动解析内层 ScanCapable，原生 TTL 判定作用于解析结果）；
- 支持装饰过的存储（自动解析内层 ScanCapable，清理直达底层）；
- 单键空间异常只记日志，不影响其他键空间；
- 不传 `lockManager` 时各实例独立清理（幂等操作，可接受）；传入后同一时刻全局仅一个实例执行。

## 组合模式

三件套可以作用在同一个键空间上，各管一段：「ExpiringValue 负责 token 常新、KvCleaner 回收过期残留、PollingWatcher 通知消费方」：

```java
// auth 空间的三位管家
ExpiringValue<Token> token = ExpiringValue.<Token>builder(Token.class)
        .store(store).key("auth", "wechat_token")
        .loader(() -> wechatClient.getAccessToken())
        .ttlOf(t -> t.getExpiresIn() * 1000L)
        .build();                                        // ① 保证 token 常新

KvCleaner cleaner = new KvCleaner("auth", 60_000, 500, lockManager)
        .addStore(store).addSpace("auth");               // ② 回收过期残留行

PollingWatcher watcher = new PollingWatcher(store, 200); // ③ 变更即通知
watcher.watch("auth", event -> notifyConsumers(event.getKey()));
```
