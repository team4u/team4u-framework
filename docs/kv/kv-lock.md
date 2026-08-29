# 锁服务

`team4u-kv-lock` 基于 KV 的 CAS 语义实现分布式锁，提供三项正确性保证：

| 保证 | 实现机制 |
| :--- | :--- |
| **释放不误删** | 释放经 `compareAndRemove`：仅删除自己令牌持有的锁；锁已被他人接管时，释放是安全的空操作 |
| **存活不超时** | 后台心跳续约（间隔自适应），持有方存活期间租约持续滚动，长任务不会跑到一半被他人抢走 |
| **宕机自愈** | 持锁方崩溃后心跳停止，租约到期自动失效（存储惰性判定，无需后台任务回写），其他实例立即可获取 |

底层存储必须实现 `CasCapable`（内存、JDBC、Redis 均支持），否则构造期快速失败。可传入装饰过的存储（如 `ObservedStore(TieredStore(redisStore))`），锁管理器自动沿装饰链解析到底层 CasCapable 存储并直达操作——锁的读写不经过缓存/观测装饰层，续约不会被缓存层喂陈旧令牌。

## 基本用法

```java
KvLockManager manager = new KvLockManager(kvStore);

// 阻塞获取：租约 30 秒，最长等待 5 秒，超时抛 KvLockTimeoutException
try (KvLock lock = manager.acquire("report.daily", 30_000, 5_000)) {
    doGenerate();
}   // try-with-resources 自动释放

// 非阻塞获取：被他人持有返回 null
KvLock lock = manager.tryAcquire("report.daily", 30_000);
if (lock != null) {
    try { doGenerate(); } finally { lock.release(); }
}
```

锁的值是**持有者令牌**（`ownerId:随机UUID`，不可猜测），这就是 CAS 的匹配标的——「值等于我的令牌」即「锁是我的」。`lock.token()` 只读返回本次持有者令牌；如需 fencing，可将该值复制到业务自己的 fencing envelope，但它不会改变锁的持有、续约或释放状态。

## 心跳与续约

- `KvLockManager` 构造时启动守护心跳线程，按 `heartbeatIntervalMillis`（默认 10 秒）对所有持有锁做 `compareAndExpire` 续约——「令牌校验 + 更新过期时间」在**单次存储往返**内原子完成，无「先读后写」窗口期；
- 心跳间隔**自适应收缩**：实际间隔取「配置值」与「最短持有租约的 1/3」中的较小者——短租约锁（如几百毫秒）也会在过期前获得续约窗口；
- 续约仅当锁值仍等于自己的令牌时才更新过期时间，且新过期时间**只会推后、不会提前**（乱序到达的晚到心跳不缩短租约），**绝不续期他人的锁**；
- `manager.close()` 释放全部持有的锁并停止心跳。

```java
// 手动续约（心跳之外的显式触发）
boolean renewed = lock.renew();   // false = 锁已丢失（被接管），应立即停止临界区工作

// 查询是否仍被自己持有（不触发续约）
boolean held = lock.isHeld();
```

## fencing 语义

「锁被接管后，旧持有者的操作不得伤害新持有者」由 CAS 单向保证：

```java
// 旧持有者（租约已超时被他人接管）释放：
oldLock.release();   // compareAndRemove(旧令牌) → 值已是新令牌 → 匹配失败 → 空操作
newLock.isHeld();    // true，新持有者不受任何影响
```

注意本组件保证的是**释放与续约的 fencing 安全**；临界区动作本身仍可能与接管者并发（租约恰好过期时）。`renew()`/`isHeld()` 返回 false 后应立即停止后续动作。

## 配置

```java
new KvLockManager(store, clock, new KvLockManager.Config()
        .setSpace("kv.lock")                    // 锁键空间
        .setOwnerId("instance-1")               // 持有者标识，默认自动生成
        .setHeartbeatIntervalMillis(10_000)     // 心跳间隔，须显著小于 lease（建议 lease/3）
        .setRetryIntervalMillis(200));          // acquire 阻塞重试间隔
```

`lease` 时长建议覆盖临界区最大耗时的 3 倍以上（容忍两次心跳失败仍不超时），且必须为正——`tryAcquire` 拒绝非正租约（永不过期的锁在进程崩溃后无法自愈）。`acquire` 的超时基于墙钟时间（`System.nanoTime`），不受注入的虚拟时钟影响——注入时钟只控制存储侧租约语义。锁丢失（续约失败被接管）后自动移出心跳列表，不再空转告警。

## 互斥范围与边界

| 底层存储 | 互斥范围 |
| :--- | :--- |
| 内存 | 仅当前进程（单机锁） |
| JDBC / Redis | 所有连接该存储的实例（分布式锁） |

- 底层异常（连接失败等）会从 `tryAcquire`/`acquire` 抛出，调用方需同时处理「null/false」与异常两类失败；
- 适合**尽量互斥**场景：定时任务防重、缓存刷新防击穿、清理互斥；高精度互斥（金融扣减等）请叠加业务幂等或专业锁组件；
- 已关闭的 manager 拒绝新的 `tryAcquire`（`IllegalStateException`）。
