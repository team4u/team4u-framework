# 快速开始

本文介绍如何在 3 分钟内使用 `team4u-policy`。

---

## 1. 引入依赖

```xml
<dependency>
    <groupId>com.team4u</groupId>
    <artifactId>team4u-policy</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## 2. 场景 A：精准键值路由 (`KeyedPolicy`)

根据明确的业务标识（如支付渠道代码 `"ALIPAY"`, `"WECHAT"`）在 $O(1)$ 时间内直接定位目标策略：

### 1. 定义策略接口与实现类
```java
import com.team4u.framework.policy.api.KeyedPolicy;

// 1. 策略接口继承 KeyedPolicy<String>
public interface PaymentPolicy extends KeyedPolicy<String> {
    void pay(double amount);
}

// 2. 策略实现类自声明 Key
public class AlipayPolicy implements PaymentPolicy {
    @Override
    public String key() {
        return "ALIPAY";
    }

    @Override
    public void pay(double amount) {
        System.out.println("使用支付宝支付: " + amount);
    }
}
```

### 2. 注册并执行
```java
import com.team4u.framework.policy.core.KeyedPolicyRegistry;

public class KeyedPolicyQuickStart {

    public static void main(String[] args) {
        // 创建指定策略类型的注册表
        KeyedPolicyRegistry<String, PaymentPolicy> registry = new KeyedPolicyRegistry<>(PaymentPolicy.class);

        // 注册策略
        registry.register(new AlipayPolicy());

        // O(1) 查找并执行（返回 Optional）
        registry.get("ALIPAY").ifPresent(policy -> policy.pay(100.0));
    }
}
```

---

## 3. 场景 B：有序责任链过滤 (`ContextPolicy`)

根据业务上下文动态评估 `supports(context)`，并按照 `priority()` 升序自动排序执行：

### 1. 定义上下文与策略实现类
```java
import com.team4u.framework.policy.api.ContextPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;

// 1. 定义上下文对象
@Data
@AllArgsConstructor
public class OrderContext {
    private boolean isVip;
    private double price;
}

// 2. 策略接口继承 ContextPolicy<OrderContext>
public interface DiscountPolicy extends ContextPolicy<OrderContext> {
    double calculate(OrderContext context);
}

// 3. VIP 专属优惠（高优先级，数值为 ContextPolicy.HIGH = -1000）
public class VipDiscountPolicy implements DiscountPolicy {
    @Override
    public boolean supports(OrderContext context) {
        return context.isVip();
    }

    @Override
    public int priority() {
        return ContextPolicy.HIGH; // -1000，数值越小越优先执行
    }

    @Override
    public double calculate(OrderContext context) {
        return context.getPrice() * 0.8;
    }
}
```

### 2. 注册并执行匹配
```java
import com.team4u.framework.policy.core.OrderedPolicyChain;

public class OrderedPolicyQuickStart {

    public static void main(String[] args) {
        OrderedPolicyChain<OrderContext, DiscountPolicy> chain = new OrderedPolicyChain<>(DiscountPolicy.class);
        chain.register(new VipDiscountPolicy());

        OrderContext vipOrder = new OrderContext(true, 100.0);

        // 匹配首个生效的策略 (firstMatch)
        chain.firstMatch(vipOrder).ifPresent(policy -> {
            double finalPrice = policy.calculate(vipOrder);
            System.out.println("VIP 折后价: " + finalPrice); // 输出: 80.0
        });
    }
}
```

---

## 下一步

- 深入了解精准路由与 Copy-On-Write 读写分离机制：[精准键值策略模式](policy-keyed.md)
- 掌握责任链排序、重复模式控制与流水线中断：[有序责任链模式](policy-ordered.md)
- 开启包扫描、SPI 发现与 Spring 自动注入：[策略自动扫描与 Spring 发现](policy-scanner.md)
- 查看完整的微服务与风控实战案例：[实战案例](policy-sample.md)
