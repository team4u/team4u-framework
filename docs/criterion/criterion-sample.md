# 实战案例

本章汇集了 `team4u-criterion` 在企业级生产场景中的经典应用案例。

---

## 案例 1：营销活动人群画像圈选

### 业务场景
电商平台策划“新春狂欢”活动，要求参与用户必须满足：
1. 年龄在 18 至 35 岁之间；
2. 用户角色为 `VIP` 或 `SVIP`；
3. 用户标签包含 `ELECTRONICS` 或 `GAMING`；
4. 注册时间在 2023 年 1 月 1 日之前。

### DSL 表达式
```sql
age between [18, 35] 
  && role in [VIP, SVIP] 
  && (tags containsAny ['ELECTRONICS', 'GAMING']) 
  && registerTime:date < '2023-01-01'
```

### Java 代码
```java
import com.team4u.framework.criterion.Criteria;

public class MarketingEligibilityService {

    public boolean isEligible(User user) {
        String rule = "age between [18, 35] " +
                      "&& role in [VIP, SVIP] " +
                      "&& (tags containsAny ['ELECTRONICS', 'GAMING']) " +
                      "&& registerTime:date < '2023-01-01'";

        return Criteria.global().matches(rule, user);
    }
}
```

---

## 案例 2：网关灰度分流与 App 版本控制

### 业务场景
移动端 API 网关升级，新功能仅对以下用户开放：
1. App 版本号大于等于 `3.2.0`；
2. 用户 ID Hash 圈选 20% 的用户（使用实验专有盐值保证分流独立性）；
3. 且客户端操作系统不是 `DEBUG_DEVICE`。

### DSL 表达式
```sql
appVersion:version >= '3.2.0' && userId hash 0.2 && deviceType != 'DEBUG_DEVICE'
```

### Java 代码
```java
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;

public class GatewayGrayReleaseFilter {

    public boolean shouldRouteToNewService(HttpRequest request) {
        MatchContext context = MatchContext.of(request)
                .setAttribute("salt", "EXP_2026_NEW_PAY_UI");

        String expression = "appVersion:version >= '3.2.0' && userId hash 0.2 && deviceType != 'DEBUG_DEVICE'";
        return Criteria.global().matches(expression, context);
    }
}
```

---

## 案例 3：风控高额交易拦截与外部属性短路加载

### 业务场景
支付风控系统拦截可疑交易：
1. 交易金额大于 50,000 元；
2. 且用户不在特权商户白名单中；
3. 且外部风控模型评分大于 85（通过 RPC 延迟查询，若前序条件不满足则短路跳过，绝不发起昂贵的远程 RPC 查询）。

### DSL 表达式
```sql
amount > 50000 && isWhiteMerchant == false && $riskScore > 85
```

### Java 代码
```java
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.LazyAttributeResolver;
import com.team4u.framework.criterion.MatchContext;

public class RiskAssessmentService {

    public boolean isHighRiskTransaction(Transaction tx) {
        MatchContext context = MatchContext.of(tx);

        // 绑定延迟解析器：仅当金额 > 50000 且不是白名单时，才会执行注册的 Lambda 发起远程调用
        LazyAttributeResolver resolver = new LazyAttributeResolver()
                .register("riskScore", () -> rpcRiskService.evaluateRiskScore(tx.getUserId(), tx.getIp()));

        context.setAttributeResolver(resolver);

        String rule = "amount > 50000 && isWhiteMerchant == false && $riskScore > 85";
        return Criteria.global().matches(rule, context);
    }
}
```
