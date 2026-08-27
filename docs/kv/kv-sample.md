# 常见案例

## 会话缓存（类型化 + 默认 TTL）

```java
Spaces.global().register(new SpacePolicy()
        .setName("user.session")
        .setValueType(Session.class)
        .setDefaultTtlMillis(3600_000));

Space<Session> sessions = Spaces.global().use("user.session", redisStore);

// 登录：写入会话（默认 1 小时过期）
sessions.put(userId, new Session(userId, token));

// 校验 + 滑动续期：再次 put 即重置过期时间
Session session = sessions.get(userId);
if (session == null) {
    throw new UnauthorizedException();
}
sessions.put(userId, session.touch());

// 登出
sessions.remove(userId);
```

## 接口幂等控制（SETNX）

支付回调可能重复推送，同一订单号只处理一次：

```java
public void onPaymentCallback(String orderId) {
    boolean first = kv.put(
            SpaceKey.of("payment.callback", orderId),
            KvRecord.of("1", 24 * 3600_000, System.currentTimeMillis()),
            PutMode.IF_ABSENT);

    if (!first) {
        log.info("重复回调，跳过|orderId={}", orderId);
        return;
    }
    doPayment(orderId);   // 不释放，靠 TTL 自然失效，24 小时内仅处理一次
}
```

`IF_ABSENT` 的原子性由存储保证（唯一索引 / SETNX），并发回调下仅一个请求成功。

## 第三方 Token 续期（ExpiringValue + CLUSTER）

```java
ExpiringValue<Token> token = ExpiringValue.<Token>builder(Token.class)
        .store(redisStore)                            // 多实例共享
        .key("auth", "wechat_token")
        .loader(() -> wechatClient.getAccessToken())
        .ttlOf(t -> (t.getExpiresIn() - 300) * 1000L) // 预留 5 分钟余量
        .refreshAhead(600_000)                        // 过期前 10 分钟续期
        .scope(ExpiringValue.Scope.CLUSTER)           // 全局仅一个实例真正调第三方
        .lockManager(new KvLockManager(redisStore))
        .build();

// 业务侧只管取值：未过期零开销，进窗口自动续期，无值自动加载
String accessToken = token.get().getAccessToken();
```

与旧方案对比：不再需要定时任务兜底触发，多实例并发下不会重复刷新（既省配额又不会互相覆盖）。

## 定时任务防重（分布式锁）

日报任务多实例部署，调度同时触发所有实例，仅执行一次：

```java
try (KvLock lock = lockManager.tryAcquire("report.daily", 30_000)) {
    if (lock == null) {
        log.info("其他实例正在执行，跳过");
        return;
    }
    doGenerate();
}   // 结束即释放：下个调度周期允许再次执行
```

与「幂等控制」的区别：防重在任务结束后**主动释放**（下次调度可再执行）；幂等在整个窗口内**仅执行一次**（不释放，靠 TTL）。

心跳续约使长任务不再有「跑到一半锁过期被他人抢走」的误放问题——只要进程活着，租约就滚动；进程崩溃，租约最迟一个 lease 周期后自动释放。

## 等待异步处理结果（轮询订阅）

提交异步任务后，另一线程/实例完成时写入结果，调用方以订阅方式等待：

```java
// 提交方
kv.put(SpaceKey.of("task", "task_1001"), KvRecord.of("PENDING"), PutMode.SET);
executor.submit(new Task("task_1001"));

// 处理方：完成后写入结果空间
kv.put(SpaceKey.of("task.result", "task_1001"), KvRecord.of("SUCCESS"), PutMode.SET);

// 调用方：订阅结果空间（JDBC 存储用 PollingWatcher 降级轮询）
CountDownLatch done = new CountDownLatch(1);
try (PollingWatcher watcher = new PollingWatcher(jdbcStore, 200)) {
    try (AutoCloseable ignored = watcher.watch("task.result", event -> {
        if ("task_1001".equals(event.getKey().getKey())) {
            done.countDown();
        }
    })) {
        done.await(10, TimeUnit.SECONDS);
    }
}
```

内存存储直接用原生订阅（写入即分发、零延迟）：

```java
try (AutoCloseable ignored = memoryStore.watch("task.result", event -> ...)) { ... }
```

## 高频读保护（分层 + 负缓存）

热点配置读取 + 「不存在的键」防穿透：

```java
KvStore config = new TieredStore(
        jdbcStore,
        30_000,                                                   // L1 缓存 30 秒
        new TieredStore.Config()
                .setTombstoneTtlMillis(5_000)                     // 删除后 5 秒内不回源
                .setNegativeTtlMillis(2_000));                    // 不存在的键 2 秒内不穿透

String value = config.get(SpaceKey.of("app.config", "feature.x")) == null
        ? null : config.get(SpaceKey.of("app.config", "feature.x")).getValue();
```

## 单元测试（零外部依赖）

```java
public class OrderServiceTest {

    private final TestKvContext kv = TestKvContext.create();

    @Test
    public void idempotentCallback() {
        OrderService service = new OrderService(kv.store());
        service.onPaymentCallback("o1001");
        service.onPaymentCallback("o1001");
        assertEquals(1, service.processedCount());
    }

    @Test
    public void sessionExpires() {
        OrderService service = new OrderService(kv.store());
        service.createSession("u1");
        kv.advanceSeconds(3600);                 // 虚拟时间精确推进
        assertNull(service.findSession("u1"));   // 过期语义确定性验证，无需等待
    }
}
```

内存实现与生产实现跑同一套契约测试（见[契约测试](kv-test.md)），单测无需 Mock、无需容器。
