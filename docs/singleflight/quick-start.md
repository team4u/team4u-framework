# 快速开始

本文面向第一次接触 `team4u-singleflight` 的开发者：从引入依赖到跑通第一个场景，再到每个配置项「配置怎么写、代码怎么传参、结果是什么」。

阅读前只需要建立一个心智模型：**业务代码只负责声明「在哪合并」（point）和「怎么加载」（loader），其余一切——key 怎么取、结果缓存多久、竞争者怎么收场——都由一条 JSON 规则决定**。配置和代码的衔接点是**参数 Map**：编程式调用时你手工构造它，注解调用时框架自动把方法参数按参数名装进它。规则里的 `${productId}`、`$refresh` 都是从这个 Map 里取值。

---

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-singleflight</artifactId>
    <version>最新正式版本</version>
</dependency>
```

协调存储按需引入 kv 后端（与 team4u-singleflight 无强绑定）：

- 内存存储（单测、单实例）：`team4u-kv-core` 自带 `InMemoryKvStore`，无需额外依赖
- Redis 存储（跨实例回源合并）：引入 `team4u-kv-store-redis`，由业务项目提供 `StringRedisTemplate`
- JDBC 存储（跨实例回源合并）：引入 `team4u-kv-store-jdbc`，由业务项目提供 `DataSource`

> [!NOTE]
> 底层存储必须实现 `CasCapable`（内存 / Redis / JDBC 均支持）。传入 `TieredStore` / `ObservedStore` 等装饰存储时，引擎会解析到最内层真实存储——协调与结果缓存路径不经过装饰层，也**不会**因此获得 L1 加速。

---

## 最小可运行示例

先跑通一个最简单的场景：**缓存击穿合并**——同一个商品只回源一次，后续请求读缓存。

```java
package demo;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlights;

import java.util.Collections;
import java.util.Map;

public final class FirstSingleFlightDemo {
    public static void main(String[] args) {
        // 1. 写一条规则：point 为 product.detail，key 取参数里的 productId，结果缓存 60 秒
        InMemoryConfigSource source = new InMemoryConfigSource("demo", 0);
        source.put("team4u.singleflight.product.detail",
                "{\"id\":\"product.detail\",\"key\":\"${productId}\","
                        + "\"cacheTtlMillis\":60000}");
        ConfigManager configManager = ConfigManager.builder()
                .addSource(source).addWatcher(source).build();

        // 2. 初始化全局门面：这里用内存存储（生产环境换 Redis/JDBC 即可，业务代码不变）
        SingleFlights.init(configManager, new InMemoryKvStore());

        // 3. 第一次调用：loader 真正执行，结果写入缓存
        //    参数 Map 的 key 必须和规则里的 ${productId} 对得上
        Map<String, Object> arguments = Collections.singletonMap("productId", "p1");
        SingleFlightExecution.SingleFlightLoader<String> loadFromDb =
                () -> queryFromDatabase("p1");
        String first = SingleFlights.execute(SingleFlightExecution.of(
                "product.detail", arguments, String.class, loadFromDb));

        // 4. 第二次调用：同 key 命中缓存，loader 完全不执行
        SingleFlightExecution.SingleFlightLoader<String> neverRuns =
                () -> "must not execute";
        String second = SingleFlights.execute(SingleFlightExecution.of(
                "product.detail", arguments, String.class, neverRuns));

        System.out.println(first);   // product:p1
        System.out.println(second);  // product:p1（来自缓存，loader 没跑）

        SingleFlights.destroy();
    }

    private static String queryFromDatabase(String productId) {
        return "product:" + productId;   // 想象这里是一次昂贵的数据库查询
    }
}
```

你应该看到：

```text
product:p1
product:p1
```

注意代码和配置的对应关系：

| 规则里的写法 | 代码里的对应 |
| :--- | :--- |
| `"id":"product.detail"` | `execute` 的第一个参数 `"product.detail"`（必须完全一致） |
| `"key":"${productId}"` | 参数 Map 里的 `"productId"` 键（注解方式则是方法参数名） |
| `cacheTtlMillis:60000` | 无需代码配合——第二次调用自动命中缓存 |
| loader | `() -> queryFromDatabase("p1")`——只有抢到执行权的调用者会真正调用它 |

---

## 两种接入方式

### 方式一：编程式（推荐先掌握）

直接构造 `SingleFlightExecution` 并执行。参数 Map 是你手工构造的，适合上下文不完全等于方法参数的场景（比如要塞入请求头、灰度标记等额外变量）：

```java
Map<String, Object> arguments = new HashMap<>();
arguments.put("productId", productId);   // 供 ${productId} 与 $productId 使用
arguments.put("refresh", forceRefresh);  // 供 skipWhen: "$refresh == true" 使用

SingleFlightExecution.SingleFlightLoader<Product> loader =
        () -> productMapper.selectById(productId);

Product result = SingleFlights.execute(SingleFlightExecution.of(
        "product.detail",          // point，对应配置键 team4u.singleflight.product.detail
        arguments,                 // 参数上下文：key 模板和条件表达式从这里取值
        Product.class,             // 返回类型（等待者按它反序列化结果）
        loader));                  // 加载函数：只有真正执行的那次调用会运行它
```

直接持有引擎时自行管理生命周期（测试可注入 `Clock` 虚拟推进租约与 TTL）：

```java
SingleFlightEngine engine = new SingleFlightEngine(configManager, new InMemoryKvStore());
// 用法与 SingleFlights.execute 完全一致
engine.close();   // 释放配置监听
```

### 方式二：注解式（业务代码零侵入）

```java
public interface ProductService {

    @SingleFlight("product.detail")
    Product detail(String productId);
}
```

注解方式下**参数 Map 由框架自动组装**：方法参数名 → 参数值。所以方法声明 `detail(String productId)` 就等价于编程式的 `{"productId": productId}`，规则里的 `${productId}` 自然取到值。

要求类编译时保留参数名（项目已默认开启 `-parameters`），否则框架拿不到参数名，代理创建期即失败。

### 泛型返回不要直接用 Class

`List<User>` 这类泛型返回必须提供精确类型（`Class` 会丢失元素类型），用 `TypeReference` 子类：

```java
public static final class UserList extends TypeReference<List<User>> {
}

SingleFlightExecution.SingleFlightLoader<List<User>> loader =
        () -> queryUsers(ids);
List<User> users = SingleFlights.execute(SingleFlightExecution.of(
        "user.byIds",
        Collections.singletonMap("ids", ids),
        new UserList(),          // 而不是 List.class
        loader));
```

> [!NOTE]
> loader 的结果会 JSON 序列化后写入 kv，等待者再按声明的返回类型反序列化。类型不精确时，`List<User>` 会退化成 `List<Map>`，错误在调用方才暴露。注解方式不受此影响——代理自动携带 `method.getGenericReturnType()`。

---

## 逐场景配置指南

以下每个场景都给出「规则 + 调用代码 + 实际行为」，可以直接复制改造。

### 场景一：防击穿合并（WAIT）

**问题**：缓存到期瞬间 2000 个请求同时回源数据库。

```properties
team4u.singleflight.product.detail={\
  "id":"product.detail",\
  "key":"${productId}",\
  "contention":"WAIT",\
  "cacheEnabled":true,\
  "cacheTtlMillis":600000,\
  "waitTimeoutMillis":5000,\
  "pollIntervalMillis":50\
}
```

```java
// 调用方代码没有任何特殊之处——2000 个线程并发调用下面这段，
// 只有 1 个真正执行 loader，其余 1999 个等待后拿到同一个结果
Product p = SingleFlights.execute(SingleFlightExecution.of(
        "product.detail",
        Collections.singletonMap("productId", "p1"),
        Product.class,
        () -> productMapper.selectById("p1")));
```

行为：缓存未命中的瞬间，第一个抢到锁的调用者回源，其余等待（最多 `waitTimeoutMillis`）后拿到本次结果；回源抛异常时，等待者收到 `SingleFlightExecutionException`，执行者本人收到原始异常。

### 场景二：并发窗口互斥（FAIL_FAST）

**问题**：用户双击提交，同一订单的重复请求应该直接拒绝。

```properties
team4u.singleflight.order.submit={\
  "id":"order.submit",\
  "key":"${orderId}",\
  "contention":"FAIL_FAST",\
  "cacheEnabled":false,\
  "lockLeaseMillis":60000\
}
```

```java
try {
    SingleFlights.execute(SingleFlightExecution.of(
            "order.submit",
            Collections.singletonMap("orderId", orderId),
            OrderResult.class,
            () -> orderRepository.create(order)));
} catch (SingleFlightConflictException e) {   // 无堆栈，高并发下开销极低
    return OrderResult.fail("请勿重复提交");    // 竞争者在这里收场
}
```

行为：第一个请求执行期间（`lockLeaseMillis` 窗口内），同 `orderId` 的后续请求立即抛 `SingleFlightConflictException`，loader 不执行。

### 场景三：竞争时降级（FALLBACK 原生 JSON）

**问题**：推荐服务是弱依赖，合并窗口内的多余请求直接拿默认推荐，不值得等待。

```properties
team4u.singleflight.recommend.feed={\
  "id":"recommend.feed",\
  "key":"${userId}",\
  "contention":"FALLBACK",\
  "cacheEnabled":false,\
  "fallback":[{"id":"default","name":"默认推荐"}]\
}
```

```java
public static final class RecommendationList
        extends TypeReference<List<Recommendation>> {
}

// 竞争时 loader 不执行，fallback JSON 直接反序列化为 List<Recommendation>
List<Recommendation> feed = SingleFlights.execute(SingleFlightExecution.of(
        "recommend.feed",
        Collections.singletonMap("userId", "u1"),
        new RecommendationList(),
        () -> recommendClient.query("u1")));
```

行为：抢到锁的调用者正常回源；没抢到的**不抛异常**，直接拿到 `[{"id":"default","name":"默认推荐"}]` 反序列化后的对象。显式 `"fallback":null` 可以让对象类型返回 null，基本类型不允许。

### 场景四：不可缓存的结果（cacheWhen）

**问题**：查询结果为「处理中 / 空数据」时不该进缓存（否则 60 秒内用户都看到旧状态），但等待中的并发请求应该能拿到这次结果。

```properties
team4u.singleflight.order.snapshot={\
  "id":"order.snapshot",\
  "key":"${orderId}",\
  "cacheEnabled":true,\
  "cacheTtlMillis":60000,\
  "cacheWhen":"status == 'SUCCESS' && amount > 0",\
  "uncacheableTtlMillis":1000\
}
```

`cacheWhen` 的判定对象是 **loader 的返回值**——表达式里的属性直接写返回值的字段名：

```java
public class OrderSnapshot {
    private String status;    // cacheWhen 里的 status 就是取的这个字段
    private BigDecimal amount;
    // ...
}

OrderSnapshot snapshot = SingleFlights.execute(SingleFlightExecution.of(
        "order.snapshot",
        Collections.singletonMap("orderId", orderId),
        OrderSnapshot.class,
        () -> orderRepository.querySnapshot(orderId)));
```

| loader 返回 | 行为 |
| :--- | :--- |
| `status = "SUCCESS"` 且 `amount > 0` | 写 60 秒结果缓存，后续请求命中缓存 |
| `status = "PROCESSING"`（表达式为 false） | **不写缓存**；本次等待者仍可拿到结果（1 秒内），下一个新请求重新回源 |

表达式里也可以用 `$参数名` 引用入参，例如 `"cacheWhen":"status == 'SUCCESS' && $userId != 'preview-user'"`。

### 场景五：某些调用跳过合并（skipWhen）

**问题**：管理端「强制刷新」的调用不应该读缓存，必须真回源。

```properties
team4u.singleflight.product.detail={\
  "id":"product.detail",\
  "key":"${productId}",\
  "cacheTtlMillis":600000,\
  "skipWhen":"$refresh == true"\
}
```

`skipWhen` 的判定对象是**参数 Map**——必须用 `$参数名` 引用（注意和 `cacheWhen` 不同，那里属性直接写返回值字段）：

```java
// 编程式：把 refresh 塞进参数 Map
Map<String, Object> arguments = new HashMap<>();
arguments.put("productId", "p1");
arguments.put("refresh", true);

Product p = SingleFlights.execute(SingleFlightExecution.of(
        "product.detail", arguments, Product.class,
        () -> productMapper.selectById("p1")));
// refresh=true → 完全绕过：不读缓存、不抢锁、不写缓存，loader 直接执行
```

注解方式更自然——方法多声明一个参数即可：

```java
public interface ProductService {

    @SingleFlight("product.detail")
    Product detail(String productId, boolean refresh);   // refresh 自动进入参数 Map
}
```

`detail("p1", true)` 命中 skipWhen 直接回源；`detail("p1", false)` 走正常合并。

### 场景六：组件失败统一兑底（errorFallback）

**问题**：不同方法的失败返回值不一样，不想为每个方法写 Java 异常处理器；希望像 `fallback` 一样，按 point 配一份 JSON，组件失败时直接反序列化成返回值。

```properties
team4u.singleflight.order.submit={\
  "id":"order.submit",\
  "key":"${orderId}",\
  "contention":"FAIL_FAST",\
  "cacheEnabled":false,\
  "errorFallback":{"code":"BAD","message":"操作太频繁，请稍后再试"}\
}
```

```java
// 竞争时不抛 SingleFlightConflictException，而是拿到 errorFallback 反序列化后的对象
OrderResult result = SingleFlights.execute(SingleFlightExecution.of(
        "order.submit",
        Collections.singletonMap("orderId", orderId),
        OrderResult.class,
        () -> orderRepository.create(order)));
// result.getCode() == "BAD"——直接作为业务失败返回给上层
```

覆盖范围（三类组件异常，均不抛出、改为返回兑底值）：

| 异常场景 | 兑底后行为 |
| :--- | :--- |
| FAIL_FAST 锁竞争（原抛 `SingleFlightConflictException`） | 返回 `errorFallback` 转换值 |
| WAIT 等待超时（原抛 `SingleFlightTimeoutException`） | 返回 `errorFallback` 转换值 |
| 复用其他执行者的失败回执（原抛 `SingleFlightExecutionException`） | 返回 `errorFallback` 转换值 |

**不覆盖**的两类（设计如此，兑底会掩盖问题）：

- 配置错误（`SingleFlightConfigException`）：规则写错了必须暴露，静默兑底会把部署问题藏到线上才爆；
- loader 业务异常：不是组件异常，永远原样上抛给调用方。

与 `fallback` 的分工：`fallback` 管「正常竞争的降级数据」（如静态推荐列表），`errorFallback` 管「组件失败的兑底值」（如统一的失败包装）。两者可同时配置，互不影响。注解方式同样生效——兑底在引擎层收口，`@EnableSingleFlight` 自动代理无需任何改动。

### 完整对照表：条件表达式的取值上下文

| 表达式 | 判定对象 | 属性写法 | 示例 |
| :--- | :--- | :--- | :--- |
| `skipWhen` | 参数 Map | `$参数名` | `$refresh == true`、`$userId is null` |
| `cacheWhen` | loader 返回值 | 直接写返回值字段；`$参数名` 引用入参 | `status == 'SUCCESS'`、`amount > 0` |

语法即 [Criterion DSL](../criterion/criterion-syntax.md)：支持 `&&` / `||`、`between`、`in`、`is null` / `is empty`、正则等。

---

## 注解接入的工程细节

### 非 Spring 环境：手动代理

```java
ProductService proxied = SingleFlightProxyFactory.proxy(new ProductServiceImpl());

// 目标为接口实现时推荐指定接口类型（JDK 代理，避免 ByteBuddy 子类代理的开销）
ProductService proxied2 = SingleFlightProxyFactory.proxy(
        new ProductServiceImpl(), ProductService.class);
```

注解可标注在实现方法或接口方法上（解析沿「方法 → 目标类同名方法 → 接口层次」查找）。

### Spring 环境：自动代理

```java
@Configuration
@EnableSingleFlight
public class SingleFlightConfig {

    private final ConfigManager configManager;
    private final StringRedisTemplate redisTemplate;

    public SingleFlightConfig(ConfigManager configManager,
                              StringRedisTemplate redisTemplate) {
        this.configManager = configManager;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct   // javax.annotation.PostConstruct
    public void initSingleFlight() {
        SingleFlights.init(configManager, new RedisKvStore(redisTemplate));
    }
}
```

> 注解代理经 `SingleFlights` 全局门面取引擎（不 init 则懒加载默认引擎：全局配置 + 内存存储）；被 AOP 代理过的 Bean 不会被再包一层，请把 `@SingleFlight` 方法放在未被其他切面抢先代理的类型中。

### exceptionHandler：组件异常转换

handler 只接收 `SingleFlightException`（冲突 / 等待超时 / 存储故障等组件异常），loader 的原始业务异常不会被它吞掉：

```java
ProductService proxied = SingleFlightProxyFactory.proxy(
        new ProductServiceImpl(),
        ProductService.class,
        (method, returnType, throwable, arguments) -> {
            if (throwable instanceof SingleFlightConflictException) {
                return emptyProduct();   // 竞争时返回兜底商品，而不是抛异常
            }
            throw new IllegalStateException(throwable);
        });
```

`returnType` 是 `java.lang.reflect.Type`（泛型感知）。handler 返回 null 只允许对象类型；抛出的 checked 异常必须是原方法声明过的异常。

---

## 命名存储

一套规则、多存储分工：按名注册存储，规则中以 `store` 字段引用：

```java
SingleFlightStores.global().register("main", new JdbcKvStore(dataSource));
SingleFlightStores.global().register("hot", new RedisKvStore(stringRedisTemplate));

SingleFlights.init(configManager, SingleFlightStores.global().resolve("main"));
```

```properties
# 热点回源走 Redis；未配置 store 的规则走默认存储
team4u.singleflight.product.detail={"id":"product.detail","key":"${productId}","store":"hot","cacheTtlMillis":600000}
team4u.singleflight.report.build={"id":"report.build","key":"${reportId}","cacheEnabled":false,"contention":"FAIL_FAST"}
```

> 同一 `id` 的 `store` 名不能热切换：新规则编译失败、旧规则继续服务（避免执行到一半的会话被切到另一个存储找不到回执）。

---

## 下一步

- 组件设计、完整规则字段表、会话状态机与存储边界：[Singleflight 组件](README.md)
- kv 锁、CAS 能力和分层存储限制：[键值存储组件](../kv/README.md)
- `skipWhen` / `cacheWhen` 的完整表达式语法：[Criterion DSL 语法指南](../criterion/criterion-syntax.md)
