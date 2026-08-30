# 场景指南

每个场景都给出「规则 + 调用代码 + 实际行为」，可以直接复制改造。接入方式（编程式 / 注解式 / Spring）见[快速开始](quick-start.md)，字段完整定义见[组件概览](README.md)。

---

## 场景一：防击穿合并（WAIT）

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

## 场景二：并发窗口互斥（FAIL_FAST）

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

## 场景三：竞争时降级（FALLBACK 原生 JSON）

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

行为：抢到锁的调用者正常回源；没抢到的**不抛异常**，直接拿到 `[{"id":"default","name":"默认推荐"}]` 反序列化后的对象。显式 "fallback":null` 可以让对象类型返回 null，基本类型不允许。

## 场景四：不可缓存的结果（cacheWhen）

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
| `status = "SUCCESS" 且 `amount > 0` | 写 60 秒结果缓存，后续请求命中缓存 |
| `status = "PROCESSING"（表达式为 false） | **不写缓存**；本次等待者仍可拿到结果（1 秒内），下一个新请求重新回源 |

表达式里也可以用 `$参数名` 引用入参，例如 "cacheWhen":"status == 'SUCCESS' && $userId != 'preview-user'"。

## 场景五：某些调用跳过合并（skipWhen）

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

## 场景六：组件失败统一兑底（errorFallback）

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

与 `exceptionHandler` 的优先级关系见[会话与失败处理](session.md#errorfallback-与-exceptionhandler-的优先级)。

---

## 条件表达式取值上下文对照

| 表达式 | 判定对象 | 属性写法 | 示例 |
| :--- | :--- | :--- | :--- |
| `skipWhen` | 参数 Map | `$参数名` | `$refresh == true`、`$userId is null` |
| `cacheWhen` | loader 返回值 | 直接写返回值字段；`$参数名` 引用入参 | `status == 'SUCCESS'`、`amount > 0` |

语法即 [Criterion DSL](../criterion/criterion-syntax.md)：支持 `&&` / `||`、`between`、`in`、`is null` / `is empty`、正则等。
