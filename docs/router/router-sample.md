# 实战案例

本章提供 `team4u-router` 在真实企业级生产环境中的典型实战范例。

---

## 动态业务开关与故障容灾迁移

### 业务背景
支付网关系统对接了两套通道服务（`primaryGatewayService` 与 `backupGatewayService`）。当主网关出现网络抖动或例行维护时，运维人员在配置中心实时修改配置，秒级将流量切换至备用网关，系统无需重启。

### 路由规则配置 (`router.payment-switch`)
```json
{
  "id": "payment-switch",
  "type": "map",
  "rules": [
    { "condition": "ONLINE", "value": "primaryGatewayService" },
    { "condition": "DEGRADED", "value": "backupGatewayService" }
  ],
  "fallbackValue": "primaryGatewayService"
}
```

### 业务代码实现
```java
public class PaymentGatewayClient {

    public void processPayment(PaymentRequest request) {
        // 读取当前环境开关状态（可从配置中心动态获取）
        String switchStatus = ConfigManager.global()
                .getString("payment.switch.status")
                .orElse("ONLINE");

        // 执行路由定位网关 Bean
        PaymentGateway gateway = RoutedBeanLocator.locate(
                "payment-switch",
                switchStatus,
                PaymentGateway.class
        );

        gateway.pay(request);
    }
}
```

---

## 基于用户画像的多维商品定价与折扣

### 业务背景
电商平台根据用户等级 (`userRank`)、会员标签 (`tags`)、商品分类 (`category`) 与订单总额 (`amount`) 动态计算适用的定价策略模型。

### 路由规则配置 (`router.pricing-router`)
```json
{
  "id": "pricing-router",
  "type": "expression",
  "rules": [
    {
      "condition": "category == 'DIGITAL' && amount >= 5000 && userRank >= 5",
      "value": "highEndDigitalVipPricingModel"
    },
    {
      "condition": "tags contains 'ENTERPRISE_VIP'",
      "value": "enterpriseCustomPricingModel"
    },
    {
      "condition": "amount >= 1000 || userRank >= 3",
      "value": "standardVipPricingModel"
    }
  ],
  "fallbackValue": "baseRetailPricingModel"
}
```

### 声明式接口调用
```java
public interface PricingService {

    @Routed(routerId = "pricing-router")
    BigDecimal calculatePrice(@RouteContext OrderContext context);
}

// 业务调用
PricingService pricingService = RoutedProxyFactory.createProxy(PricingService.class);
OrderContext ctx = new OrderContext("DIGITAL", 6000.0, 5, Collections.singletonList("ENTERPRISE_VIP"));
BigDecimal finalPrice = pricingService.calculatePrice(ctx);
```

---

## 新算法模型灰度发布与 A/B 实验分流

### 业务背景
推荐算法团队上线了新版模型 `recommendModelV2`，需要将全局 20% 的流量分发给新模型，80% 的流量保持老模型 `recommendModelV1`，且同一用户的推荐策略必须稳定幂等（粘性分流）。

### 路由规则配置 (`router.recommend-ab-router`)
```json
{
  "id": "recommend-ab-router",
  "type": "weight",
  "rules": [
    { "condition": "20", "value": "recommendModelV2" },
    { "condition": "80", "value": "recommendModelV1" }
  ],
  "fallbackValue": "recommendModelV1"
}
```

### 业务调用
```java
String userId = "user_998823";
RouteResult<String> result = RoutingManager.global().route("recommend-ab-router", userId, String.class);

String modelBeanName = result.getValue();
RecommendModel model = BeanManager.getInstance().getBean(modelBeanName, RecommendModel.class);
List<Item> recommendations = model.recommend(userId);
```

---

## 多业务线私有规则覆盖系统全局规则 (CompositeRouter)

### 业务背景
大型中台系统包含直播业务线和通用电商业务线。直播业务有独特的报错码与翻译策略，其余公共错误（如 DB 超时、限流）则统一使用中台基准规则。

### 规则配置
直播业务私有规则 (`router.translator.live`)：
```json
{
  "id": "translator.live",
  "type": "map",
  "rules": [
    {
      "condition": "LIVE_ROOM_CLOSED",
      "value": { "code": "ERR_3001", "defaultMsg": "当前直播间已关闭" }
    }
  ]
}
```

系统全局公共规则 (`router.translator.system`)：
```json
{
  "id": "translator.system",
  "type": "expression",
  "rules": [
    {
      "condition": "code == 'DB_TIMEOUT'",
      "value": { "code": "ERR_5001", "defaultMsg": "数据库连接超时，请重试" }
    }
  ],
  "fallbackValue": {
    "code": "ERR_9999",
    "defaultMsg": "系统繁忙: ${rawMessage}"
  }
}
```

组合聚合入口 (`router.translator.main`)：
```json
{
  "id": "translator.main",
  "type": "composite",
  "ext": {
    "delegates": [
      "translator.live",
      "translator.system"
    ]
  }
}
```

### 运行机制
当直播业务抛出 `LIVE_ROOM_CLOSED` 时，组合路由器优先命中 `translator.live` 并立即短路返回；当抛出 `DB_TIMEOUT` 时，私有规则未命中，自动滑落到 `translator.system` 中命中；如果两者均未命中显式规则，则由公共兜底 `ERR_9999` 收口。
