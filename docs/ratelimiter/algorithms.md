# 算法详解

四个内置算法均为无状态单例（纯决策逻辑、零存储代码），全部状态保存在 KvStore 中。每个算法声明所需 kv 能力接口，引擎在**规则加载期**逐一校验存储齐备，缺能力当场抛 `RateLimitConfigException`——不会等到运行期才行为错乱。

| 算法 | `key()` | 所需能力 | 能力的内存/Redis/JDBC 支持 |
| :--- | :--- | :--- | :--- |
| 固定窗口 | `fixed-window` | `CounterCapable` | ✅ / ✅（`INCRBY`）/ ✅（行锁） |
| 令牌桶 | `token-bucket` | `CasCapable` | ✅ / ✅（Lua）/ ✅（条件 UPDATE） |
| 滑动窗口 | `sliding-window` | `ScoredWindowCapable` | ✅ / ✅（ZSET + Lua）/ ❌（未实现） |
| 历史窗口 | `history-window` | 无（无状态） | 不使用存储 |

`JdbcKvStore` 未实现 `ScoredWindowCapable`，因此 `sliding-window` 规则绑定 JDBC 存储会在加载期报错（`Rate limit store not capable`）；需要跨实例精确滑动窗口时使用 Redis 后端。

## 固定窗口（fixed-window）

**语义**：窗口内第 N 次请求 `n = incrementAndGet(key, permits, windowMillis)`，`n <= threshold` 放行。一次原子递增完成全部工作，是四个算法中最轻的。**所用 kv 原语与契约要点**（`CounterCapable.incrementAndGet(key, delta, ttlMillis)`）：

- 键不存在时从 0 开始计数，首次调用返回 `delta`（不要求预先建键）；
- 递增与 TTL 设置在同一原子操作内完成，并发调用不丢失更新、不出现「重置与累积分离」的中间态；
- TTL 在键创建时设置、后续递增**不刷新**；存量无 TTL 键首次遇到 `ttlMillis > 0` 的递增时补充设置 TTL；
- 过期后的首次递增从 0 重新开始（返回值等于 `delta`）。

**浮动窗口（浮窗）锚定**：窗口 TTL 自**本窗口首次递增**起算，即窗口起算点是「本窗口第一个请求到达时刻」，而非墙钟对齐时刻。举例：阈值 5、窗口 60 秒，用户在第 0 秒发第 1 个请求则窗口覆盖第 0~60 秒；若用户 30 秒内无请求、第 61 秒重来，则新窗口自第 61 秒起算。由此带来两点：

- 窗口边缘可能双倍突发（相邻半窗各打满阈值），对精度不敏感的配额场景可接受；
- 浮窗无法精确给出重试等待，`retryAfterMillis` 恒为 `null`。

**窥探**：`permits = 0` 时 `delta = 0`，计数不变，仅探测 `n <= threshold`。

## 令牌桶（token-bucket）

**语义**：`threshold` = 桶容量（最大突发量），`windowMillis` = 注满一桶所需时间，补充速率为 `capacity / windowMillis` 个令牌每毫秒。例：`threshold=100, windowMillis=10000` 表示平均 10 个/秒、瞬时最多 100 个。**所用 kv 原语与契约要点**：桶状态以 JSON 存于 kv 值域（`{"tokens":剩余令牌,"lastMillis":最近补水时刻}`），读写复用 `KvStore` 四操作：

- 新桶满桶起算：不存在时以 `capacity - permits` 初始令牌建桶，`put(IF_ABSENT)` 防并发重复建桶，抢建失败重读重试；
- 补水按 `min(capacity, tokens + elapsed * rate)` 计算，`elapsed` 取自 `lastMillis`（时钟回拨时补水量按 0 处理，不出现负增长）；
- **CAS 循环**：补水扣减后的新状态经 `CasCapable.compareAndSet`（期望值为旧值字符串）原子提交；CAS 失败（并发竞争）重读重试，最多 `MAX_CAS_ATTEMPTS = 8` 次，耗尽抛 `KvStoreException` 交由引擎按 `failOpen` 处置；
- 拒绝路径不回写状态——补水额由 `lastMillis` 推导，不写不丢；
- 键卫生：记录过期时间 = `now + 2 * windowMillis`，静默桶（长期无流量）自动回收，重新访问时按满桶起算。

**窥探**：`permits = 0` 与 `permits = 1` 走同一 CAS 路径，仅扣减 0 个令牌（状态会刷新 `lastMillis`，但额度不变）。

## 滑动窗口（sliding-window）

**语义**：任意连续 `windowMillis` 区间内请求数 ≤ 阈值，精确滚动（非固定对齐）。窗口边缘的突发在下一时刻即可重新获得额度——上一个请求滑出窗口的瞬间，额度立即恢复。**所用 kv 原语与契约要点**（`ScoredWindowCapable.offer(key, Offer)`，一次原子「裁剪 → 计数 → 条件添加」）：

- 每个请求以到达时刻为成员 score 入窗，裁剪 `score <= now - windowMillis` 的过期成员（契约：score **等于** cutoff 的成员视为过期被裁剪，严格大于才存活）；
- 「裁剪后计数 + members 数量」超过 `maxCount`（即 `threshold`）时**不添加任何成员**并返回 `accepted = false`——全部或全无，无中间态；
- members 为空表示**窥探**：仅裁剪与计数，永不拒绝；
- 键 TTL = 窗口时长，每次成功操作（含窥探）刷新，清理零流量残留键——TTL 是键卫生手段，与按 score 裁剪是两套独立机制；
- 成员 id 为 `nowMillis-hexRandom-i` 随机串，同一请求的多个许可各自唯一。

**内存上界定理**：窗口成员数恒不超过 `maxCount`（超限即整体拒绝、不添加），因此**存储内存 = 键数 × threshold** ，与流量速率无关。估算示例：10 万用户 × 阈值 5 × 每成员约 40 字节 ≈ 20 MB，可控。阈值下调无需迁移数据——窗口随成员自然滑出逐步排干。**窥探**：`permits = 0` 提交空 members，裁决改为 `count < threshold`（判断下一个单许可能否通过）。

## 历史窗口（history-window）

**语义**：epoch 对齐的固定窗口，计数来源是**调用方携带的历史时间戳列表**（经规则 `config.path` 点路径从检查上下文提取），服务端零存储、无状态，是唯一不解析 `store` 配置的算法。

- **epoch 对齐**：`windowStart = (now / windowMillis) * windowMillis`，窗口边界落在 `windowMillis` 的整数倍时刻；`now` 进入下一个 `windowStart` 周期后计数自然归零，无需任何清理动作；
- **未来时间戳计入当前窗口**：历史中 `ts >= windowStart` 的条目全部计入——客户端时钟超前的记录不放大额度，统一计入当前窗口消耗，杜绝「把请求记到未来窗口里腾额度」的口径漏洞；
- 裁决：`count + permits <= threshold` 放行；
- `config.path` 点路径导航：`a.b.0.c` 形式，Map 按键取值、List 按数字下标取值、Bean 读公有 getter；**默认** `history`——调用方将历史置于约定属性下即可零配置（`config` 整体可省）；路径缺失或终点非 List 视为空历史（空历史 = 无约束放行）；列表元素仅支持 `Number` 与 `Date`（转 epoch 毫秒），其余元素跳过；
- `decisionTimeMillis` 供客户端回填记录，保证双方时钟基准一致（协作协议详见[快速开始 · 推荐场景案例](quick-start.md#推荐场景完整案例app-客户端推荐频控)）。

**信任边界**：历史由调用方携带、天然可伪造。本算法是**合作式限流**（客户端自我节流），不能作为服务端防刷手段；对抗性场景使用服务端状态的 `fixed-window` / `sliding-window`。

## failOpen / failClosed

存储故障（`KvStoreException`，含令牌桶 CAS 竞争耗尽）由引擎统一捕获，按**规则级** `failOpen` 字段处置：

| 配置 | 行为 | 结果 |
| :--- | :--- | :--- |
| `failOpen = true`（默认） | 记 warn 后**视为该条规则通过**，继续执行后续规则 | 该条以 reason=`PASS`、ruleId=故障规则计入（`remaining` / `retryAfterMillis` 为 `null`）；最终结果为最后一条通过规则 |
| `failOpen = false` | **立即拒绝**，不再执行后续规则（首拒即停） | reason=`STORE_ERROR`、ruleId 为故障规则 |

选择依据：配额类限流（超了也只是多放几个请求）通常 `failOpen=true`，保可用性；保护类限流（限流就是为了让下游活命，存储挂了更不能放行）应 `failOpen=false`。

## 结果字段对照

`RateLimitResult` 中 `remaining` 与 `retryAfterMillis` 由算法**尽力提供**，无法精确计算时为 `null`：

| 场景 | `allowed` | `ruleId` | `remaining` | `retryAfterMillis` |
| :--- | :--- | :--- | :--- | :--- |
| 无规则放行 | `true` | `null` | `null` | `null` |
| fixed-window 通过/拒绝 | 按 `n <= threshold` | 本规则 | `max(0, threshold - n)`，恒有值 | 恒 `null`（浮窗无法精确给出） |
| token-bucket 通过 | `true` | 本规则 | 补水扣减后剩余令牌（取整） | `null`（无需等待） |
| token-bucket 拒绝 | `false` | 本规则 | 当前可用令牌（取整） | 有值：`ceil(缺口 / 速率)`，即凑够许可所需等待 |
| sliding-window 通过/拒绝 | 按 `accepted`（窥探按 `count < threshold`） | 本规则 | `max(0, threshold - count)`，恒有值 | 最老成员滑出窗口还需 `max(0, oldestScore + windowMillis - now)` 毫秒；窗口为空（一般意味着 `permits` 大于阈值）时为 `null` |
| history-window 通过/拒绝 | 按 `count + permits <= threshold` | 本规则 | `max(0, threshold - count)`，恒有值 | 恒有值：当前对齐窗口剩余时间 `windowStart + windowMillis - now` |
| 存储故障且 `failOpen=false` | `false` | 故障规则 | `null` | `null` |
| 存储故障且 `failOpen=true`（该条） | `true` | 故障规则（继续后续规则） | `null` | `null` |

多规则场景：任一规则拒绝立即返回（`ruleId` 为拒绝规则）；全部通过返回**最后一条**通过规则的结果。`decisionTimeMillis` 恒有值（裁决时刻 epoch 毫秒），`point` 由引擎补齐（算法不感知检查点）。

## 自定义算法

实现 `RateLimitAlgorithm` 三个方法并注册，规则中即可按名引用：

```java
public class MyAlgorithm implements RateLimitAlgorithm {

    public static final String KEY = "my-algorithm";

    @Override
    public String key() {
        return KEY;   // 规则 algorithm 字段取值
    }

    @Override
    public Class<?>[] requiredCapabilities() {
        // 声明所需 kv 能力接口，引擎在规则加载期校验存储齐备
        // 空数组 = 无状态算法，引擎不为其解析规则中的 store 配置（传入 store 为 null）
        return new Class<?>[]{CounterCapable.class};
    }

    @Override
    public RateLimitResult tryAcquire(RateLimitRule rule, KvStore store, String key,
                                      Object context, long nowMillis, int permits) {
        // 纯决策：key 已由引擎渲染为 {规则标识}.{渲染后的键}，算法自行组装 SpaceKey
        // 存储故障抛 KvStoreException，由引擎按规则 failOpen 处置
        ...
    }
}

// 注册（引擎构造后追加）
engine.algorithms().register(new MyAlgorithm());
```

算法应为无状态单例：并发共享、全部状态保存在 KvStore 中，能力经 `KvStores.capabilityOf` 协商获得。
