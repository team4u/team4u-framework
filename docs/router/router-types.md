# 路由器类型

`team4u-router` 内置了四种核心路由器实现，覆盖了从简单映射到复杂表达式、比例灰度以及多级级联的完整业务场景。

---

## 路由器类型概览

| 类型标识 (`type`) | 对应实现类 | 核心算法与特征 | 适用场景 |
| :--- | :--- | :--- | :--- |
| `map` | `MapRouter` | 基于精确字符串匹配，查找复杂度 $O(1)$，初始化检测重复 Condition | 状态码映射、渠道直连、枚举分发 |
| `expression` | `ExpressionRouter` | 集成 `team4u-criterion` 低开销 DSL，支持短路评估与 `multiMatch` 多重匹配 | 人群圈选、风控拦截、多维优惠定价 |
| `weight` | `WeightRouter` | 基于 MurmurHash32 与 `TreeMap.ceilingEntry` 区间查找，查找复杂度 $O(\log N)$ | A/B 测试、灰度发布、流量权重负载 |
| `composite` | `CompositeRouter` | 瀑布责任链串联多个子路由，支持私有规则优先与基准兜底继承 | 业务线定制覆盖系统全局通用规则 |

---

## 精准映射路由器 (MapRouter)

### 特性说明
- **类型标识**：`type: "map" (常量 `RouterType.MAP`)
- **匹配逻辑**：将请求对象转换为字符串 `String.valueOf(request)`，并在内部哈希表中进行 $O(1)$ 精确比较。
- **兜底机制**：未命中任何规则或请求入参为 `null` 时，返回配置的 `fallbackValue`；若无兜底配置则返回 `RouteResult.unmatch()`。
- **配置冲突防御**：初始化时严格检测规则唯一性。若配置中存在重复的 `condition`，将直接抛出 `RouteConfigException.duplicateCondition`，防止运行时决策歧义。

### JSON 配置示例
```json
{
  "id": "channel-router",
  "type": "map",
  "rules": [
    { "condition": "ALIPAY", "value": "alipayChannelHandler" },
    { "condition": "WECHAT", "value": "wechatChannelHandler" },
    { "condition": "UNIONPAY", "value": "unionpayChannelHandler" }
  ],
  "fallbackValue": "defaultChannelHandler"
}
```

### 代码调用示例
```java
RouteResult<String> result = RoutingManager.global().route("channel-router", "ALIPAY", String.class);
if (result.isRuleMatch()) {
    String beanName = result.getValue(); // "alipayChannelHandler"
    String condition = result.getMatchedCondition(); // "ALIPAY"
}
```

---

## 规则表达式路由器 (ExpressionRouter)

### 特性说明
- **类型标识**：`type: "expression" (常量 `RouterType.EXPRESSION`)
- **匹配逻辑**：集成 `team4u-criterion` 规则引擎，规则按配置顺序依次执行短路评估。
- **预热机制**：初始化时自动调用 `criteria.compileExpression(condition)` 预编译所有规则表达式，避免运行时首次解析的编译抖动。
- **多重匹配与条件收集** (`multiMatch`)：支持通过 `ext.multiMatch: true` 开启多重匹配，收集所有命中的规则结果集合（`List<T>`）以及对应的全部命中条件（`matchedConditions`）。
- **规则引擎定制**：支持在构造 `ExpressionRouterFactory(Criteria)` 时注入自定义的 `Criteria` 实例（例如注册了业务特定算子或转换器的实例）。

### 单匹配模式配置 (默认)
首个匹配成功的规则即刻短路返回：

```json
{
  "id": "vip-discount-router",
  "type": "expression",
  "rules": [
    {
      "condition": "userRank >= 5 && totalAmount > 1000",
      "value": "SUPER_VIP_DISCOUNT"
    },
    {
      "condition": "userRank >= 3 || tags contains 'PLUS_MEMBER'",
      "value": "NORMAL_VIP_DISCOUNT"
    }
  ],
  "fallbackValue": "STANDARD_DISCOUNT"
}
```

### 多重匹配模式配置 (`multiMatch: true`)
在营销发券、风控多重命中合规检查场景下，一个请求可能同时满足多条规则：

```json
{
  "id": "coupon-router",
  "type": "expression",
  "ext": {
    "multiMatch": true
  },
  "rules": [
    { "condition": "isNewUser == true", "value": "NEW_USER_COUPON" },
    { "condition": "totalAmount >= 200", "value": "FULL_REDUCTION_COUPON" },
    { "condition": "birthday:date == 'now'", "value": "BIRTHDAY_COUPON" }
  ],
  "fallbackValue": ["DEFAULT_COUPON"]
}
```

### 多重匹配代码消费
```java
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.router.RoutingManager;
import com.team4u.framework.router.api.model.RouteResult;

import java.util.List;

RouteResult<List<String>> result = RoutingManager.global().route(
        "coupon-router",
        userContext,
        new TypeReference<List<String>>() {}
);

if (result.isMatch()) {
    // 命中的所有优惠券列表
    List<String> coupons = result.getValue(); 
    // ["NEW_USER_COUPON", "FULL_REDUCTION_COUPON"]
    
    // 命中的所有条件列表
    List<String> matchedConditions = result.getMatchedConditions(); 
    // ["isNewUser == true", "totalAmount >= 200"]
}
```

---

## 权重比例路由器 (WeightRouter)

### 特性说明
- **类型标识**：`type: "weight" (常量 `RouterType.WEIGHT`)
- **算法原理**：
  1. 规则的 `condition` 必须为非负整数字符串（如 "20", "30", "50"）。若非数字或小于 0，初始化时抛出 `RouteConfigException.validationError`。
  2. 框架在初始化时按顺序累加权重生成连续区间，并使用 `TreeMap<Integer, Object>` 保存区间终点与目标值的映射。
  3. 对请求路由键进行 **MurmurHash32** 运算取模：
     $$\text{hashValue} = (\text{HashUtil.murmur32}(\text{routingKey}) \ \& \ \text{Integer.MAX\_VALUE}) \pmod{\text{totalWeight}}$$
  4. 使用 `TreeMap.ceilingEntry(hashValue + 1)` 以 $O(\log N)$ 时间复杂度精准命中区间。
- **确定性与粘性路由**：相同入参在规则未变更的情况下，哈希结果恒定，保证同一用户/设备在灰度期间策略稳定不漂移。

### JSON 配置示例
```json
{
  "id": "ab-test-router",
  "type": "weight",
  "rules": [
    { "condition": "20", "value": "strategy_v1" },
    { "condition": "30", "value": "strategy_v2" },
    { "condition": "50", "value": "strategy_v3" }
  ],
  "fallbackValue": "strategy_default"
}
```

### 代码调用示例
```java
// 传入 userId 或 deviceId 作为分流因子
RouteResult<String> result = RoutingManager.global().route("ab-test-router", "user_10086", String.class);
System.out.println("命中策略: " + result.getValue());
System.out.println("命中权重规则: " + result.getMatchedCondition()); // 例如 "20"
```

---

## 组合代理路由器 (CompositeRouter)

### 特性说明
- **类型标识**：`type: "composite" (常量 `RouterType.COMPOSITE`)
- **匹配逻辑**：在 `ext.delegates` 中按优先级配置子路由器 ID 列表（支持混合多种不同类型的子路由器）。
- **瀑布流与短路机制**：
  1. 依次委托给各个子路由器执行。
  2. 一旦某个子路由器产生 **规则命中**(`RULE_MATCH`) 或 **拦截器短路** (`SHORT_CIRCUITED`)，立即短路中断并返回该结果。
  3. 若子路由器仅命中其自身的兜底值 (`FALLBACK_MATCH`)，组合路由**不会立即终止**，而是收集该兜底值作为候选，并继续尝试后续委托项，直到找到真实规则命中或以最终收集的兜底值收口。

### JSON 配置示例
```json
{
  "id": "translator.main",
  "type": "composite",
  "ext": {
    "delegates": [
      "translator.biz-live",     // 优先级 1：直播业务专用错误翻译规则 (MapRouter)
      "translator.biz-order",    // 优先级 2：订单业务专用错误翻译规则 (ExpressionRouter)
      "translator.common"        // 优先级 3：系统全局公共错误规则 (ExpressionRouter)
    ]
  },
  "fallbackValue": {
    "code": "UNKNOWN_ERROR",
    "defaultMsg": "系统繁忙，请稍后再试"
  }
}
```

---

## 编程式构建路由策略 (RoutePolicyBuilder)

`team4u-router` 提供了流畅的强类型 Fluent Builder，支持无配置文件的纯 Java 代码构建：

```java
import com.team4u.framework.router.api.builder.RoutePolicyBuilder;
import com.team4u.framework.router.api.model.RoutePolicy;

// 1. 构建 Map 路由策略
RoutePolicy mapPolicy = RoutePolicyBuilder.<String>map()
        .id("region-router")
        .rule("CN", "chinaHandler")
        .rule("US", "usHandler")
        .fallback("globalHandler")
        .build();

// 2. 构建 Expression 路由策略 (支持多重匹配配置)
RoutePolicy exprPolicy = RoutePolicyBuilder.<String>expression()
        .id("vip-router")
        .rule("level > 5", "highVip")
        .rule("level <= 5", "normalVip")
        .ext("multiMatch", false)
        .fallback("guestVip")
        .build();

// 3. 构建 Weight 路由策略
RoutePolicy weightPolicy = RoutePolicyBuilder.<String>weight()
        .id("gray-router")
        .rule("10", "gray-node")
        .rule("90", "stable-node")
        .fallback("stable-node")
        .build();

// 4. 构建 Composite 组合路由策略 (支持可变参数 delegates)
RoutePolicy compositePolicy = RoutePolicyBuilder.<String>composite()
        .id("master-router")
        .delegates("region-router", "vip-router")
        .fallback("finalFallback")
        .build();

// 5. 构建自定义 SPI 路由器策略
RoutePolicy customPolicy = RoutePolicyBuilder.<String>custom("consistent-hash")
        .id("sharding-router")
        .rule("db_node_0", "datasource0")
        .rule("db_node_1", "datasource1")
        .build();
```
