# 会话与失败处理

深入机制：执行回执的状态流转、执行者崩溃后的接管、存储选型与限制，以及组件异常的多层收口关系。入门请先看[快速开始](quick-start.md)与[场景指南](scenarios.md)。

---

## 会话状态机

执行回执保存在 `singleflight.session` space，值是 JSON。`token` 是这次执行的锁持有者令牌，也是 CAS 的身份边界。

| 状态 | 写入者 | 载荷 | TTL | 后续读取 |
| :--- | :--- | :--- | :--- | :--- |
| `PENDING` | 获得锁的执行者 | `token`、`startedAtMillis` | 动态：`max(waitTimeoutMillis + max(uncacheableTtlMillis, failureTtlMillis), 1000)` | 锁仍存在则等待；锁不存在则尝试接管 |
| `SUCCESS_CACHEABLE` | loader 成功且 `cacheWhen` 通过 | `token`、`result`、`finishedAtMillis` | `uncacheableTtlMillis` | WAIT 调用者反序列化 `result`；执行者另行写 `singleflight.cache` |
| `SUCCESS_NOT_CACHEABLE` | loader 成功但 `cacheWhen` 不通过 | `token`、`result`、`finishedAtMillis` | `uncacheableTtlMillis` | WAIT 调用者可读取结果，但不写结果缓存 |
| `FAILURE` | loader 抛出 `RuntimeException` / `Error` | `token`、`error`、`finishedAtMillis` | `failureTtlMillis` | WAIT 调用者收到 `SingleFlightExecutionException`，message 来自原异常 |

终态发布使用 `compareAndSet(sessionKey, pending.toJson(), terminal)`：存储里的回执必须「还是我写的那份 PENDING」才允许写成终态。只要接管者已经写入新 token 的 PENDING，旧执行者的 CAS 必然失败。

**执行者崩溃后的接管流程**：

1. 执行者 A 获得锁，写入 `PENDING(tokenA)`，随后进程崩溃、心跳停止；
2. WAIT 调用者读到 `PENDING(tokenA)`，再检查锁记录——已随租约到期消失；
3. 调用者 `tryAcquire` 抢锁，成功后先重读回执：A 若恰好刚发布终态则直接复用结果，否则写入新的 `PENDING(tokenB)`；
4. A 若此时晚到完成，它用 `tokenA` 的 PENDING 做 CAS 会失败，盖不掉 `tokenB` 的回执；
5. 接管者执行 loader 并发布自己的终态；接管路径不写结果缓存——避免由未持有原始请求语义的线程替调用方决定长 TTL 缓存。

首次协调同样「抢锁后重读」：从进入协调到真正抢到锁之间，上一个执行者可能已完成并释放锁，此时直接复用已发布结果——这是「同 key 一个周期只执行一次」的最后一道保障。

> 本地执行 loader 的调用者收到原始业务异常；其他线程或实例只能从失败回执读取错误信息，收到的是 `SingleFlightExecutionException`（只含 message）——组件不承诺跨线程重建原异常对象。

## errorFallback 与 exceptionHandler 的优先级

`errorFallback` 在引擎层兑底，`exceptionHandler` 在代理层接异常——引擎先执行，因此：

| 场景 | errorFallback | exceptionHandler | 实际结果 |
| :--- | :--- | :--- | :--- |
| 竞争 / 超时 / 失败回执 | 已配置 | 已配置 | **errorFallback 生效**，handler 收不到这些异常 |
| 竞争 / 超时 / 失败回执 | 未配置 | 已配置 | 异常抛到代理层，**handler 生效** |
| 竞争 / 超时 / 失败回执 | 未配置 | 未配置 | 异常抛给调用方 |
| 配置错误 | 无论是否配置 | 已配置 | 异常穿透引擎（不兑底），**handler 生效** |
| loader 业务异常 | 无论是否配置 | 无论是否配置 | 都不生效，原样上抛 |

不配置 `errorFallback` 时行为与未引入该字段前完全一致，无隐藏默认值。注意：若依赖 handler 统一记录组件异常日志（监控埋点），配上 errorFallback 后竞争 / 超时类事件不再经过 handler，需改从引擎日志观察。

## 存储选型与限制

| 存储 | 互斥范围 | 说明 |
| :--- | :--- | :--- |
| `InMemoryKvStore` | 当前 JVM | 单测、单实例。跨进程调用者不会合并到同一个执行窗口 |
| `RedisKvStore` | 连接同一 Redis 的实例 | 生产常用，支持 `CasCapable`，适合跨实例回源合并 |
| `JdbcKvStore` | 连接同一数据库的实例 | 共享协调可用；高 QPS 热点 key 需评估数据库压力 |
| 其他 `KvStore` | 由存储决定 | 最内层存储必须实现 `CasCapable`，否则引擎构造或规则编译失败 |

**协调路径必须直达底层**：

- 引擎对默认存储和命名存储都调用 `KvStores.innermost`，再在最内层存储上校验 `CasCapable`；
- `TieredStore`、`ObservedStore` 等装饰层不参与锁、回执和结果缓存读写。传入 `TieredStore` **不会**得到 singleflight 结果缓存的 L1 加速；
- 目的：避免本地 L1 让锁续约、回执轮询或 CAS 读到陈旧 token；
- 引擎没有单独的「协调存储 / 结果缓存存储」字段。若业务在 singleflight 之外再套分层缓存，需自行处理负缓存和 TTL：`SUCCESS_NOT_CACHEABLE` 与 `FAILURE` 只写回执，不会给外部缓存写墓碑；外部 L1 在自身 TTL 窗口内也可能看不到其他实例的新结果。分层存储自身的边界见[分层存储](../kv/kv-tiered.md)；
- 同一规则 id 的 `store` 名不能在热更新中从 A 改为 B；新编译失败时旧规则继续服务。

**存储异常策略**：

| `onStoreFailure` | 行为 |
| :--- | :--- |
| `PASS_THROUGH` | 记 warn，跳过协调直接执行 loader。存储故障时失去合并能力，但保住业务可用性 |
| `FAIL_CLOSED` | 抛出包装了原因的 `SingleFlightConfigException`。协调阶段失败时 loader 不执行；若失败发生在 loader 成功后的结果缓存写入，loader 已经执行过 |
