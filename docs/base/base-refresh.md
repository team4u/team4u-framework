# 可刷新值 (RefreshableValue)

业务系统中大量存在「远端数据在本进程的影子」：全局配置、数据字典、黑白名单、第三方凭证。它们的共同诉求是**主动保持最新、变更可感知**——`RefreshableValue<T>` 将这类单值的「检测过期 → 加载新值 → 发布变更」固化为一个并发安全的声明式组件，业务只需声明「值怎么取、新鲜度怎么算」。

```java
import com.team4u.framework.base.refresh.RefreshableValue;

RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())
        .refreshEvery(java.time.Duration.ofSeconds(10))
        .background()                                        // 无人读也定时刷新
        .build();

GlobalConfig c = config.get();   // 热路径：一次 volatile 读
```

> 定位边界：**多键缓存**请使用 [TimedCache](base-cache.md)（内置）或 Caffeine（自带依赖）；**跨实例共享与全局唯一加载**（如多实例共用一份 token）请使用 [ExpiringValue](../kv/kv-lifecycle.md)。`RefreshableValue` 只做单进程内的单值影子。

---

## 核心概念

| 概念 | 说明 |
| :--- | :--- |
| `RefreshableValue<T>` | 可刷新值，实现 `Supplier<T>` 与 `AutoCloseable`，统一入口 |
| `Loader<T>` | 值加载器：`T load(LoadContext<T> ctx) throws Exception`，可抛受检异常 |
| `LoadContext<T>` | 加载上下文：`oldValue()` 上次成功值（首载为 null）、`attempt()` 本次尝试前的连续失败次数 |
| `Status` | 不可变状态快照：版本、计数、最近错误等，见[状态观测](#状态观测-status) |

---

## 语义模型：三个时间戳

全部时序语义收敛为状态里的三个时间戳：

| 时间戳 | 含义 | 计算 |
| :--- | :--- | :--- |
| **staleAfter**（软死期） | 值该刷新了 | `refreshEvery`：loadedAt + interval；`ttlOf`：loadedAt + max(ttl − refreshAhead, 0)；均未配置（MANUAL）时永不过期 |
| **hardAfter**（硬死期） | 值不可再服务 | staleAfter + maxStale，未配置 maxStale 时无限 |
| **retryAt**（冷却至） | 失败后不再打源端 | now + min(cooldownInitial × 2^(k-1), cooldownMax)，第 k 次连续失败；成功后清零 |

### `get()` 决策算法

```text
① 值未加载          → 阻塞加载（所有模式一致，get() 永不返回首载前的 null）
② now <  staleAfter → 返回当前值（热路径：一次 volatile 读 + 一次比较，低分配）
③ 已关闭            → 有值返值；未加载抛 IllegalStateException
④ now >= hardAfter  → 阻塞重载，绕过冷却；失败异常抛给调用方（maxStale 是正确性边界）
⑤ now <  retryAt    → 返回当前值（冷却兜底，不打源端）
⑥ 开启 swr          → 触发异步刷新，立即返回当前值
⑦ 否则              → 阻塞刷新
```

---

## 构建参数一览

| 参数 | 默认值 | 说明 |
| :--- | :--- | :--- |
| `name` | -（必填） | 日志与观测标识 |
| `loader` | -（必填） | 值加载器，返回 null 视为非法（抛 `IllegalArgumentException`） |
| `refreshEvery(Duration)` | - | 固定周期软死期，与 `ttlOf` 互斥 |
| `ttlOf(Function<T, Duration>)` | - | 按值计算软死期（如从响应中读取 expiresIn） |
| `refreshAhead(Duration)` | - | 提前刷新窗口，仅配合 `ttlOf` |
| `maxStale(Duration)` | 无限 | 硬死期增量，需已配置新鲜度 |
| `staleWhileRevalidate()` | 关闭 | 读时遇软死期立即返旧值 + 后台刷新（swr） |
| `background()` | 关闭 | 后台定时刷新（无人读也保持最新） |
| `warmup()` | 关闭 | build 时同步加载一次，失败从 `build()` 抛出 |
| `cooldown(Duration, Duration)` | 1s ~ 60s | 失败冷却倍增区间（initial × 2^(k-1)，封顶 max） |
| `onChange(BiConsumer<T, T>)` | 无 | 变更回调，可多次调用累积；仅值实际变更时触发 |
| `clock(Clock)` | 系统时钟 | 时间源注入，测试用 |
| `scheduler(ScheduledExecutorService)` | 共享池 | 自定义调度器（调用方所有，`close()` 不会关闭它） |

组合校验（`build()` 时快速失败）：`refreshEvery` 与 `ttlOf` 至多其一；`refreshAhead` / `background` / `maxStale` 需先配置新鲜度；`background + ttlOf` 必须提供 `refreshAhead`；**`ttlOf + staleWhileRevalidate` 必须提供 `maxStale`**（源宣告死期后无界供旧值是契约矛盾）。

---

## 典型场景

### 外部信号驱动（MANUAL）

不配置新鲜度，值仅随显式 `refresh()` 变化——适合由上游通知、管理端操作驱动的场景：

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDictFromDb())
        .onChange((oldV, newV) -> rebuildIndex(newV))
        .build();

dict.refresh();      // 收到外部变更信号时调用
Dict d = dict.get(); // 其余时刻零开销直读
```

### 配置影子：固定周期 + 后台刷新 + 失败兜底

```java
RefreshableValue<GlobalConfig> config = RefreshableValue.<GlobalConfig>builder()
        .name("global.config")
        .loader(ctx -> loadGlobalConfigFromDb())
        .refreshEvery(Duration.ofSeconds(10))
        .background()                                                    // 无人读也保持最新
        .cooldown(Duration.ofSeconds(1), Duration.ofSeconds(60))         // 源端故障时退避
        .onChange((oldV, newV) -> listeners.forEach(l -> l.onConfig(newV)))
        .build();
```

### 凭证续期：源决定死期 + 提前刷新 + 硬死期

```java
RefreshableValue<Token> token = RefreshableValue.<Token>builder()
        .name("auth.wechat")
        .loader(ctx -> fetchToken(ctx.oldValue()))   // LoadContext 可见旧值
        .ttlOf(Token::getTtl)                        // 有效期由响应决定
        .refreshAhead(Duration.ofMinutes(10))        // 死期前 10 分钟进入刷新窗口
        .maxStale(Duration.ofMinutes(2))             // 超过硬死期后 get() 阻塞重载，失败抛出
        .warmup()                                    // 启动即加载
        .build();
```

### 零等待读：stale-while-revalidate

```java
RefreshableValue<Dict> dict = RefreshableValue.<Dict>builder()
        .name("sys.dict")
        .loader(ctx -> fetchDict())
        .refreshEvery(Duration.ofSeconds(30))
        .staleWhileRevalidate()   // 读线程永不等待：过期后首个读触发后台刷新，期间返回旧值
        .build();
```

---

## 读取行为矩阵

| 模式 | `get()` 热路径 | 过期读 | 后台 | 失败行为 |
| :--- | :--- | :--- | :--- | :--- |
| MANUAL | volatile 直读 | 不存在过期 | 无 | `refresh()` 抛出，其余不动 |
| `refreshEvery` / `ttlOf` | volatile 直读 | 默认阻塞刷新 | 可选开启 | 阻塞方拿到异常；冷却期内返旧值 |
| `+ staleWhileRevalidate` | volatile 直读 | 立即返旧值 | 可选开启 | 旧值 + 冷却；超 `maxStale` 转为阻塞重载 |

---

## 并发契约

- **单飞（singleflight）**：同一时刻至多一个加载在途。同步路径（阻塞读 / `refresh()` / `warmup`）由触发线程执行加载，并发等待者等待**同一个** future——所有等待者共享同一次结果或同一个异常，不会各自重试；
- **状态信封**：值与全部元数据打包为不可变 `State`，经单个 `AtomicReference` 原子换发。单写者纪律（仅加载线程写）+ volatile 读保证：读方一次读取即获得一致快照，`status()` 无撕裂；
- **可见性**：加载线程发布新状态先行发生于唤醒等待者，阻塞方经 future、非阻塞方经 volatile 读，两条路径均有 happens-before 担保；
- **变更检测**：新旧值 `Objects.equals` 判定。未变更时不发布新引用、版本不推进、不触发 `onChange`——`version` 语义即「值实际变更次数」；
- **回调隔离**：`onChange` 在独立单线程守护线程池（`team4u-refresh-callback`）上执行，逐回调异常隔离（记 warn 不中断）。单加载线程按序提交 + 单消费线程 = 每值天然 FIFO。回调应保持轻量，重活请自行转交业务线程池。

## 后台刷新与线程模型

开启 `background()` 后，组件以 `scheduleWithFixedDelay` 周期执行一次廉价的时点检查（周期 = `refreshEvery` 或 `refreshAhead`），到期才经单飞触发实际加载；单个 tick 的异常被整体捕获，绝不终止后续周期。

默认使用全 JVM 共享的守护线程池（2 线程，`team4u-refresh-N`）；重 IO 加载请通过 `scheduler(...)` 注入独立调度器（调用方所有，`close()` 不会关闭它）。

## 生命周期

`close()` 幂等：停止后台任务；此后 `get()` 返回最后值不再刷新，`refresh()` 抛 `IllegalStateException`，未加载即关闭的 `get()` 同样抛出。不等待在途加载（其结果允许最后一次自然发布）。

---

## 状态观测 (Status)

```java
Status s = config.status();
s.getVersion();               // 值实际变更次数
s.getRefreshCount();          // 成功加载次数（含值未变化的刷新）
s.getFailureCount();          // 失败次数
s.getConsecutiveFailures();   // 当前连续失败次数（成功后清零）
s.getStale();                 // 是否已过软死期
s.getStaleMillis();           // 陈旧时长（未过期为 0）
s.getRetryAtMillis();         // 冷却结束时间戳
s.getLastError();             // 最近一次失败原因
```

快照取自单次状态读取，各字段来自同一次发布，并发刷新下保证一致。

---

## 单元测试

`clock` 注入虚拟时钟后，过期、冷却、提前刷新等时序行为可零 sleep 验证：

```java
MutableClock clock = new MutableClock();   // team4u-base 测试工具，亦可在业务测试中自建

RefreshableValue<String> v = RefreshableValue.<String>builder()
        .name("test").loader(() -> "A").refreshEvery(Duration.ofSeconds(10))
        .clock(clock).build();

clock.advanceMillis(11_000);
assertTrue(v.isStale());
```

契约测试（并发单飞、失败冷却、swr 零等待、close 语义、后台自愈等 15 项）见 `team4u-base` 模块 `RefreshableValueTest`，可作为行为规范的Executable 参照。
