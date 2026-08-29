# 类型化键空间与命名存储

本文档位于 `team4u-kv-space` 模块（依赖 kv-core、policy、serializer-json）。裸用核心接口时，每个调用点都要重复两件事：键空间字符串（拼错不报错）和值类型（`get` 后自行反序列化）。`Space` 门面把「键空间 + 值类型 + 默认 TTL」绑定到一个对象上，之后读写只传业务键，值自动做 JSON 序列化/反序列化（复用 serializer 组件的 `JsonUtil`）。注册表读路径使用 Copy-On-Write 结构，避免热查找加锁：

```java
// 裸用核心接口
KvRecord record = kv.get(SpaceKey.of("user.session", "u1"));
Session session = record == null ? null : JsonUtil.toBean(record.getValue(), Session.class);

// 类型化门面
Session session = sessions.get("u1");   // 同样的事，一行
```

## 注册与使用

示例中的 `Session` 是一个普通 POJO（需要无参构造与 getter/setter）：

```java
public class Session {
    private String token;
    // getter/setter 略
}
```

## 注册与使用

```java
// 注册策略（Spaces.global() 为全局单例，也可 new Spaces() 独立实例）
Spaces.global().register(new SpacePolicy()
        .setName("user.session")            // 键空间名（唯一标识）
        .setValueType(Session.class)        // 值类型，默认 String
        .setDefaultTtlMillis(3600_000));    // 默认有效期，默认 0（永不过期）

// 构建类型化门面
Space<Session> sessions = Spaces.global().use("user.session", kvStore);

sessions.put("u1", new Session("token-abc"));            // 使用默认 TTL
Session session = sessions.get("u1");                    // 自动反序列化，缺失返回 null
sessions.put("u1", session, 1800_000);                   // 指定 TTL
sessions.expire("u1", 60_000);                           // 续期
sessions.remove("u1");                                   // 删除

// 幂等控制：另注册一个 String 值类型的键空间
Spaces.global().register(new SpacePolicy().setName("payment.idem"));
Space<String> idem = Spaces.global().use("payment.idem", kvStore);
boolean first = idem.putIfAbsent("order-1", "1", 24 * 3600_000L);
```

未注册的键空间在 `use` 时快速失败，避免拼写错误静默产生新空间。

**落库形态**：值以 JSON 字符串存储。`Space<Session>` 存 `{"token":"token-abc"}`；注意 `Space<String>` 存的是带引号的 JSON 字符串（`"1"`），取回时自动还原为 `1`——序列化细节由门面处理，业务无感知，但直接查库时会看到 JSON 形态。

## 策略热更新

`Spaces` 的注册表基于 policy 组件的 `KeyedPolicyRegistry`（Copy-On-Write，读路径无锁、低分配）。同名重新注册即覆盖：

```java
Spaces.global().register(new SpacePolicy()
        .setName("user.session")
        .setValueType(Session.class)
        .setDefaultTtlMillis(1800_000));   // 覆盖旧策略

Space<Session> after = Spaces.global().use("user.session", kvStore);
// 已构建的 Space 不受影响；新构建的 Space 使用新策略——与配置中心的快照热更语义一致
```

策略对象可由配置中心下发后重新注册（对象仅三个字段，天然适合 JSON 映射），实现 TTL 等行为的热调整。

## 设计边界

- 序列化固定走 `JsonUtil`（值以 JSON 字符串落库）；需要其他编码时直接使用核心接口；
- 值类型不填默认 `String.class`——上文的 `payment.idem` 空间就是用的默认值；
- 一个 `Space` 绑定一个存储：同一策略可对多个存储分别 `use`（如本地缓存一份、Redis 一份）；
- `Spaces.global()` 是全局单例，适合单体应用；多个独立模块互不干扰时各自 `new Spaces()` 隔离注册表；
- `Spaces` 不管理存储生命周期，`AutoCloseable` 责任在存储本身。

## 命名存储注册表（NamedKvStoreRegistry）

同一模块还提供命名 `KvStore` 注册表，供规则驱动的组件（id / ratelimiter / singleflight）按规则里的 `store` 名引用存储，实现「一套规则、多存储分工」（如默认走内存、热点走 Redis）：

```java
// 注册（同名重新注册即热更新，后注册者覆盖先注册者）
NamedKvStoreRegistry.global().register("main", new JdbcKvStore(dataSource));
NamedKvStoreRegistry.global().register("hot", new RedisKvStore(stringRedisTemplate));

// 按名取用（作为引擎/服务的默认存储，或由规则的 store 字段解析）
KvStore main = NamedKvStoreRegistry.global().get("main");
```

行为要点：

- FQCN 不变：`com.team4u.framework.kv.NamedKvStore` / `com.team4u.framework.kv.NamedKvStoreRegistry`；1.0 仅将它们从 kv-core 迁移到 `team4u-kv-space`，依赖 `team4u-kv-space` 即可继续使用；
- 注册表实现 policy 组件的 `KeyedPolicyRegistry`（Copy-On-Write，读路径无锁、低分配），同名重新注册即覆盖；
- 声明为 Spring Bean 后可经 policy 组件的自动装配机制批量注入容器内的 `NamedKvStore` Bean（见类 Javadoc）；
- 使用方文档：[序号生成](../id/quick-start.md#多存储分工)、[限流](../ratelimiter/quick-start.md)、[回源合并](../singleflight/quick-start.md)。
