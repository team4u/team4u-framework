# 时长校验与租约心跳 (DurationUtil / Expiry / ScheduledHeartbeat)

租约、TTL、心跳间隔这类时间参数散落在各个模块（lease、kv、retry、id），它们对「时长怎么校验、时间戳怎么算、心跳怎么跳」的需求高度一致，却各自长出了私有实现。`team4u-base` 把这三件事收敛为三个小而明确的工具：

| 工具 | 职责 | 失败策略 |
| :--- | :--- | :--- |
| `DurationUtil` | `Duration` → 毫秒的**校验换算** | 不满足要求抛 `IllegalArgumentException` |
| `Expiry` | 时间戳的**饱和运算**（now + ttl、剩余时长、过期判定） | 永不抛异常，上溢封顶 `Long.MAX_VALUE` |
| `ScheduledHeartbeat` | 持有者令牌的**固定间隔续约心跳** | 续约失败即弃权（回调 `onLost`），异常容忍重试 |

## DurationUtil：校验层，拒绝可疑配置

业务 API 普遍用 `java.time.Duration` 表达时长（如 `lease(Duration.ofSeconds(30))`），但存储与运行期只认毫秒。换算必须回答三个问题：负数怎么办？亚毫秒精度（纳秒余数）怎么办？超出 long 毫秒范围怎么办？

`DurationUtil` 的答案是**入口显式拒绝**——只接受「精确到毫秒」的时长，任何有损换算都不静默截断：

```java
// 任一要求不满足均抛 IllegalArgumentException（消息含参数名定位）
DurationUtil.requireExactMillis(Duration.ofSeconds(30), "lease");       // 30_000
DurationUtil.requirePositiveMillis(Duration.ofSeconds(30), "lease");    // 30_000，且拒绝 0
DurationUtil.requireNonNegativeMillis(Duration.ofSeconds(0), "timeout"); // 0 合法（表示不等待）
```

三个变体对应三类语义：

- `requireExactMillis`：基线，非 null、非负、无纳秒余数、可被 long 毫秒表示；
- `requirePositiveMillis`：租约时长、心跳间隔等「零值无意义」的场景；
- `requireNonNegativeMillis`：超时等待、轮询间隔等「零值合法（表示不等待）」的场景。

为什么在入口抛异常而不是截断？租约从 30.5 秒被截成 30 秒尚可接受，从 `Long.MAX_VALUE` 毫秒回绕成负数就是「立即过期」——静默截断会让语义漂移悄无声息地进入存储层。校验失败发生在配置入口，堆栈直接指向调用方，比运行期的诡异行为好定位得多。

## Expiry：运行期，饱和不抛异常

与校验层相反，运行期的时间戳运算**不抛异常**：进入运行期的值已经过了入口校验，此刻再遇到 `now + ttl` 上溢，语义是「极远的未来」，等价于「永不过期」——这是有明确定义且安全的结果，抛异常反而会把罕见边界放大成运行时故障。

```java
Expiry.expiryFromNow(30_000L);              // now + 30s；上溢封顶 Expiry.NEVER（Long.MAX_VALUE）
Expiry.expiryFrom(clock.millis(), 30_000L); // 指定起始时间（测试注入时钟）
Expiry.remainingMillis(expiry);             // 距过期剩余毫秒；已过期返回 0（不返回负数）
Expiry.isExpired(expiry);                   // now >= expiry 即视为已过期
Expiry.NEVER                                 // 永不过期哨兵；remainingMillis(NEVER) == NEVER
```

两层的分工契约：

| 层 | 类 | 策略 | 适用 |
| :--- | :--- | :--- | :--- |
| 校验层 | `DurationUtil`（及各模块入口校验，如 lease 的 `LeaseTimes`） | 拒绝会让语义漂移的极端配置，入口即失败 | 用户可配置项（租约、超时、间隔） |
| 运行期 | `Expiry` | 已合法时间戳的增量计算饱和封顶，不因罕见边界抛异常 | 存储与运行期已持有合法值的运算 |

一句话记忆：**配置错了要炸得早（校验层），运行期边界要炸不了（饱和层）**。lease 模块保留的 `LeaseTimes.plusMillis` 即「校验层」在该模块的入口约束——巨大的提交延迟/租约时长以 `IllegalArgumentException` 拒绝且不产生副作用。

`Expiry` 不感知「0 表示永不过期」等模块私有哨兵语义（如 KvRecord 以 `expireAt=0` 表示永不过期）——它的哨兵是 `NEVER = Long.MAX_VALUE`，模块如需 0 哨兵请在自身边界转换。

## ScheduledHeartbeat：租约心跳器

拿着执行权（锁、租约）的组件需要一个后台心跳：按固定间隔向存储续约，证明「我还活着」。此前 KvLockManager（wait/notify 专用线程）与 lease TaskWorker（HeartbeatTask）各有一套私有实现，线程模型不一致。`ScheduledHeartbeat` 统一为纯 JDK 调度器模型：

```java
ScheduledHeartbeat heartbeat = ScheduledHeartbeat.builder()
        .token(holderToken)            // 持有者令牌：续约时校验「续的是自己的约」
        .leaseMillis(30_000L)          // 租约时长
        .intervalMillis(10_000L)       // 心跳间隔，默认 lease/3
        .onLost(() -> abortWork())     // 租约丢失回调（续约返回 false 时触发一次）
        .operation(token -> backend.renew(token))   // 续约操作，返回是否成功
        .build();
heartbeat.start();
try {
    doCriticalWork();
} finally {
    heartbeat.stop();                  // 释放持有权时主动停跳，幂等
}
```

行为契约：

- **失败即弃权**：续约操作返回 `false` 视为租约丢失（过期被接管、令牌不匹配），立即停止后续心跳并触发 `onLost`——继续心跳只会刷他人的租约。持有方收到 `onLost` 后应立即停止临界区工作；
- **异常容忍**：续约抛出的异常只记 warn、不停止心跳——瞬时故障（网络抖动）不应弃权，默认间隔（lease/3）已预留两个失败窗口。`Error` 会上抛并停止，避免「假活着」；
- **一次性实例**：一个实例只对应一段持有期，丢失（`onLost` 触发）或 `stop` 后不可复用，需重新 build；
- **线程模型**：每个心跳器独占一个 daemon 调度线程（命名 `team4u-heartbeat-N`），进程退出不被心跳线程阻塞，全部停止后线程自动回收。`onLost` 回调在心跳调度线程执行，应快速返回且不得抛异常（抛出仅记日志）。

`intervalMillis` 必须大于 0 且严格小于租约时长（`build()` 校验）；不设置时取 `leaseMillis / 3`。

## 与各模块的关系

- **lease** ：业务 API 的 `Duration` 参数经 `DurationUtil` 换算毫秒后进入 SPI（TaskWorker 自身的心跳因生命周期与 worker 关闭联动而保留私有实现，未复用本类）；
- **kv** ：锁的心跳由 `KvLockManager` 自身管理（一个 manager 多锁共用一条心跳线程，间隔自适应收缩），续约语义见 [CasCapable.compareAndExpire](../kv/kv-store.md)；
- **retry** ：租约持久化重试（MANAGED 模式）的时长参数经 `DurationUtil` 校验换算；
- **id / ratelimiter** ：TTL 与时间戳运算复用存储侧既有饱和行为，与本工具语义一致。
