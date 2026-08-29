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

六大典型场景（防击穿 WAIT / 互斥 FAIL_FAST / 降级 FALLBACK / 不可缓存 cacheWhen / 跳过 skipWhen / 失败兑底 errorFallback）的「规则 + 代码 + 行为」完整示例见[场景指南](scenarios.md)。

几个关键速查：

| 需求 | 字段 |
| :--- | :--- |
| 没抢到锁的请求怎么收场 | `contention`：WAIT 等待 / FAIL_FAST 报错 / FALLBACK 降级 |
| 什么结果不进缓存 | `cacheWhen`：表达式为 false 时不写缓存，等待者仍可读本次结果 |
| 哪些调用完全绕过合并 | `skipWhen`：命中则直接执行 loader，不抢锁不读写缓存 |
| 组件失败时返回兑底值 | `errorFallback`：竞争 / 超时 / 失败回执三类异常不抛，按返回类型反序列化此 JSON |

> 条件表达式的取值上下文：`skipWhen` 判参数 Map 用 `$参数名`；`cacheWhen` 判返回值直接写字段名。完整对照与示例见[场景指南](scenarios.md#条件表达式取值上下文对照)，表达式语法即 [Criterion DSL](../criterion/criterion-syntax.md)。

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

- 六大场景的规则 + 代码 + 行为完整示例：[场景指南](scenarios.md)
- 组件设计、完整规则字段表：[Singleflight 组件](README.md)
- 会话状态机、崩溃接管、errorFallback 优先级、存储边界：[会话与失败处理](session.md)
- kv 锁、CAS 能力和分层存储限制：[键值存储组件](../kv/README.md)
- `skipWhen` / `cacheWhen` 的完整表达式语法：[Criterion DSL 语法指南](../criterion/criterion-syntax.md)
