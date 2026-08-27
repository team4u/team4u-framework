# 类型化键空间

`type` 字符串散落在调用点、值类型参数重复传递，是裸用核心接口的两个痛点。`Space` 门面把两者绑定到一个对象上，值自动 JSON 序列化（复用 serializer 组件的 `JsonUtil`）。

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
sessions.put("u1", session, 1800_000);                   // 指定 TTL
Session session = sessions.get("u1");                    // 自动反序列化，缺失返回 null

boolean first = sessions.putIfAbsent("order-1", "1", 24 * 3600_000L);  // 幂等控制
sessions.expire("u1", 60_000);                           // 续期
sessions.remove("u1");                                   // 删除
```

未注册的键空间在 `use` 时快速失败，避免拼写错误静默产生新空间。

## 策略热更新

`Spaces` 的注册表基于 policy 组件的 `KeyedPolicyRegistry`（Copy-On-Write，读路径无锁、零 GC）。同名重新注册即覆盖：

```java
Spaces.global().register(new SpacePolicy()
        .setName("user.session")
        .setValueType(Session.class)
        .setDefaultTtlMillis(1800_000));   // 覆盖旧策略

Space<Session> after = Spaces.global().use("user.session", kvStore);
// 已构建的 Space 不受影响；新构建的 Space 使用新策略——与配置中心的快照热更语义一致
```

策略对象可由配置中心下发后重新注册（对象仅四个字段，天然适合 JSON 映射），实现 TTL 等行为的热调整。

## 设计边界

- 序列化固定走 `JsonUtil`（值以 JSON 字符串落库）；需要其他编码时直接使用核心接口；
- 一个 `Space` 绑定一个存储：同一策略可对多个存储分别 `use`；
- `Spaces` 不管理存储生命周期，`AutoCloseable` 责任在存储本身。
