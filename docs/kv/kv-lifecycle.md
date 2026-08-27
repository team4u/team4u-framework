# 值生命周期

`team4u-kv-lifecycle` 管理三类「随时间变化」的关注点：过期值续期（ExpiringValue）、变更订阅（PollingWatcher）、过期清理（KvCleaner）。

## ExpiringValue：过期值源

解决**有有效期的外部凭证**续期问题（典型如第三方 `access_token`）。业务只声明「值怎么取、有效期怎么算」，取值统一走 `get()`：

| `get()` 时的状态 | 行为 |
| :--- | :--- |
| 未过期且未进刷新窗口 | 直接返回缓存值，零加载开销 |
| 进入刷新窗口（剩余时间 <= `refreshAhead`） | 本线程同步续期；并发请求经 singleflight 等待，不重复加载。**续期失败不影响返回旧值**（记 warn，下次 `get()` 自动重试） |
| 不存在 / 已过期 | 加载新值并写入；singleflight 保证并发下仅加载一次 |

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

**与旧体系刷新器的区别**：旧设计需要「定义 + 抽象刷新器 + 外部定时任务兜底触发」三件套；这里 `get()` 内聚了检测-加载-保存全流程，**业务侧不再需要定时任务**。刷新失败不影响返回旧值（旧值在过期前仍可用），下次 `get()` 自动重试。

singleflight 两种作用域：

| Scope | 机制 | 适用 |
| :--- | :--- | :--- |
| `LOCAL`（默认） | 进程内 per-key 互斥（双重检查） | 单实例，或允许各实例各自加载一次 |
| `CLUSTER` | 基于 `KvLockManager` 的 KV 锁跨实例互斥 | 多实例共享一份凭证，全局仅一个加载者（防止重复消耗第三方配额） |

CLUSTER 作用域的刷新锁正常路径随 try-with-resources 释放；进程崩溃后靠锁租约 TTL 自愈——没有旧体系「抢到锁故意不释放」的 hack。

## PollingWatcher：轮询订阅

为不支持 `WatchCapable` 的存储（JDBC、Redis）提供订阅降级：基于 `ScanCapable` 周期对比快照差异，产生 PUT / REMOVE 事件：

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

边界：轮询周期即事件延迟上界；两次轮询之间同键多次变更合并为一次事件（只见最终状态）；扫描成本与键量成正比，适合键量可控的键空间（任务结果、配置型数据）。内存实现请直接用原生 `WatchCapable`（写入即分发，无延迟）。

## KvCleaner：过期清理

惰性过期使「读取永不返回脏数据」，清理只是**回收存储空间**（写多读少的冷键）。实现 `NativeTtlCapable` 的存储（Redis）自动跳过：

```java
KvCleaner cleaner = new KvCleaner("shared",   // 清理锁名前缀（可选锁互斥时区分业务域）
        60_000,        // 清理间隔
        500,           // 单键空间单次最大删除量（防长事务）
        lockManager)   // 可选：多实例共享存储时全局互斥（锁租约=2×间隔，宕机自动失效）
        .addStore(jdbcStore)   // 须为 ScanCapable
        .addStore(memoryStore)
        .addSpace("task")      // 显式注册待清理键空间（必填）
        .addSpace("auth");

cleaner.runOnceQuietly();   // 也可挂外部调度平台手工触发
...
cleaner.close();
```

要点：

- 键空间**显式注册**（`addSpace`）——清理是后台写操作，作用域必须显式声明，不做隐式全量扫描；
- 单键空间异常只记日志，不影响其他键空间；
- 不传 `lockManager` 时各实例独立清理（幂等操作，可接受）；传入后同一时刻全局仅一个实例执行。

## 组合模式

三件套的典型协作——「凭证续期 + 变更通知 + 空间回收」可以同存储共存：

```java
// auth 空间：ExpiringValue 写入 token，KvCleaner 回收过期残留，PollingWatcher 通知消费方
ExpiringValue<Token> token = ...;
KvCleaner cleaner = new KvCleaner(null, 60_000, 500, lockManager)
        .addStore(store).addSpace("auth");
```
