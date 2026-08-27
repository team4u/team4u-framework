# 可刷新值 (RefreshableValue)

系统里常有这样一类数据：**存在远端（数据库、配置中心、第三方接口），但读得非常频繁**——全局配置、数据字典、黑白名单、第三方 token。每次读都去远端查一遍，扛不住；于是大家各自写一份「本地缓存 + 定时刷新」，写着写着就遇到同样的问题：

- 自建一个后台线程轮询？十个这样的值就是十个线程；
- 读的时候才发现过期了？第一个请求要同步等远端返回，其余请求跟着一起等；
- 远端挂了几分钟？每个请求都在砸远端，旧数据明明还能用；
- 手写 `volatile` + 双重检查锁？可见性、重复加载这些坑，写错过一次才知道疼。

`RefreshableValue<T>` 把这件事一次性做对。业务只声明两件事——**值从哪来**（loader）、**多久算旧**（刷新策略），剩下的问题它全包：

```java
RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())   // 值从哪来
        .refreshEvery(Duration.ofSeconds(10))      // 10 秒算旧
        .build();

GlobalConfig c = config.get();   // 值新：一次内存读，纳秒级
```

> 它管的是**单个值**。如果要的是「按键缓存的多个值」，用 [TimedCache](base-cache.md) 或 Caffeine；如果值要**多实例共享、全集群只加载一份**（比如部署了 5 个节点共用一个 token），用 [ExpiringValue](../kv/kv-lifecycle.md)。

## `get()` 的时候会发生什么

不需要记任何规则，看这张表就够了：

| `get()` 时值的状态 | 发生什么 |
| :--- | :--- |
| 还是新的 | 直接返回，一次内存读，零开销 |
| 旧了（默认模式） | 当前线程加载新值再返回；期间其他线程的并发请求**等待同一次加载**，不会重复打远端 |
| 旧了（开了 `staleWhileRevalidate`） | 立即返回旧值，后台悄悄刷新，读线程永不等待 |
| 还从没加载过 | 第一次 `get()` 加载完才返回——**它绝不会返回首载前的 null** |
| 加载失败，手里有旧值 | 返回旧值，并且进入**失败冷却**（见下），不再反复打远端 |
| 加载失败，手里没有旧值（首次加载就失败） | 异常抛给你，由你决定怎么处理 |

## 场景一：配置/字典影子（最常见）

数据在数据库表里，希望进程里永远有一份热的，谁要用直接拿：

```java
RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())
        .refreshEvery(Duration.ofSeconds(10))   // 每 10 秒重新拉一次
        .background()                            // 没人读也定时刷新（凌晨也不例外）
        .onChange((oldV, newV) -> rebuildIndex(newV))   // 值变了收到通知
        .build();
```

上面这段代码没有写的默认行为，恰恰是它替你兜住的部分：

- **数据库挂了会怎样**：第一次失败后歇 1 秒再试，连续失败就翻倍（1s → 2s → 4s…），最长歇 60 秒；期间所有 `get()` 拿旧值，业务无感。恢复成功后冷却立即清零。想改这两个数：`.cooldown(Duration.ofSeconds(1), Duration.ofSeconds(60))`
- **值没变会怎样**：新拉到的值和旧值 `equals` 相同，则不算变更——不触发 `onChange`、不打变更日志、连内存里的对象引用都不换（对依赖对象身份的代码友好）
- **`background()` 不开会怎样**：变成「读时驱动」——没人读就永远不刷新，第一个读到旧值的请求承担刷新耗时。两者可以同时开：后台保底，读时兜漏

## 场景二：第三方 token 续期

token 的有效期是**接口响应说了算**的（比如微信返回 `expiresIn=7200` 秒），不是你拍脑袋定的间隔。这种情况换成「从值里读有效期」：

```java
RefreshableValue<Token> token = RefreshableValue.<Token>builder()
        .name("auth.wechat")
        .loader(ctx -> wechatClient.getAccessToken())  // ctx.oldValue() 可以拿到旧 token
        .ttlOf(t -> Duration.ofSeconds(t.getExpiresIn()))  // 有效期由响应决定
        .refreshAhead(Duration.ofMinutes(10))   // 死期前 10 分钟就开始换新
        .maxStale(Duration.ofMinutes(2))        // 最多透支 2 分钟旧值
        .warmup()                               // 启动时就取一次，别等第一个请求
        .build();
```

用人话翻译两个关键参数：

- **`refreshAhead`**：离过期还有 10 分钟时，值就算「旧了」，`get()` 会顺手换新——不让任何人用到真过期的那一刻
- **`maxStale`**：万一续期一直失败（第三方挂了），旧 token 最多再透支使用 2 分钟；超过后 `get()` 不再给旧值，而是把加载异常抛出来——**token 死了就该报错，而不是拿着死 token 去撞接口**。这就是为什么开了 `staleWhileRevalidate` 时 `ttlOf` 场景必须显式给 `maxStale`：总得有个人说清楚透支上限

单进程用这套就够了。如果是**多实例部署、想全集群只刷新一份**，请换 [ExpiringValue](../kv/kv-lifecycle.md)（值住 KV 存储、跨实例单飞）。

## 场景三：零等待读（stale-while-revalidate）

有些场景读请求一毫秒都不想等——字典旧一秒无所谓。那就在值变旧时**先给旧的、转身再刷**：

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDict())
        .refreshEvery(Duration.ofSeconds(30))
        .staleWhileRevalidate()   // 唯一的差别：过期后 get() 立即返旧值，刷新在后台进行
        .build();
```

第一个发现值过期的请求触发后台刷新，它自己和后续请求都拿旧值；新值就绪后自然切换。适合「新鲜度要求秒级容忍」的读多场景。

## 场景四：外部信号驱动（不配刷新策略）

已经有人会告诉你值变了（比如 MQ 通知、管理端操作），就不需要猜间隔：

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDictFromDb())
        .onChange((oldV, newV) -> rebuildIndex(newV))
        .build();          // 不配 refreshEvery/ttlOf = 永不自动刷新

dict.refresh();            // 收到变更信号时调用；失败会抛异常，由调用方决定重试策略
```

config 组件的 `DbConfigWatcher` 就是这个用法：值是 `system_config` 表的 `MAX(update_time)`，探测到时间戳变大（即 `onChange` 触发）就去通知配置重载。

## 变更通知（onChange）

| 情况 | 是否触发 |
| :--- | :--- |
| 值实际发生了变化 | ✅ 参数是 (旧值, 新值) |
| 新值和旧值 `equals` 相同 | ❌（这才是它的价值：没变就别打扰我） |
| 加载失败 | ❌（值没变，失败走冷却） |
| 首次加载（null → 值） | ✅（旧值为 null，习惯上把首载当变更） |

回调在**独立的单线程守护线程池**上执行，每个回调的异常被隔离（记 warn，不影响其他回调和刷新本身），同一个值的回调保证按变更顺序到达。回调请保持轻量——要干重活自己往业务线程池里扔。

## 这些事它替你办好了（不用再手写）

- **并发只加载一次**：100 个线程同时发现值过期，只有一个线程真正去远端，其余等它——等的人拿到的是**同一个结果**（成功共享新值，失败共享同一个异常，不会各自再试一遍）
- **值的可见性**：新值发布后，所有线程的下一次 `get()` 立即可见——不需要你写 `volatile`，也不会遇到旧实现里那类可见性坑
- **状态快照不撕裂**：`status()` 的所有字段来自同一次原子读取，并发刷新下看到的版本、计数、错误是同一瞬间的
- **后台任务死不了**：某次刷新抛异常不会终止后续周期（JDK 调度器「任务抛异常就静默取消」的坑已处理）

## 「为什么我的值不刷新了？」——用 status() 排障

```java
Status s = config.status();
s.getConsecutiveFailures();   // 连续失败次数：>0 说明源端有问题
s.getLastError();             // 最近一次失败的原因（完整异常）
s.getRetryAtMillis();         // 冷却到这个时间点才会再试
s.getStale();                 // 当前值是否已过软死期
s.getStaleMillis();           // 已经旧了多久
s.getVersion();               // 值实际变更次数（没变不递增）
s.getRefreshCount();          // 成功加载次数（含值没变化的刷新）
```

典型排障路径：值一直不新 → `consecutiveFailures > 0` → 看 `lastError` 是数据库挂了还是 loader 写错了 → 好了之后冷却自动解除。

## 配置速查

| 配置 | 默认值 | 什么时候需要它 |
| :--- | :--- | :--- |
| `name` | 必填 | 日志和排障时的身份标识 |
| `loader` | 必填 | 值从哪来。返回 null 会被拒绝（防止把「加载了 null」和「还没加载」混为一谈） |
| `refreshEvery` | 无 | 固定周期刷新（配置/字典类） |
| `ttlOf` | 无 | 有效期由值自己说了算（token 类）。与 `refreshEvery` 二选一 |
| `refreshAhead` | 无 | 提前刷新窗口，只配 `ttlOf` 时有意义 |
| `maxStale` | 无限 | 旧值最多透支多久。`ttlOf` + `staleWhileRevalidate` 时**必填** |
| `staleWhileRevalidate()` | 关 | 读线程一毫秒都不能等时开 |
| `background()` | 关 | 没人读也要保持最新时开（否则是读时驱动） |
| `warmup()` | 关 | 启动时就加载一次，失败从 `build()` 直接抛出 |
| `cooldown(initial, max)` | 1s ~ 60s | 源端故障时的退避节奏，默认够用 |
| `onChange` | 无 | 值变了要做点什么（重建索引、发通知） |
| `clock` | 系统时钟 | 仅测试注入 |
| `scheduler` | 共享池 | 加载是重 IO 时注入独立调度器（**归你所有，close 不会替你关**） |

组合上有一条强制校验值得单独记：**`ttlOf`（源宣告了死期）+ `staleWhileRevalidate`（旧值照给）必须配 `maxStale`**——源都说了值几点死，不能无限制拿旧值顶。其余非法组合（比如两个刷新策略都配）在 `build()` 时直接报错，不会静默选一个。

## 线程与关闭

- 默认共享一个 2 线程守护线程池（`team4u-refresh-N`），全 JVM 复用；重 IO 加载请自备 `scheduler`
- `close()` 幂等：停后台任务；之后 `get()` 返回最后值不再刷新，`refresh()` 抛 `IllegalStateException`

## 单元测试怎么写

注入一个可以拨动的时钟，过期、冷却、提前刷新全都不用真等：

```java
MutableClock clock = new MutableClock();   // base 测试工具，业务测试可照抄实现

RefreshableValue<String> v = RefreshableValue.<String>builder()
        .name("test").loader(() -> "A")
        .refreshEvery(Duration.ofSeconds(10))
        .clock(clock).build();

clock.advanceMillis(11_000);
assertTrue(v.isStale());
```

完整的 15 项契约测试（并发单飞、失败冷却、零等待读、关闭语义、后台自愈）见 `team4u-base` 模块的 `RefreshableValueTest`——它们就是这份文档里每一条行为的可执行版本。
