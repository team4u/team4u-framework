# 实战案例

本章汇集了 `team4u-flow` 在企业级生产场景中的经典应用案例。

---

## 案例一：电商全链路下单与库存幂等预占

### 业务场景
电商下单核心链路包含参数守卫、库存幂等预占、风险扫描与凭证生成：
1. **参数校验**：校验购买数量与金额合法，不满足时正常拒绝并返回业务提示；
2. **库存预占**：调用外部仓储 RPC 预占库存，必须传递稳定的业务幂等键防止重复扣减；
3. **风控扫描**：检查用户是否处于下单黑名单；
4. **生成凭证**：将中间上下文对象转换为不可变订单结算凭证（`Receipt`）；
5. **终态指标**：无论成功或失败均记录监控指标。

### 业务模型
```java
public class OrderContext {
    private final String orderId;
    private final String userId;
    private final int quantity;
    private final long amount;
    private boolean stockReserved;

    public OrderContext(String orderId, String userId, int quantity, long amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.quantity = quantity;
        this.amount = amount;
    }

    public String getOrderId() { return orderId; }
    public String getUserId() { return userId; }
    public int getQuantity() { return quantity; }
    public long getAmount() { return amount; }
    public boolean isStockReserved() { return stockReserved; }
    public void setStockReserved(boolean stockReserved) { this.stockReserved = stockReserved; }
}

public class OrderReceipt {
    private final String orderId;
    private final String status;
    private final long amount;

    public OrderReceipt(String orderId, String status, long amount) {
        this.orderId = orderId;
        this.status = status;
        this.amount = amount;
    }

    public String getOrderId() { return orderId; }
    public String getStatus() { return status; }
    public long getAmount() { return amount; }
}
```

### Flow 编排与调用
```java
import com.team4u.framework.flow.*;

public class OrderCheckoutService {

    private final InventoryClient inventoryClient;
    private final MetricsClient metricsClient;
    private final Flow<OrderContext, OrderReceipt> checkoutFlow;

    public OrderCheckoutService(InventoryClient inventoryClient, MetricsClient metricsClient) {
        this.inventoryClient = inventoryClient;
        this.metricsClient = metricsClient;
        this.checkoutFlow = buildCheckoutFlow();
    }

    private Flow<OrderContext, OrderReceipt> buildCheckoutFlow() {
        return Flows.<OrderContext>begin("order-checkout")
                // 1. 守卫校验：购买数量与金额必须合法
                .guard("validate-params",
                        order -> order.getQuantity() > 0 && order.getAmount() > 0,
                        order -> StopReason.of("INVALID_ORDER", "购买数量或金额不合法"))
                
                // 2. 外部库存预占（传递确定性幂等键）
                .tap("reserve-inventory", (stepContext, order) -> {
                    inventoryClient.reserve(stepContext.invocationId(), order.getOrderId(), order.getQuantity());
                    order.setStockReserved(true);
                })
                
                // 3. 转换为成功凭证
                .step("create-receipt", order ->
                        new OrderReceipt(order.getOrderId(), "SUCCESS", order.getAmount()))
                
                // 4. 终态度量上报
                .ensure("record-metrics", (order, completion) -> {
                    metricsClient.record("order.checkout", completion.kind().name());
                })
                .build();
    }

    public OrderReceipt checkout(OrderContext context) {
        return checkoutFlow.call(context);
    }
}
```

---

## 案例二：跨渠道多分支支付路由

### 业务场景
聚合支付系统根据用户选择的支付渠道分流到不同的渠道子流程：
1. **微信支付**：拉起微信统一下单 API，获取 PrepId 并透传；
2. **支付宝支付**：拉起支付宝网页/App 支付接口，获取 Form 表单字符串；
3. **银联云闪付**：拉起云闪付报文生成接口；
4. **未支持渠道**：触发业务安全停止（`otherwiseStop`），返回明确的不支持渠道错误码。

### Flow 编排
```java
public class PaymentRoutingService {

    public Flow<PaymentRequest, PaymentResponse> buildPaymentFlow() {
        // 定义渠道子流程
        Flow<PaymentRequest, PaymentResponse> wechatFlow = Flows.<PaymentRequest>begin("wechat-pay")
                .step("call-wechat-api", req -> wechatClient.unifiedOrder(req))
                .build();

        Flow<PaymentRequest, PaymentResponse> alipayFlow = Flows.<PaymentRequest>begin("alipay-pay")
                .step("call-alipay-api", req -> alipayClient.createOrder(req))
                .build();

        Flow<PaymentRequest, PaymentResponse> unionFlow = Flows.<PaymentRequest>begin("union-pay")
                .step("call-union-api", req -> unionClient.pay(req))
                .build();

        // 编排主路由流程
        return Flows.<PaymentRequest>begin("payment-router")
                .guard("check-order-active",
                        PaymentRequest::isPayable,
                        req -> StopReason.of("ORDER_NOT_PAYABLE", "订单不可支付"))
                .choose("route-by-channel", PaymentRequest::getChannel)
                    .when("WECHAT", wechatFlow)
                    .when("ALIPAY", alipayFlow)
                    .when("UNION", unionFlow)
                    .otherwiseStop(req -> StopReason.of("UNSUPPORTED_CHANNEL", "不支持的支付渠道: " + req.getChannel()))
                .end()
                .build();
    }
}
```

---

## 案例三：长周期订单履约与崩溃恢复

### 业务场景
在涉及多步骤外部调用的长流程履约中（如“扣减优惠券 -> 划扣主账户余额 -> 发放权益卡包 -> 发送短信通知”）：
- 当某一步骤由于远程网络抖动、容器重启导致中断时，系统可从最后一次成功的检查点**无缝恢复**，无需从头重跑。
- 业务外部调用保持同一 `invocationId`，下游系统可通过该幂等键防止重复发放权益。

### DurableFlow 代码
```java
import com.team4u.framework.flow.durable.*;

public class DurableFulfillmentService {

    private final DurableFlow<FulfillmentContext, FulfillmentResult> durableFlow;

    public DurableFulfillmentService(DurableRuntime runtime) {
        Flow<FulfillmentContext, FulfillmentResult> flow = Flows.<FulfillmentContext>begin("order-fulfillment")
                .tap("deduct-coupon", (ctx, req) -> couponClient.deduct(ctx.invocationId(), req.getCouponId()))
                .tap("debit-balance", (ctx, req) -> balanceClient.debit(ctx.invocationId(), req.getUserId(), req.getAmount()))
                .tap("grant-rights", (ctx, req) -> rightsClient.grant(ctx.invocationId(), req.getUserId(), req.getRightsId()))
                .step("build-result", req -> new FulfillmentResult(req.getOrderId(), true))
                .build();

        // 注册版本为 1 的持久化流程
        this.durableFlow = runtime.register(flow, 1);
    }

    // 1. 发起履约
    public DurableResult<FulfillmentResult> startFulfillment(String orderId, FulfillmentContext context) {
        return durableFlow.start(orderId, context);
    }

    // 2. 崩溃或超时后恢复
    public DurableResult<FulfillmentResult> recoverFulfillment(String orderId) {
        return durableFlow.recover(orderId);
    }

    // 3. 失败后显式重试
    public DurableResult<FulfillmentResult> retryFulfillment(String orderId) {
        return durableFlow.retry(orderId);
    }
}
```
