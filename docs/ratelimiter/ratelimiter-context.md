# 动态上下文与分层路径限流

在大型微服务架构中，单纯按单一字符串 Key 限流往往无法满足多维度、层级化（Hierarchical）的风控诉求。`team4u-ratelimiter` 提供了 **`ContextProperties`（动态属性提取）** 与 **`HistoryPaths`（分层路径限流）** 体系。

---

## 1. 动态上下文提取：`ContextProperties`

`ContextProperties` 允许从当前业务线程、RPC 请求头、HTTP Header 或 Spring Security 上下文中提取多维特征：

```java
import com.team4u.framework.ratelimiter.core.ContextProperties;

ContextProperties props = ContextProperties.create()
        .with("tenantId", "tenant_001")
        .with("userId", "user_98765")
        .with("ip", "192.168.1.100")
        .with("channel", "APP_IOS");

// 复合限流 Key 构建
String compositeKey = props.resolveKey("tenant:${tenantId}:user:${userId}");
// 产出: "tenant:tenant_001:user:user_98765"
```

---

## 2. 分层路径限流：`HistoryPaths`

`HistoryPaths` 支持树形分层路径的限流统计与继承：

```text
/
├── api (全局限额: 1000/s)
│   ├── pay (支付服务限额: 200/s)
│   │   ├── createOrder (下单接口: 50/s)
│   │   └── refund (退款接口: 10/s)
│   └── user (用户服务限额: 300/s)
```

```java
import com.team4u.framework.ratelimiter.core.HistoryPaths;

HistoryPaths paths = HistoryPaths.of("/api/pay/createOrder");

// 获取所有祖先层级路径
List<String> hierarchy = paths.hierarchy();
// ["/api", "/api/pay", "/api/pay/createOrder"]
```

### 多级联合判定
请求到达时，框架自动自底向上依次校验各级路径的配额；只要任一层级配额超限，请求立即被阻断，有效保护下游集群免受级联雪崩。

---

## 关联章节与进一步阅读

- 了解限流算法原理：[限流算法深度解析](ratelimiter-algorithms.md)
- 了解声明式注解与代理降级：[声明式注解与代理降级](ratelimiter-declarative.md)
- 查看高并发秒杀防刷案例：[限流实战案例与最佳实践](ratelimiter-sample.md)
