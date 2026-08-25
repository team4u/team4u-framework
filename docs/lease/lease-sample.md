# 实战案例

本章提供 `team4u-lease` 在分布式调度与排他长任务治理中的典型实战范例。

---

## 未支付订单 15 分钟超时自动取消

### 业务场景
用户下单后若 15 分钟内未完成支付，系统需自动关闭订单并释放库存。系统要求：
- 相同订单号绝对不能创建重复的取消任务（幂等建档）；
- 服务重启或节点宕机后延迟任务不能丢失；
- 集群中任意一台空闲 Worker 均可抢占执行，且排他单节点处理。

### 代码实现

#### 下单时幂等发布延迟租约任务
```java
import com.team4u.framework.lease.api.LeaseProducer;
import com.team4u.framework.lease.model.LeasePublishRequest;
import com.team4u.framework.lease.model.LeasePublishResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    @Autowired
    private LeaseProducer leaseProducer;

    @Autowired
    private OrderDao orderDao;

    public void createOrder(Order order) {
        // 1. 本地落库保存订单
        orderDao.insert(order);

        // 2. 幂等发布 15 分钟延迟取消任务
        LeasePublishResult result = leaseProducer.publishIfAbsent(LeasePublishRequest.builder()
                .taskGroup("order-lifecycle")
                .taskType("order-cancel")
                .businessKey("CANCEL|" + order.getId()) // 业务幂等键
                .payload(String.valueOf(order.getId()))
                .priority(5)
                .delayMillis(15 * 60 * 1000L)          // 15 分钟后就绪可见
                .build());

        if (!result.isCreated()) {
            // 已存在该订单的取消任务，无需重复操作
        }
    }
}
```

#### Worker 端注册处理逻辑
```java
import com.team4u.framework.lease.handler.DefaultLeaseTaskHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class OrderCancelHandler {

    @Autowired
    private DefaultLeaseTaskHandlerRegistry handlerRegistry;

    @Autowired
    private OrderDao orderDao;

    @Autowired
    private InventoryService inventoryService;

    @PostConstruct
    public void init() {
        handlerRegistry.register("order-lifecycle", "order-cancel", context -> {
            Long orderId = Long.parseLong(context.getPayload());
            Order order = orderDao.selectById(orderId);

            // 校验订单状态是否仍为未支付
            if (order != null && "UNPAID".equals(order.getStatus())) {
                order.setStatus("CANCELLED");
                orderDao.updateById(order);
                inventoryService.releaseStock(order.getProductId(), order.getQuantity());
            }
            // 普通 LeaseTaskHandler 正常结束返回即可，框架自动提交 close(SUCCEEDED)
        });
    }
}
```

---

## 第三方支付结果长耗时轮询补偿 (`LeaseLifecycleAwareTaskHandler`)

### 业务场景
某些聚合支付通道仅支持商户主动轮询支付结果。在提交支付后，系统需要每隔 30 秒发起一次状态查询：
- 若支付成功：标记订单成功并立即提交成功终态（`close(SUCCEEDED)`）；
- 若仍处于处理中（`PROCESSING`）：主动释放租约并推迟 30 秒再次轮询（`release(delayMillis)`）；
- 若明确失败或超时已达最大轮询次数：标记订单失败并提交失败终态（`close(FAILED)`）。

### 代码实现

```java
import com.team4u.framework.lease.enums.LeaseTaskFailureReason;
import com.team4u.framework.lease.handler.LeaseLifecycleAwareTaskHandler;
import com.team4u.framework.lease.model.LeaseCloseRequest;
import com.team4u.framework.lease.model.LeaseReleaseRequest;
import com.team4u.framework.lease.runtime.LeaseLifecycleExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentQueryHandler implements LeaseLifecycleAwareTaskHandler {

    private static final int MAX_POLL_COUNT = 20; // 最多轮询 20 次 (约 10 分钟)

    @Autowired
    private ThirdPartyPayApi thirdPartyPayApi;

    @Autowired
    private OrderService orderService;

    @Override
    public void handleLifecycle(LeaseLifecycleExecutionContext context) throws Exception {
        String paymentId = context.getPayload();
        int currentDeliveryCount = context.getDeliveryCount();

        // 1. 超过最大轮询上限，主动终止
        if (currentDeliveryCount > MAX_POLL_COUNT) {
            orderService.markPayTimeout(paymentId);
            context.close(LeaseCloseRequest.failed(
                    LeaseTaskFailureReason.RETRY_EXHAUSTED,
                    "超过最大轮询次数: " + MAX_POLL_COUNT
            ));
            return;
        }

        // 2. 调用第三方接口查询支付结果
        PaymentResult result = thirdPartyPayApi.queryPayment(paymentId);

        if (result.isSuccess()) {
            // 支付成功：完成业务并主动闭环任务
            orderService.markPaid(paymentId);
            context.close(LeaseCloseRequest.succeeded());
        } else if (result.isProcessing()) {
            // 仍处于处理中：主动释放租约，推迟 30 秒后再次唤醒抢占
            context.release(LeaseReleaseRequest.of(30_000L));
        } else {
            // 明确失败：标记订单失败并关闭任务
            orderService.markFailed(paymentId);
            context.close(LeaseCloseRequest.failed(
                    LeaseTaskFailureReason.MANUAL_FAIL,
                    "第三方支付网关明确返回交易失败: " + result.getErrorCode()
            ));
        }
    }
}
```

