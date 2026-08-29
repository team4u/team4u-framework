# 快速开始

本文介绍如何在项目中引入并使用 `team4u-singleflight` 回源合并组件。

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

下面这个例子可以在单进程内直接运行（仅依赖 `team4u-singleflight`）：

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

        // 2. 初始化全局门面：默认内存存储（行为与 Redis 后端一致，同一套 kv 契约测试保证）
        SingleFlights.init(configManager, new InMemoryKvStore());

        // 3. 同 key 并发调用时只有一个 loader 真正执行
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

你应该看到：

```text
product:p1
product:p1
```

第二次调用的 loader 完全没有执行——它读到了第一次执行写入的结果缓存。

---

## 编程式接入

直接构造引擎，适合自持生命周期的场景（测试可注入 `Clock` 虚拟推进租约与 TTL）：

```java
SingleFlightEngine engine = new SingleFlightEngine(configManager, new InMemoryKvStore());

SingleFlightExecution.SingleFlightLoader<String> loader =
        () -> loadFromDatabase("p1");
String result = engine.execute(SingleFlightExecution.of(
        "product.detail",
        Collections.singletonMap("productId", "p1"),
        String.class,
        loader));

engine.close();   // 释放配置监听
```

更多场景使用静态门面 `SingleFlights`（内部持有全局引擎，`init` 显式初始化，未 init 时首次调用以 `ConfigManager.global()` + 内存存储懒加载）：

```java
// 初始化（也可以注入时钟：init(configManager, store, clock)）
SingleFlights.init(configManager, kvStore);

SingleFlightExecution.SingleFlightLoader<String> loader = () -> loadFromDatabase("p1");
String result = SingleFlights.execute(SingleFlightExecution.of(
        "product.detail",
        Collections.singletonMap("productId", "p1"),
        String.class,
        loader));

SingleFlights.destroy();   // 复位引用，供测试隔离
```

### 泛型返回不要直接用 Class

`List<User>` 这类泛型返回必须提供精确类型（`Class` 会丢失元素类型），用 `TypeReference` 子类：

```java
public static final class UserList extends TypeReference<List<User>> {
}

SingleFlightExecution.SingleFlightLoader<List<User>> loader =
        () -> queryUsers(ids);
List<User> users = engine.execute(SingleFlightExecution.of(
        "user.byIds",
        Collections.singletonMap("ids", ids),
        new UserList(),
        loader));
```

> [!NOTE]
> loader 的结果会 JSON 序列化后写入 kv，等待者再按声明的返回类型反序列化。类型不精确时，`List<User>` 会退化成 `List<Map>`，错误在调用方才暴露。

---

## 注解式接入

方法上标注 `@SingleFlight`，注解值就是 point，方法参数按参数名自动组装为上下文（供 key 模板与 `skipWhen` 使用）：

```java
public interface ProductService {

    @SingleFlight("product.detail")
    Product detail(String productId);

    @SingleFlight("user.byIds")
    List<User> users(List<String> ids);
}
```

要求类编译时保留参数名（项目已默认开启 `-parameters`），key 模板 `${productId}` 才能取到参数值。

### 非 Spring 环境：手动代理

```java
ProductService proxied = SingleFlightProxyFactory.proxy(new ProductServiceImpl());

// 目标为接口实现时推荐指定接口类型（JDK 代理，避免 ByteBuddy 子类代理的开销）
ProductService proxied2 = SingleFlightProxyFactory.proxy(
        new ProductServiceImpl(), ProductService.class);
```

注解可标注在实现方法或接口方法上（解析沿「方法 → 目标类同名方法 → 接口层次」查找）。

### Spring 环境：自动代理

配置类加 `@EnableSingleFlight`，容器中含 `@SingleFlight` 方法的 Bean 自动包装为代理：

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
        // 引擎由 SingleFlights 静态门面持有；init 后注解代理自动生效
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

辅助方法按业务返回值实现：

```java
private Product emptyProduct() {
    return new Product("empty", "empty");
}
```

`returnType` 是 `java.lang.reflect.Type`（泛型感知）。如果需要 `Class`，应先判断 `instanceof Class`，不要直接强转。handler 返回 null 只允许对象类型；抛出的 checked 异常必须是原方法声明过的异常。

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

行为：缓存未命中时同 key 只有一个调用者回源；其他调用者等待并读取本次结果。回源失败时，等待者在 `failureTtlMillis` 内收到 `SingleFlightExecutionException`，本地执行者收到原始异常。

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

行为：已有执行者持有同一 `reportId` 时，后来者立即收到 `SingleFlightConflictException`（无堆栈、开销极低），不会等待、不会重复执行。适合任务防重和并发窗口互斥。

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

`fallback` 是原生 JSON（不是转义字符串）。竞争时不会执行 loader，而是把这份 JSON 反序列化成返回类型：

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

显式 `"fallback":null` 可以让对象类型返回 null，基本类型不允许。

### 不可缓存结果

「空结果不缓存，但等待者能拿到这次结果」：

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

`cacheWhen` 以 loader 返回值为判定对象（Criterion 语法，属性直接写返回值字段）：

- `status == 'SUCCESS'` 为 true：写 `cacheTtlMillis` 的结果缓存，后续请求命中缓存；
- 为 false（如空单、处理中）：**不写结果缓存**，但等待者仍可在 `uncacheableTtlMillis` 内读到本次结果——下一个新请求会重新回源。

需要完全禁用结果缓存、只做同 key 合并时：

```properties
team4u.singleflight.order.snapshot={"id":"order.snapshot","key":"${orderId}","cacheEnabled":false}
```

### 跳过协调

某些调用不希望参与合并（如管理端强制刷新），用 `skipWhen`（以参数 Map 为判定对象，`$参数名` 引用参数）：

```properties
team4u.singleflight.product.detail={\
  "id":"product.detail",\
  "key":"${productId}",\
  "cacheTtlMillis":600000,\
  "skipWhen":"$refresh == true"\
}
```

`refresh=true` 的调用直接执行 loader，不抢锁、不读缓存、不写缓存。

---

## 下一步

- 组件设计、完整规则字段表、会话状态机与存储边界：[Singleflight 组件](README.md)
- kv 锁、CAS 能力和分层存储限制：[键值存储组件](../kv/README.md)
