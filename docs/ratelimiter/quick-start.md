# 快速开始

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-ratelimiter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

限流存储按需引入 kv 后端（与 team4u-ratelimiter 无强绑定）：

- 内存存储（单测、单实例）：`team4u-kv-core` 自带 `InMemoryKvStore`，无需额外依赖
- Redis 存储（跨实例限流、滑动窗口）：引入 `team4u-kv-store-redis`，由业务项目提供 `StringRedisTemplate`
- JDBC 存储（固定窗口、令牌桶）：引入 `team4u-kv-store-jdbc`，由业务项目提供 `DataSource`
  （注意：`JdbcKvStore` 未实现 `ScoredWindowCapable`，`sliding-window` 规则不能绑定 JDBC 存储，加载期即报错）

## 配置键约定

一个限流**检查点**（point）对应一个配置键 `team4u.ratelimiter.{point}`，值为该检查点的**规则 JSON 数组**：

```properties
# 检查点 order.create：一条按用户维度的固定窗口规则
team4u.ratelimiter.order.create=[{"id":"per-user","algorithm":"fixed-window","windowMillis":60000,"threshold":5,"key":"${userId}"}]
```

规则经配置组件的 `ConfigDrivenRegistry` 加载，配置中心变更即热更新（先建新再替换、解析失败保留旧规则并记日志）。

### 规则字段

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `id` | String | 是 | 规则标识，同检查点内唯一且不允许包含 `:`（参与计数键组成） |
| `algorithm` | String | 是 | 算法名：`fixed-window` / `token-bucket` / `sliding-window` / `history-window`，或自定义注册名 |
| `store` | String | 否 | 命名存储（注册于 `RateLimitStores`）；空 = 引擎默认存储。无状态算法（`history-window`）不解析本字段 |
| `key` | String | 否 | 计数键模板，支持 `${variable}` 占位符（变量取自检查上下文）；空 = 以检查点为静态键，全检查点共享额度。不允许包含 `:` |
| `priority` | int | 否 | 优先级，**越小越先执行**（与策略组件 ContextPolicy 约定一致：越小优先级越高）；默认 `0`。同优先级保持配置顺序（稳定排序） |
| `windowMillis` | long | 是 | 窗口时长（毫秒），必须 > 0。语义随算法：fixed-window 计数窗口、token-bucket 注满一桶时间、sliding-window 滚动窗口长度、history-window 对齐窗口长度 |
| `threshold` | long | 是 | 阈值，必须 > 0。fixed-window/sliding-window/history-window 为窗口内请求数上限；token-bucket 为桶容量 |
| `failOpen` | boolean | 否 | 存储故障时是否放行，默认 `true`。`true` = 故障开放（记 warn、该条视为通过继续）；`false` = 故障关闭（立即拒绝，reason=`STORE_ERROR`） |
| `config` | Object | 条件 | 算法私有配置，形态由算法声明（`RateLimitAlgorithm#configType()`），加载期反序列化为类型化实例并校验；算法未声明配置类型时禁止携带（加载期报错）。当前仅 `history-window` 使用：`{"path": "..."}` 指定历史时间戳在上下文中的点路径，缺省取 `history` 属性 |

全算法共享的参数（`windowMillis`/`threshold`/`failOpen` 等）保留在规则模型；单算法专属参数放各自的 `config`——新增算法的私有参数不触碰通用规则模型。

同一检查点配多条规则即组成规则链：按 `priority` 升序依次执行（越小优先级越高）、首拒即停。

```properties
# 规则链示例：先按用户维度卡 5 次/分钟，再按全局维度兜底 1000 次/分钟
team4u.ratelimiter.order.create=[\
  {"id":"per-user","algorithm":"fixed-window","windowMillis":60000,"threshold":5,"key":"${userId}","priority":10},\
  {"id":"global","algorithm":"fixed-window","windowMillis":60000,"threshold":1000,"priority":0}\
]
```

## 编程式接入

直接构造引擎，适合自持生命周期的场景（时钟可注入供测试虚拟推进）：

```java
RateLimitEngine engine = new RateLimitEngine(configManager, new InMemoryKvStore());

// 检查并采集裁决结果（1 个许可）
RateLimitResult result = engine.acquire("order.create",
        Collections.singletonMap("userId", "u1"));

// 便捷入口：仅返回是否放行
boolean allowed = engine.tryAcquire("order.create",
        Collections.singletonMap("userId", "u1"));

// 多许可（如批量操作一次占 3 个额度）；permits = 0 为窥探：仅计数不占用
RateLimitResult peeked = engine.acquire("order.create", context, 0);

// 自定义算法注册后即可在规则中按名引用
engine.algorithms().register(new MyAlgorithm());

engine.destroy();   // 释放配置监听
```

更多场景使用静态门面 `RateLimiters`（内部持有全局引擎，`init` 显式初始化，未 init 时首次调用以 `ConfigManager.global()` + 内存存储懒加载）：

```java
// 初始化（也可以注入时钟：init(configManager, store, clock)）
RateLimiters.init(configManager, kvStore);

// acquire：放行返回裁决结果，拒绝抛 RateLimitException（携带完整裁决）
RateLimitResult result = RateLimiters.acquire("order.create", context);

// tryAcquire：仅返回是否放行，不抛限流异常
boolean allowed = RateLimiters.tryAcquire("order.create", context);

RateLimiters.destroy();   // 复位引用，供测试隔离
```

### 裁决结果字段

```java
RateLimitResult result = RateLimiters.acquire("order.create", context);

result.isAllowed();            // 是否放行
result.getPoint();             // 检查点标识
result.getRuleId();            // 裁决规则：拒绝时为触发拒绝的规则；全部通过时为最后一条通过的规则；无规则时为 null
result.getRemaining();         // 剩余额度（窗口内还能通过的请求数）；无法精确计算时为 null
result.getRetryAfterMillis();  // 建议重试等待毫秒数；无意义（如固定浮窗）或无需等待时为 null
result.getDecisionTimeMillis();// 裁决时刻（epoch 毫秒）；history-window 场景供客户端回填记录
result.getReason();            // 裁决原因：NO_RULE / PASS / THRESHOLD / STORE_ERROR

// 拒绝时从异常提取（acquire 入口）
try {
    RateLimiters.acquire("order.create", context);
} catch (RateLimitException e) {
    RateLimitResult denied = e.getResult();
}
```

各字段在不同算法下的取值差异（remaining / retryAfter 何时有值）见[算法详解](algorithms.md#结果字段对照)。

## 注解接入

方法上标注 `@RateLimit`，方法参数（按参数名）自动组装为检查上下文，供规则键模板渲染：

```java
public interface OrderService {

    @RateLimit("order.create")                            // value 简写：拒绝时抛 RateLimitException
    String create(String userId, String orderId);

    @RateLimit(point = "report.export", permits = 3)      // 一次导出占 3 个额度
    byte[] export(String userId);

    @RateLimit(point = "report.run", reject = RateLimitReject.NULL_VALUE)  // 拒绝返回 null
    String run();
}
```

`value` 是 `point` 的简写别名，二者至少设置一个、同时设置时必须一致；只关心检查点时推荐 `@RateLimit("order.create")` 简写。

要求类编译时保留参数名（项目已默认开启 `-parameters`），键模板 `${userId}` 才能取到参数值。

### 非 Spring 环境：手动代理

```java
OrderService proxied = RateLimitProxyFactory.proxy(new OrderServiceImpl());

// 目标为接口实现时推荐指定接口类型（JDK 代理，避免 ByteBuddy 子类代理的开销）
OrderService proxied2 = RateLimitProxyFactory.proxy(new OrderServiceImpl(), OrderService.class);
```

注解可标注在实现方法或接口方法上（解析沿「方法 → 目标类同名方法 → 接口层次」查找）；`RateLimitReject.NULL_VALUE` 拒绝时对象类型返回 null、基本类型返回默认值、void 方法直接拦截不执行。

### Spring 环境：自动代理

配置类加 `@EnableRateLimit`，容器中含 `@RateLimit` 方法的 Bean 自动包装为限流代理：

```java
@Configuration
@EnableRateLimit
public class RateLimitConfig {

    private final ConfigManager configManager;
    private final StringRedisTemplate redisTemplate;

    public RateLimitConfig(ConfigManager configManager, StringRedisTemplate redisTemplate) {
        this.configManager = configManager;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct   // javax.annotation.PostConstruct
    public void initRateLimit() {
        // 引擎由 RateLimiters 静态门面持有；init 后注解代理自动生效
        RateLimiters.init(configManager, new RedisKvStore(redisTemplate));
    }
}
```

> 注解代理经 `RateLimiters` 全局门面取引擎（不 init 则懒加载默认引擎：全局配置 + 内存存储）；final 类无法代理时记 warn 并跳过（返回原 Bean，不阻断启动）。

## 命名存储注册

一套规则、多存储分工：按名注册存储，规则中以 `store` 字段引用（同名重新注册即热更新）：

```java
// 全局注册表
RateLimitStores.global().register("main", new JdbcKvStore(dataSource));
RateLimitStores.global().register("hot", new RedisKvStore(stringRedisTemplate));

// 引擎默认存储为 main；规则可按名切换到 hot
RateLimiters.init(configManager, RateLimitStores.global().resolve("main"));
```

```properties
# 高频检查点走 Redis
team4u.ratelimiter.recommend.feed=[{"id":"per-user","algorithm":"sliding-window","store":"hot","windowMillis":60000,"threshold":5,"key":"${userId}"}]
# 其余规则不配置 store，走默认存储
team4u.ratelimiter.order.create=[{"id":"per-user","algorithm":"fixed-window","windowMillis":60000,"threshold":5,"key":"${userId}"}]
```

## 推荐场景完整案例：APP 客户端推荐频控

场景：APP 首页推荐流要求「每个用户每分钟最多主动刷新 5 次」。特点——用户量大、单个用户频控价值有限、服务端不想为每次刷推荐落计数状态。用 `history-window`：状态由客户端携带，服务端零存储。

**服务端**规则（无状态算法，不绑定任何存储）：

```properties
# 检查点 recommend.feed：epoch 对齐的 60 秒固定窗口，阈值 5
# 历史置于约定属性 history 下即可零配置（不在此字段也可用 config.path 指定点路径）
team4u.ratelimiter.recommend.feed=[{"id":"client-history","algorithm":"history-window","windowMillis":60000,"threshold":5}]
```

**服务端**检查：

```java
// 请求体携带客户端本地记录的请求历史（时间戳毫秒列表）
Map<String, Object> request = ...;   // 如 {"history": [1755900000000, 1755900015000], ...}

try {
    RateLimitResult result = RateLimiters.acquire("recommend.feed", request);
    // 协作协议：把服务端裁决时刻回填给客户端，作为双方一致的时钟基准
    long decisionTime = result.getDecisionTimeMillis();
    return renderFeed(request, decisionTime);
} catch (RateLimitException e) {
    // e.getResult().getRetryAfterMillis()：当前窗口剩余时间，可转成 UI 提示
    throw new TooManyRequestsException(e.getResult().getRetryAfterMillis());
}
```

**客户端**协作协议：

```text
1. 本地维护请求历史列表 history（epoch 毫秒），每次发推荐请求时随请求体带上；
2. 请求放行后，把响应中的 decisionTimeMillis 追加进 history；
   （用服务端时刻而非本地时钟，消除客户端时钟偏差；客户端时钟超前的记录
    也会被服务端计入当前窗口，不会放大额度）
3. history 中超出对齐窗口的旧记录由客户端自行裁剪（服务端只统计当前窗口）。
```

**信任边界**：历史由客户端携带、天然可伪造——本算法是**合作式限流**，约束的是「正常客户端的自我节流」（防误触、防轮询耗电），**不是防刷边界**。对抗性场景（薅羊毛、爬虫）请用服务端状态的 `fixed-window` / `sliding-window` 并按用户标识组键。

## 下一步

- 四算法语义、kv 原语契约、结果字段取值与故障行为：[算法详解](algorithms.md)
- kv 能力接口与存储后端支持矩阵：[键值存储组件](../kv/README.md)
