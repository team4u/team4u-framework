# 快速开始

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-singleflight</artifactId>
    <version>最新正式版本</version>
</dependency>
```

协调存储按需引入 kv 后端：

- 内存存储（单测、单实例）：`team4u-kv-core` 传递引入 `InMemoryKvStore`，无需额外依赖；
- Redis 存储（跨实例回源合并）：引入 `team4u-kv-store-redis`，由业务项目提供 `StringRedisTemplate`；
- JDBC 存储（跨实例回源合并）：引入 `team4u-kv-store-jdbc`，由业务项目提供 `DataSource`。

底层存储必须实现 `CasCapable`。传入 `TieredStore` / `ObservedStore` 等装饰存储时，引擎会解析到最内层真实存储，协调与结果缓存路径不会经过装饰层。

## 最小可运行示例

```java
package demo;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlights;

import java.util.Collections;

public final class FirstSingleFlightDemo {
    public static void main(String[] args) {
        // 1. 配置源：写入一条规则（生产环境接配置中心/数据库，见配置组件文档）
        InMemoryConfigSource source = new InMemoryConfigSource("demo", 0);
        source.put("team4u.singleflight.product.detail",
                "{\"id\":\"product.detail\",\"key\":\"${productId}\","
                        + "\"cacheTtlMillis\":60000}");
        ConfigManager configManager = ConfigManager.builder()
                .addSource(source).addWatcher(source).build();

        // 2. 初始化全局门面；同 key 并发调用时只有一个 loader 真正执行
        SingleFlights.init(configManager, new InMemoryKvStore());

        SingleFlightExecution.SingleFlightLoader<String> firstLoader =
                () -> loadFromDatabase("p1");
        String first = SingleFlights.execute(SingleFlightExecution.of(
                "product.detail",
                Collections.singletonMap("productId", "p1"),
                String.class,
                firstLoader));

        SingleFlightExecution.SingleFlightLoader<String> secondLoader =
                () -> "must not execute";   // cacheTtlMillis 内不会执行
        String second = SingleFlights.execute(SingleFlightExecution.of(
                "product.detail",
                Collections.singletonMap("productId", "p1"),
                String.class,
                secondLoader));
        System.out.println(first);
        System.out.println(second);

        SingleFlights.destroy();
    }

    private static String loadFromDatabase(String productId) {
        return "product:" + productId;
    }
}
```

输出：

```text
product:p1
product:p1
```

## 编程式接入

直接持有引擎时自行管理生命周期（测试可注入 `Clock` 控制存储侧租约与 TTL 时间）：

```java
SingleFlightEngine engine = new SingleFlightEngine(configManager, new InMemoryKvStore());

// 也可以使用静态门面 SingleFlights.init(configManager, store) / execute(execution)
String result = engine.execute(SingleFlightExecution.of(
        "product.detail",
        Collections.singletonMap("productId", "p1"),
        String.class,
        (SingleFlightExecution.SingleFlightLoader<String>) () -> loadFromDatabase("p1")));

engine.close();   // 释放配置监听
```

复杂泛型不要直接使用 `Class`：

```java
public static final class UserList extends TypeReference<List<User>> {
}

List<User> users = engine.execute(SingleFlightExecution.of(
        "user.byIds",
        Collections.singletonMap("ids", ids),
        new UserList(),
        () -> queryUsers(ids)));
```

## 注解式接入

注解值就是 point，方法参数按参数名组装为上下文：

```java
public interface ProductService {

    @SingleFlight("product.detail")
    Product detail(String productId);

    @SingleFlight("user.byIds")
    List<User> users(List<String> ids);
}
```

项目编译需保留参数名（框架父 POM 默认开启 `-parameters`）。

### 非 Spring 环境：手动代理

```java
ProductService proxied = SingleFlightProxyFactory.proxy(new ProductServiceImpl());

// 目标为接口实现时推荐显式指定接口类型
ProductService proxied2 = SingleFlightProxyFactory.proxy(
        new ProductServiceImpl(), ProductService.class);
```

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

注解代理经 `SingleFlights` 全局门面取引擎；不 `init` 时会懒加载默认引擎（全局配置 + 内存存储）。被 AOP 代理过的 Bean 不会被 `@EnableSingleFlight` 再包一层，请把 `@SingleFlight` 方法放在未被其他切面抢先代理的类型中。

### exceptionHandler：组件异常转换

handler 只接收 `SingleFlightException`，loader 的原始业务异常不会被它吞掉：

```java
ProductService proxied = SingleFlightProxyFactory.proxy(
        new ProductServiceImpl(),
        ProductService.class,
        (method, returnType, throwable, arguments) -> {
            if ("detail".equals(method.getName())
                    && throwable instanceof SingleFlightConflictException) {
                return emptyProduct();
            }
            throw new IllegalStateException(throwable);
        });
```

辅助方法按业务返回值实现：

```java
private Product emptyProduct() {
    return new Product("empty", "empty");
}
```

`returnType` 是 `java.lang.reflect.Type`。如果需要 `Class`，应先判断 `instanceof Class` 或使用类型工具，不要直接强转泛型类型。handler 返回 null 只允许对象类型；返回值必须能赋给原方法返回类型；抛出的 checked 异常必须是原方法声明过的异常。

## 命名存储

一套规则可按 point 选择不同存储。注意：同一 `id` 的 `store` 名不能热切换，否则新规则编译失败、旧规则继续使用：

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

## 常见配置

### 防击穿合并：WAIT

```properties
team4u.singleflight.product.detail={\
  "id":"product.detail",\
  "key":"${productId}",\
  "contention":"WAIT",\
  "cacheEnabled":true,\
  "cacheTtlMillis":600000,\
  "lockLeaseMillis":30000,\
  "waitTimeoutMillis":5000,\
  "pollIntervalMillis":50,\
  "uncacheableTtlMillis":1000,\
  "failureTtlMillis":1000,\
  "onStoreFailure":"PASS_THROUGH"\
}
```

行为：缓存未命中时同 key 只有一个调用者回源；其他调用者等待并读取本次结果。回源失败时，等待者在 `failureTtlMillis` 内收到 `SingleFlightExecutionException`；本地执行者收到原始异常。

### 并发窗口互斥：FAIL_FAST

```properties
team4u.singleflight.report.build={\
  "id":"report.build",\
  "key":"${reportId}",\
  "contention":"FAIL_FAST",\
  "cacheEnabled":false,\
  "lockLeaseMillis":60000,\
  "onStoreFailure":"FAIL_CLOSED"\
}
```

行为：已有执行者持有同一 `reportId` 时，后来者立即收到 `SingleFlightConflictException`，不会等待、不会重复执行。适合任务防重和并发窗口互斥。

### 竞争时降级：FALLBACK 原生 JSON

```properties
team4u.singleflight.recommend.feed={\
  "id":"recommend.feed",\
  "key":"${userId}",\
  "contention":"FALLBACK",\
  "cacheEnabled":false,\
  "fallback":[{"id":"default","name":"默认推荐"}]\
}
```

Java 返回类型必须是匹配的集合类型，例如 `List<Recommendation>`：

```java
public static final class RecommendationList
        extends TypeReference<List<Recommendation>> {
}

SingleFlightExecution.SingleFlightLoader<List<Recommendation>> loader =
        () -> queryRecommendations("u1");
List<Recommendation> recommendations = SingleFlights.execute(
        SingleFlightExecution.of(
                "recommend.feed",
                Collections.singletonMap("userId", "u1"),
                new RecommendationList(),
                loader));
```
竞争时不会执行 loader，而是把 fallback 原生 JSON 反序列化成返回类型。显式 `"fallback":null` 可以让对象类型返回 null，基本类型不允许。

### 不可缓存结果

```properties
team4u.singleflight.order.snapshot={\
  "id":"order.snapshot",\
  "key":"${orderId}",\
  "cacheEnabled":true,\
  "cacheTtlMillis":60000,\
  "cacheWhen":"status == 'SUCCESS'",\
  "uncacheableTtlMillis":1000\
}
```

`cacheWhen` 以 loader 返回值为 Criterion actual：

- `status == 'SUCCESS'` 为 true：发布 `SUCCESS_CACHEABLE`，写 `cacheTtlMillis` 的结果缓存；
- 为 false：发布 `SUCCESS_NOT_CACHEABLE`，不写结果缓存，但等待者仍可在 `uncacheableTtlMillis` 内读取本次结果。

需要完全禁用结果缓存、只做同 key 合并时：

```properties
team4u.singleflight.order.snapshot={"id":"order.snapshot","key":"${orderId}","cacheEnabled":false}
```

## 下一步

- 组件设计、完整规则字段表、会话状态机与存储边界：[Singleflight 组件](README.md)
- kv 锁、CAS 能力和分层存储限制：[键值存储组件](../kv/README.md)
