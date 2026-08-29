# 精准键值策略模式

精准键值策略模式适用于根据唯一路由标识（如支付渠道代码、指令代码、消息类型、业务动作代码）在 $O(1)$ 复杂度内精准定位目标策略对象的场景。

---

## 为什么不直接使用 `Map<String, Policy>`？

| 维度 | 原生 `ConcurrentHashMap<String, Policy>` | `KeyedPolicy` + `KeyedPolicyRegistry` |
| :--- | :--- | :--- |
| **自描述与内聚性** | Key 与 Policy 分离维护，策略对象在方法间传递时极易丢失身份标识 | 策略自身实现 `K key()`，身份与策略实体天然强绑定 |
| **高并发读取性能** | 原生 Map 遍历或读取仍存在哈希计算、分段节点巡检与并发开销 | 基于 **Copy-On-Write** 机制，写时更新不可变快照，读取操作无锁，直接返回缓存列表，不做拷贝 |
| **注册自动化** | 必须在启动类中手写大量的 `map.put("KEY", new Policy())` 样板代码 | 深度融合 `PolicyScanner` 与 Spring，支持包扫描、SPI 与容器 Bean 零配置自动装配 |
| **防御性校验** | 容易误注册不合规类型或 `null` 键，运行时难以及时发现 | 构造时绑定 `Class<P>`，注册时严格执行类型检查与非空断言，快速失败 |

---

## 核心接口与行为规范

### `KeyedPolicy<K>` 接口
```java
package com.team4u.framework.policy.api;

public interface KeyedPolicy<K> {
    /**
     * 当前策略绑定的唯一路由标识键
     */
    K key();
}
```

---

### `KeyedPolicyRegistry<K, P>` 注册表

#### 写入同步与不可变快照缓存
`KeyedPolicyRegistry` 内部维护了 `Map<K, P> policies` 索引以及一个只读的 `volatile List<P> unmodifiablePolicies` 缓存：
- 每次发生写操作（`register`, `addAll`, `unregister`, `unregisterIf`, `unregisterAll`）时，在同步块内完成底层 Map 更新后，立即构建一份全新的只读列表并更新缓存引用。
- `getPolicies()` 直接返回此 `volatile` 引用，高并发读取时不再逐次拷贝集合，减少临时集合对象与对应的 GC 压力。

#### 完整 API 规范

```java
public class KeyedPolicyRegistry<K, P extends KeyedPolicy<K>> implements PolicyRegistry<P> {

    // 构造器：必须传入策略接口类型的 Class 对象
    public KeyedPolicyRegistry(Class<?> policyClass);

    // 单个注册：若存在相同 key 则覆盖已有策略
    public synchronized void register(P policy);

    // 批量注册集合：自动过滤 null 元素
    public synchronized void addAll(Collection<? extends P> policies);

    // 合并另一个同类型 KeyedPolicyRegistry
    public synchronized void addAll(PolicyRegistry<? extends P> registry);

    // 精准 O(1) 检索
    public Optional<P> get(K key);

    // 注销指定策略实例
    public synchronized void unregister(P policy);

    // 条件函数式注销，返回成功移出的策略数量
    public synchronized int unregisterIf(Predicate<P> predicate);

    // 按实现类类型注销，返回成功移出的策略数量
    public int unregisterByType(Class<? extends P> policyClass);

    // 清空所有已注册策略
    public synchronized void unregisterAll();

    // 获取所有策略的只读快照列表 (无锁极速读取)
    public List<P> getPolicies();

    // 获取绑定的策略 Class 类型
    public Class<P> getPolicyClass();
}
```

---

## 防御性校验与异常体系 (`PolicyException`)

在调用 `register` 或 `addAll` 时，框架会自动执行严格的合规性校验：

1. **类型匹配校验**：若传入的策略对象未实现注册表声明的 `policyClass`，抛出 `PolicyException`：
   ```java
   // Policy type mismatch, expected: com.example.PaymentPolicy, got: com.example.WrongPolicy
   ```
2. **非空 Key 校验**：策略的 `key()` 返回值不允许为 `null`，否则抛出 `PolicyException`：
   ```java
   // Policy key cannot be null for policy type: com.example.PaymentPolicy
   ```
3. **注册表类型兼容校验**：调用 `addAll(registry)` 时，传入的注册表必须同为 `KeyedPolicyRegistry`，否则抛出 `PolicyException.unsupportedRegistry(...)`。

---

## 典型使用示例

```java
import com.team4u.framework.policy.api.KeyedPolicy;
import com.team4u.framework.policy.core.KeyedPolicyRegistry;
import java.util.Optional;

// 1. 定义自描述策略
public interface SmsSender extends KeyedPolicy<String> {
    void send(String phone, String content);
}

public class AliyunSmsSender implements SmsSender {
    @Override
    public String key() {
        return "ALIYUN";
    }

    @Override
    public void send(String phone, String content) {
        System.out.println("通过阿里云发送短信至 " + phone);
    }
}

// 2. 注册与使用
public class SmsService {
    private final KeyedPolicyRegistry<String, SmsSender> registry = 
            new KeyedPolicyRegistry<>(SmsSender.class);

    public void init() {
        registry.register(new AliyunSmsSender());
    }

    public void dispatchSms(String channel, String phone, String msg) {
        SmsSender sender = registry.get(channel)
                .orElseThrow(() -> new IllegalArgumentException("未找到短信渠道策略: " + channel));
        sender.send(phone, msg);
    }
}
```
