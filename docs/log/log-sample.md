# 实战案例

本章提供 `team4u-log` / `team4u-log-governance` 在常见核心业务场景中的最佳实践代码。以下 JSON 示例均假定已启动 `LogBootstrap`；core 默认输出未经脱敏的 RAW/UNMASKED 明文 `toString`。

---

## 核心交易订单流转日志

### 业务场景
在电商交易系统中，订单创建包含多个步骤（参数校验、风控检查、锁定库存、落库）。要求：
1. 每个关键步骤输出结构化日志；
2. 记录整体执行耗时；
3. 发生业务异常时准确记录错误码和异常原因。

### 代码实现
```java
import com.team4u.framework.log.LogSpan;
import com.team4u.framework.log.Loggers;
import org.springframework.stereotype.Service;

@Service
public class TradeOrderService {

    public OrderResult submitOrder(CreateOrderCmd cmd) {
        // 开启耗时统计 Span 并记录起始日志
        LogSpan span = Loggers.of(TradeOrderService.class)
                .action("SubmitOrder")
                .put("userId", cmd.getUserId())
                .put("amount", cmd.getAmount())
                .begin()
                .logStart();

        try {
            // 步骤 1：风控检查
            checkRisk(cmd);

            // 步骤 2：生成订单并落库
            Order order = doCreateOrder(cmd);

            // 步骤 3：记录成功日志并自动计算耗时
            span.put("orderId", order.getId())
                .success()
                .log();

            return OrderResult.success(order.getId());
        } catch (BizException ex) {
            span.put("errorCode", ex.getCode())
                .put("errorMsg", ex.getMessage())
                .failed(ex)
                .log();
            throw ex;
        }
    }
}
```

---

## 慢方法监控与慢调用自动提权

### 业务场景
大型报表导出与复杂统计查询接口，正常情况下耗时在 100ms 以内。要求：若某次执行耗时超过 500ms，自动将日志级别提升为 `WARN` 并记录状态为 "slow_success"，供告警平台采集。

### 代码实现
```java
@Service
public class ReportQueryService {

    @AutoLogTrace(action = "QuerySalesReport", slowThreshold = 500, ignoreExceptions = {ParamValidationException.class})
    public SalesReportVO queryReport(ReportFilter filter) {
        return reportDao.queryBigReport(filter);
    }
}
```

#### 输出的 JSON 示例（耗时 680ms 触发慢告警）：
```json
{
  "loggerName": "com.demo.ReportQueryService",
  "level": "WARN",
  "traceId": "tid-882200",
  "action": "QuerySalesReport",
  "status": "slow_success",
  "durationMs": 680,
  "payload": {
    "filter": {
      "startDate": "2026-08-01",
      "region": "CN"
    },
    "resp": {
      "totalRevenue": 1500000.00
    },
    "slowThreshold": 500
  },
  "suppressed": false
}
```

---

## 线上排查与动态条件染色实战

### 业务场景
线上环境日常运行在 `INFO` 级别，排查特定 VIP 租户 `TENANT_VIP_008` 在调用微信支付接口时的偶尔超时问题。

### 治理步骤

#### 动态下发染色规则（配置 `team4u.log.dyeing`）
```json
[
  {
    "id": "dyeing_vip_tenant",
    "condition": "tenantId == 'TENANT_VIP_008'",
    "targetLevel": "DEBUG"
  }
]
```

#### 业务日志打印
```java
Loggers.of(PaymentService.class)
       .action("WxPayRequest")
       .atDebug() // 日常情况下 DEBUG 级别会被 SLF4J 过滤掉
       .put("tenantId", req.getTenantId())
       .put("outTradeNo", req.getOutTradeNo())
       .put("rawXmlPayload", req.getXmlData())
       .log();
```

#### 效果
- 普通租户（`tenantId != 'TENANT_VIP_008'`）的 DEBUG 日志直接在客户端短路丢弃，不付出序列化与输出成本；
- `TENANT_VIP_008` 的请求命中染色规则，日志级别被提权并完整输出到日志平台，`payload` 中自动附加 "dyeingRuleMatched": "dyeing_vip_tenant"。
