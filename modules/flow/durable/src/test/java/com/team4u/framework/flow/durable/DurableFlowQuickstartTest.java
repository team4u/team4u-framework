package com.team4u.framework.flow.durable;

import com.team4u.framework.flow.Flow;
import com.team4u.framework.flow.Flows;
import com.team4u.framework.flow.StopReason;
import org.junit.Assert;
import org.junit.Test;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable 快速入门端到端场景测试。
 *
 * @author jay.wu
 */
public class DurableFlowQuickstartTest {

    public static class OrderContext implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String orderId;
        private final long amount;
        private boolean stockReserved;
        private String paymentChannel;
        private String receiptId;

        public OrderContext(String orderId, long amount, String paymentChannel) {
            this.orderId = orderId;
            this.amount = amount;
            this.paymentChannel = paymentChannel;
        }

        public String getOrderId() { return orderId; }
        public long getAmount() { return amount; }
        public boolean isStockReserved() { return stockReserved; }
        public void setStockReserved(boolean stockReserved) { this.stockReserved = stockReserved; }
        public String getPaymentChannel() { return paymentChannel; }
        public String getReceiptId() { return receiptId; }
        public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    }

    public static class Receipt implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String orderId;
        private final String receiptId;
        private final long amount;

        public Receipt(String orderId, String receiptId, long amount) {
            this.orderId = orderId;
            this.receiptId = receiptId;
            this.amount = amount;
        }

        public String getOrderId() { return orderId; }
        public String getReceiptId() { return receiptId; }
        public long getAmount() { return amount; }
    }

    public static class ExternalInventoryService {
        private final Map<String, String> externalCalls = new HashMap<>();

        public void reserve(String invocationId, OrderContext context) {
            externalCalls.put(invocationId, context.getOrderId());
            context.setStockReserved(true);
        }

        public Map<String, String> getExternalCalls() {
            return externalCalls;
        }
    }

    @Test
    public void durableCheckoutScenario() {
        ExternalInventoryService inventory = new ExternalInventoryService();
        InMemoryDurableStore store = new InMemoryDurableStore();
        DurableRuntime runtime = DurableRuntime.builder(store).build();

        Flow<OrderContext, OrderContext> cardPaySubflow = Flows.<OrderContext>begin("card-pay")
                .tap("call-card-gateway", ctx -> ctx.setReceiptId("RCP-CARD-" + ctx.getOrderId()))
                .build();

        Flow<OrderContext, OrderContext> walletPaySubflow = Flows.<OrderContext>begin("wallet-pay")
                .tap("call-wallet-gateway", ctx -> ctx.setReceiptId("RCP-WALLET-" + ctx.getOrderId()))
                .build();

        Flow<OrderContext, Receipt> checkoutFlow = Flows.<OrderContext>begin("checkout")
                .guard("validate-order",
                        order -> order.getAmount() > 0,
                        order -> StopReason.of("INVALID_AMOUNT", "Amount must be positive"))
                .tap("reserve-stock", (stepContext, order) ->
                        inventory.reserve(stepContext.invocationId(), order))
                .choose("choose-channel", OrderContext::getPaymentChannel)
                    .when("CARD", cardPaySubflow)
                    .when("WALLET", walletPaySubflow)
                .end()
                .step("build-receipt", order -> new Receipt(order.getOrderId(), order.getReceiptId(), order.getAmount()))
                .build();

        DurableFlow<OrderContext, Receipt> durableCheckout = runtime.register(checkoutFlow, 1);

        OrderContext order = new OrderContext("ORD-9999", 5000L, "CARD");
        DurableResult<Receipt> result = durableCheckout.start("exec-order-9999", order);

        Assert.assertTrue(result.isCompleted());
        Assert.assertEquals("ORD-9999", result.value().getOrderId());
        Assert.assertEquals("RCP-CARD-ORD-9999", result.value().getReceiptId());
        Assert.assertEquals(5000L, result.value().getAmount());

        // External inventory was invoked with deterministic invocationId
        String expectedInvocId = "checkout:1:exec-order-9999#/s1:reserve-stock";
        Assert.assertEquals(1, inventory.getExternalCalls().size());
        Assert.assertTrue(inventory.getExternalCalls().containsKey(expectedInvocId));

        // Snapshot in store is COMPLETED
        DurableSnapshot snapshot = store.load("checkout", "exec-order-9999");
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(DurableLifecycle.COMPLETED, snapshot.lifecycle());
    }
}
