# 实战案例

本章介绍 `team4u-policy` 在多渠道支付路由、电商营销优惠计算链与风控拦截流水线中的典型实战架构。

---

## 案例 1：多渠道聚合支付网关 (KeyedPolicy)

### 业务场景
聚合支付系统需要根据前端传入的支付方式代码（如 `"ALIPAY"`, `"WECHAT"`, `"UNIONPAY"`）分发到对应的支付渠道 SDK 执行统一统一下单。

### 代码实现

#### 1. 策略接口与数据传输模型
```java
import com.team4u.framework.policy.api.KeyedPolicy;
import lombok.Data;

@Data
public class PayRequest {
    private String orderId;
    private double amount;
    private String clientIp;
}

@Data
public class PayResponse {
    private boolean success;
    private String payUrl;
    private String tradeNo;
}

public interface PayChannelStrategy extends KeyedPolicy<String> {
    PayResponse pay(PayRequest request);
}
```

#### 2. 渠道策略实现（由 Spring 托管）
```java
@Component
public class WechatPayStrategy implements PayChannelStrategy {

    @Autowired
    private WechatPayClient wechatClient;

    @Override
    public String key() {
        return "WECHAT";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        return wechatClient.unifiedOrder(request);
    }
}

@Component
public class AlipayStrategy implements PayChannelStrategy {

    @Autowired
    private AlipayClient alipayClient;

    @Override
    public String key() {
        return "ALIPAY";
    }

    @Override
    public PayResponse pay(PayRequest request) {
        return alipayClient.createTrade(request);
    }
}
```

#### 3. 路由分发服务
```java
@Service
public class PaymentGatewayService {

    @Autowired
    private KeyedPolicyRegistry<String, PayChannelStrategy> payRegistry;

    public PayResponse executePay(String channelCode, PayRequest request) {
        PayChannelStrategy strategy = payRegistry.get(channelCode)
                .orElseThrow(() -> new IllegalArgumentException("不支持的支付渠道: " + channelCode));
        return strategy.pay(request);
    }
}
```

---

## 案例 2：多级营销优惠叠加计算链 (ContextPolicy)

### 业务场景
电商订单在结算时，需要按照固定顺序依次评估并叠加多种优惠规则：
1. 新人首单直减（优先级最高，`priority = ContextPolicy.HIGH = -1000`）；
2. 会员专属折扣（优先级中等，`priority = ContextPolicy.NORMAL = 0`）；
3. 满减优惠券抵扣（优先级最低，`priority = ContextPolicy.LOW = 1000`）。

### 代码实现

#### 1. 上下文与策略定义
```java
import com.team4u.framework.policy.api.ContextPolicy;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class OrderSettlementContext {
    private String userId;
    private boolean isNewUser;
    private int userRank; // 0-普通, 1-VIP
    private BigDecimal originalAmount;
    private BigDecimal payAmount;

    public void deduct(BigDecimal amount) {
        this.payAmount = this.payAmount.subtract(amount);
        if (this.payAmount.compareTo(BigDecimal.ZERO) < 0) {
            this.payAmount = BigDecimal.ZERO;
        }
    }
}

public interface PromotionPolicy extends ContextPolicy<OrderSettlementContext> {
    void applyPromotion(OrderSettlementContext context);
}
```

#### 2. 各级营销策略实现
```java
public class NewUserPromotion implements PromotionPolicy {
    @Override
    public boolean supports(OrderSettlementContext ctx) {
        return ctx.isNewUser();
    }

    @Override
    public int priority() {
        return ContextPolicy.HIGH; // -1000，最先执行
    }

    @Override
    public void applyPromotion(OrderSettlementContext ctx) {
        ctx.deduct(new BigDecimal("10.00")); // 首单立减 10 元
    }
}

public class MemberDiscountPromotion implements PromotionPolicy {
    @Override
    public boolean supports(OrderSettlementContext ctx) {
        return ctx.getUserRank() > 0;
    }

    @Override
    public int priority() {
        return ContextPolicy.NORMAL; // 0，次之执行
    }

    @Override
    public void applyPromotion(OrderSettlementContext ctx) {
        // 会员享受 95 折
        ctx.setPayAmount(ctx.getPayAmount().multiply(new BigDecimal("0.95")));
    }
}
```

#### 3. 优惠链装配与全量匹配执行
```java
public class PromotionEngine {

    private final OrderedPolicyChain<OrderSettlementContext, PromotionPolicy> promoChain;

    public PromotionEngine() {
        this.promoChain = new OrderedPolicyChain<>(PromotionPolicy.class);
        promoChain.register(new MemberDiscountPromotion());
        promoChain.register(new NewUserPromotion());
    }

    public BigDecimal calculateFinalAmount(OrderSettlementContext context) {
        context.setPayAmount(context.getOriginalAmount());

        // 获取所有匹配的优惠策略（已按 priority 升序排序）
        List<PromotionPolicy> activePromotions = promoChain.allMatches(context);

        for (PromotionPolicy policy : activePromotions) {
            policy.applyPromotion(context);
        }

        return context.getPayAmount();
    }
}
```

---

## 案例 3：金融交易风控拦截流水线 (PolicyPipeline)

### 业务场景
在转账或大额交易前，必须经过多道风控规则检查（黑名单过滤 -> 单日限额检查 -> 异地登录校验）。任何一关未通过，必须**立即终止后续校验并阻断交易**。

### 代码实现

```java
import com.team4u.framework.policy.api.ContextPolicy;
import com.team4u.framework.policy.core.OrderedPolicyChain;
import com.team4u.framework.policy.engine.PolicyPipeline;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TradeRiskManagementSystem {

    @Data
    @AllArgsConstructor
    public static class TradeContext {
        private String userId;
        private double amount;
        private String ip;
        private String deviceFingerprint;
    }

    public interface RiskInterceptor extends ContextPolicy<TradeContext> {
        boolean checkRisk(TradeContext context);
    }

    // 1. 黑名单规则（高优先级）
    public static class BlacklistRiskInterceptor implements RiskInterceptor {
        @Override
        public boolean supports(TradeContext context) {
            return true;
        }

        @Override
        public int priority() {
            return ContextPolicy.HIGH; // -1000
        }

        @Override
        public boolean checkRisk(TradeContext context) {
            if ("USER_BLOCKED".equals(context.getUserId())) {
                log.warn("命中全局黑名单: {}", context.getUserId());
                return false; // 拦截
            }
            return true; // 放行
        }
    }

    // 2. 大额超限规则（普通优先级）
    public static class AmountLimitRiskInterceptor implements RiskInterceptor {
        @Override
        public boolean supports(TradeContext context) {
            return context.getAmount() > 10000.0;
        }

        @Override
        public int priority() {
            return ContextPolicy.NORMAL; // 0
        }

        @Override
        public boolean checkRisk(TradeContext context) {
            if (context.getAmount() > 1000000.0) {
                log.warn("超过单笔最大交易限额: {}", context.getAmount());
                return false;
            }
            return true;
        }
    }

    public static void main(String[] args) {
        OrderedPolicyChain<TradeContext, RiskInterceptor> chain = new OrderedPolicyChain<>(RiskInterceptor.class);
        chain.register(new BlacklistRiskInterceptor());
        chain.register(new AmountLimitRiskInterceptor());

        // 构建流水线
        PolicyPipeline<TradeContext, RiskInterceptor> pipeline = new PolicyPipeline<>(chain);

        TradeContext normalTrade = new TradeContext("USER_001", 50000.0, "127.0.0.1", "device_abc");

        // 执行流水线
        boolean passed = pipeline.executeChain(normalTrade, (interceptor, ctx) -> {
            return interceptor.checkRisk(ctx);
        });

        System.out.println("交易风控校验结果: " + (passed ? "通过" : "拦截"));
    }
}
```
