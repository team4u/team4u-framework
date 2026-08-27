# 可刷新值 (RefreshableValue)

很多数据的源头在远端（数据库、配置中心、第三方接口），读取却发生在进程内的高频路径上：全局配置、数据字典、黑白名单、第三方 token。每次读取都访问远端不可行，常见的做法是在本地保留一份副本并定期刷新。`RefreshableValue<T>` 是这份副本的标准化实现：业务声明值从哪来（loader）与多久算旧（刷新策略），加载、并发控制、失败冷却、变更通知由组件负责。

它只管理单个值。多键缓存使用 [TimedCache](base-cache.md) 或 Caffeine；值需要多实例共享、全集群仅加载一份时，使用 [ExpiringValue](../kv/kv-lifecycle.md)。

```java
RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())
        .refreshEvery(Duration.ofSeconds(10))
        .build();

GlobalConfig c = config.get();   // 值未过期时为一次内存读
```

## get() 的行为

| `get()` 时值的状态 | 行为 |
| :--- | :--- |
| 未过期 | 直接返回，无加载开销 |
| 已过期（默认模式） | 当前线程加载新值后返回；并发请求等待同一次加载，不重复访问远端 |
| 已过期（开启 `staleWhileRevalidate`） | 立即返回旧值，刷新在后台执行 |
| 尚未加载 | 加载完成后才返回。首次加载完成前不会返回 null |
| 加载失败，有旧值 | 返回旧值并进入失败冷却，冷却期间不再访问远端 |
| 加载失败，无旧值（首次加载失败） | 异常抛给调用方 |

## 固定周期刷新（配置、字典）

数据在数据库或配置表中，进程内保持一份最新副本：

```java
RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())
        .refreshEvery(Duration.ofSeconds(10))            // 每 10 秒重新加载
        .background()                                    // 后台定时刷新，无人读取也保持最新
        .onChange((oldV, newV) -> rebuildIndex(newV))    // 值变化后的回调
        .build();
```

要点：

- 默认为读时驱动：仅当 `get()` 发现过期时才刷新。开启 `background()` 后由后台线程定时刷新，两者可同时开启，后台保底、读时兜漏；
- 加载失败后进入冷却：首次失败等待 1 秒，连续失败每次翻倍，上限 60 秒，可用 `cooldown(Duration, Duration)` 调整；冷却期间 `get()` 返回旧值，加载成功后冷却清零；
- 新值与旧值 `equals` 相同时不算变更：不触发 `onChange`、不输出变更日志，内存中的对象引用也不替换；
- loader 返回 null 将抛出 `IllegalArgumentException`——null 无法与「尚未加载」区分。

## 按值有效期刷新（token）

有效期由远端响应决定时（如 `expiresIn=7200` 秒），用 `ttlOf` 代替固定周期：

```java
RefreshableValue<Token> token = RefreshableValue.<Token>builder()
        .name("auth.wechat")
        .loader(ctx -> wechatClient.getAccessToken())          // ctx.oldValue() 为上一次的值
        .ttlOf(t -> Duration.ofSeconds(t.getExpiresIn()))      // 有效期从响应中读取
        .refreshAhead(Duration.ofMinutes(10))                  // 距过期不足 10 分钟即视为过期
        .maxStale(Duration.ofMinutes(2))                       // 旧值最多透支 2 分钟
        .warmup()                                              // 构造时同步加载一次
        .build();
```

- `refreshAhead`：进入该窗口后 `get()` 会提前完成续期，避免使用到真正过期的时刻；
- `maxStale`：旧值超过软死期该时长后不再返回，`get()` 转为阻塞重载，失败时异常抛给调用方。已失效的凭证继续使用，往往在下游产生更难定位的错误；
- 组合校验：`ttlOf` 与 `staleWhileRevalidate` 同时使用时必须配置 `maxStale`，源端已声明死期的值不应无限期透支。其余非法组合（如同时配置 `refreshEvery` 与 `ttlOf`）在 `build()` 时直接抛 `IllegalArgumentException`。

## 零等待读（stale-while-revalidate）

对新鲜度有秒级容忍、对延迟敏感的读取场景：

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDict())
        .refreshEvery(Duration.ofSeconds(30))
        .staleWhileRevalidate()
        .build();
```

首个发现过期的请求触发后台刷新并立即返回旧值，新值就绪后自然生效，读线程在任何时刻都不会因刷新而等待。

## 外部信号驱动

不配置刷新策略时值不会自动过期，仅在 `refresh()` 时重新加载。适合上游已有变更通知（MQ、管理端操作）的场景：

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDictFromDb())
        .onChange((oldV, newV) -> rebuildIndex(newV))
        .build();

dict.refresh();   // 收到变更信号时调用
```

`refresh()` 同步执行加载，失败抛出异常，重试策略由调用方决定；与在途刷新并发时合并为同一次。config 组件的 `DbConfigWatcher` 即此用法：值为 `system_config` 表的 `MAX(update_time)`，`onChange` 触发时通知配置重载。

## 变更通知（onChange）

| 情况 | 是否触发 | 原因 |
| :--- | :--- | :--- |
| 值发生变化 | ✅ | 参数为 (旧值, 新值) |
| 新值与旧值 `equals` 相同 | ❌ | 值未变化，无需通知 |
| 加载失败 | ❌ | 值未变化，失败由冷却机制处理 |
| 首次加载（null → 值） | ✅ | 旧值为 null |

回调在独立的单线程守护线程池上执行，单个回调抛出异常仅记录 warn，不影响后续回调与刷新本身；同一值的回调按变更顺序执行。回调应保持轻量，耗时操作移交业务线程池。

## 并发行为

- 值过期时的并发 `get()` 仅触发一次加载：第一个线程执行 loader，其余线程等待同一个结果；加载失败时，等待者收到同一个异常，不会各自重试；
- 新值经原子引用发布，所有线程的下一次读取立即可见；
- `status()` 的各字段取自同一次原子读取，并发刷新下不会出现版本与计数不一致的快照；
- 后台刷新任务的单次异常不会终止后续周期。

## 状态观测（status）

```java
Status s = config.status();
s.getConsecutiveFailures();   // 连续失败次数，大于 0 说明加载持续失败
s.getLastError();             // 最近一次失败的异常
s.getRetryAtMillis();         // 冷却结束、下一次尝试的时间
s.getStale();                 // 当前值是否已过软死期
s.getStaleMillis();           // 已过期的时长
s.getVersion();               // 值变更次数（值未变化不递增）
s.getRefreshCount();          // 成功加载次数（含值未变化的刷新）
```

值长时间未刷新时，先看 `consecutiveFailures` 与 `lastError` 定位加载失败的原因，`retryAtMillis` 指示下一次尝试时间。

## 配置速查

| 配置 | 默认值 | 什么时候需要 |
| :--- | :--- | :--- |
| `name` | 必填 | 日志与排障标识 |
| `loader` | 必填 | 值的加载方式。返回 null 会被拒绝 |
| `refreshEvery` | 无 | 固定周期刷新 |
| `ttlOf` | 无 | 有效期由值自身决定（token 类）。与 `refreshEvery` 二选一 |
| `refreshAhead` | 无 | 提前刷新窗口，仅配合 `ttlOf` |
| `maxStale` | 无限 | 旧值可透支的时长上限。`ttlOf` + `staleWhileRevalidate` 时必填 |
| `staleWhileRevalidate()` | 关闭 | 读线程不接受任何刷新等待时开启 |
| `background()` | 关闭 | 无人读取也要保持最新时开启 |
| `warmup()` | 关闭 | 构造时同步加载，失败从 `build()` 抛出 |
| `cooldown(initial, max)` | 1s ~ 60s | 源端故障时的退避节奏 |
| `onChange` | 无 | 值变化后需要执行的动作 |
| `clock` | 系统时钟 | 测试注入 |
| `scheduler` | 共享池 | 加载为重 IO 时注入独立调度器。调度器归调用方所有，`close()` 不会关闭它 |

## 线程与关闭

- 后台刷新与回调默认使用全 JVM 共享的守护线程池（2 线程，`team4u-refresh-N`）。加载为重 IO 时应通过 `scheduler` 注入独立调度器，避免慢加载影响其他值；
- `close()` 幂等：停止后台任务。此后 `get()` 返回最后的值，`refresh()` 抛出 `IllegalStateException`。

## 单元测试

注入可拨动的时钟后，过期、冷却、提前刷新等时间相关行为无需真实等待即可验证：

```java
MutableClock clock = new MutableClock();   // team4u-base 测试工具

RefreshableValue<String> v = RefreshableValue.<String>builder()
        .name("test").loader(() -> "A")
        .refreshEvery(Duration.ofSeconds(10))
        .clock(clock).build();

clock.advanceMillis(11_000);
assertTrue(v.isStale());
```

组件的全部行为契约（并发单飞、失败冷却、零等待读、关闭语义、后台自愈等）见 `team4u-base` 模块的 `RefreshableValueTest`。
