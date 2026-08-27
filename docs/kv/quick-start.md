# 快速开始

## 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-kv-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

组件对 Spring 及各存储的依赖均为可选，按需引入 `team4u-kv-lock` / `team4u-kv-lifecycle` / `team4u-kv-store-jdbc` / `team4u-kv-store-redis` 等子模块。

## 最简用法：内存存储

内存实现零依赖，API 即核心接口，适合单测与单实例临时数据：

```java
package demo;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;

public final class FirstKvDemo {
    public static void main(String[] args) {
        KvStore kv = new InMemoryKvStore();
        SpaceKey key = SpaceKey.of("user.session", "u1");
        kv.put(key, KvRecord.of("token-abc", 3600_000, System.currentTimeMillis()), PutMode.SET);
        System.out.println(kv.get(key).getValue());   // 输出: token-abc
    }
}
```

下面的片段默认持有 `kv`（内存或任意后端）：

```java
SpaceKey key = SpaceKey.of("user.session", "u1");

// 写：值 + 有效期（毫秒），0 为永不过期
kv.put(key, KvRecord.of("token-abc", 3600_000, System.currentTimeMillis()), PutMode.SET);

// 读：不存在或已过期返回 null
String token = kv.get(key).getValue();

// 原子写：仅当键不存在时成功（SETNX 语义），已存在返回 false
boolean first = kv.put(
        SpaceKey.of("idem", "order-1001"),
        KvRecord.of("1", 24 * 3600_000, System.currentTimeMillis()),
        PutMode.IF_ABSENT);

// 删除 / 续期
kv.remove(key);
kv.expire(key, 60_000);
```

键空间（`user.session`、`idem`）实现多业务数据隔离，同一存储可承载多个键空间。

## 类型化门面：Space

固定键空间与值类型的场景，注册策略后按类型读写，值自动 JSON 序列化：

```java
Spaces.global().register(new SpacePolicy()
        .setName("user.session")          // 键空间名
        .setValueType(Session.class)      // 值类型
        .setDefaultTtlMillis(3600_000));  // 默认有效期

Space<Session> sessions = Spaces.global().use("user.session", kv);

sessions.put("u1", new Session("token-abc"));   // 使用默认 TTL
Session session = sessions.get("u1");           // 自动反序列化
sessions.remove("u1");
```

## 分层存储：TieredStore

L1 本地缓存 + L2 远程存储，读穿透回填、写直通：

```java
KvStore l2 = new JdbcKvStore(dataSource);

KvStore tiered = new TieredStore(
        l2,
        60_000,                                                   // L1 条目有效期
        new TieredStore.Config().setTombstoneTtlMillis(5_000));    // 删除墓碑

// API 与核心接口完全一致，业务无感知
tiered.put(key, KvRecord.of("v1"), PutMode.SET);
```

装饰器可自由组合，例如「观测 → 分层 → 重试 → Redis」：

```java
KvStore kv2 = new ObservedStore(                       // 审计日志、慢操作告警
        new TieredStore(                               // L1 缓存
                new RetryableStore(redisStore),        // 存储抖动重试
                30_000, new TieredStore.Config()));
```

## 分布式锁

底层存储实现 `CasCapable`（内存、JDBC、Redis 均支持）即可使用：

```java
KvLockManager lockManager = new KvLockManager(kvStore);

// acquire 为阻塞获取：超时抛受检异常 KvLockTimeoutException，外层方法需声明或捕获
try (KvLock lock = lockManager.acquire("report.daily", 30_000, 5_000)) {
    // 临界区：锁由后台线程自动续约，不会因超时被误放
    doGenerate();
}   // 自动释放：只删自己令牌持有的锁，绝不误删他人的锁
```

## 过期值源：Token 续期

「值怎么取、有效期怎么算」声明一次，取值统一走 `get()`：

```java
ExpiringValue<Token> wechatToken = ExpiringValue.<Token>builder(Token.class)
        .store(kvStore)
        .key("auth", "wechat_token")
        .loader(() -> wechatClient.getAccessToken())       // 怎么取
        .ttlOf(t -> t.getExpiresIn() * 1000L)              // 有效期怎么算
        .refreshAhead(600_000)                             // 过期前 10 分钟开始续期
        .scope(ExpiringValue.Scope.CLUSTER)                // 跨实例 singleflight
        .lockManager(lockManager)
        .build();

Token token = wechatToken.get();   // 未过期直接返回；到期自动加载；并发只加载一次
```

## 单元测试：零外部依赖

```java
// 示例为 JUnit 4 风格（与框架测试栈一致）
public class OrderServiceTest {

    private final TestKvContext kv = TestKvContext.create();

    @Test
    public void idempotentCallback() {
        OrderService service = new OrderService(kv.store());
        service.onPaymentCallback("o1001");
        service.onPaymentCallback("o1001");
        assertEquals(1, service.processedCount());
    }
}
```

`TestKvContext` 提供内存存储与虚拟时钟，`advanceSeconds(60)` 可精确推进时间验证 TTL 语义。

## 下一步

- 四操作契约与能力协商细节：[核心抽象](kv-store.md)
- 墓碑、负缓存与一致性边界：[分层存储](kv-tiered.md)
- 锁的心跳与 fencing 语义：[锁服务](kv-lock.md)
- 各场景完整案例：[常见案例](kv-sample.md)
