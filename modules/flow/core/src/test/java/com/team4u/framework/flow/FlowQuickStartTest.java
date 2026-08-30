package com.team4u.framework.flow;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 快速入门与典型业务模式测试。
 *
 * @author jay.wu
 */
public class FlowQuickStartTest {

    public static class OrderContext {
        private final String orderId;
        private final long amount;
        private boolean valid;
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
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public boolean isStockReserved() { return stockReserved; }
        public void setStockReserved(boolean stockReserved) { this.stockReserved = stockReserved; }
        public String getPaymentChannel() { return paymentChannel; }
        public String getReceiptId() { return receiptId; }
        public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    }

    public static class Receipt {
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

    public static class InventoryService {
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
    public void checkoutQuickstart() {
        InventoryService inventory = new InventoryService();

        Flow<OrderContext, OrderContext> cardPaySubflow = Flows.<OrderContext>begin("card-pay")
                .tap("call-card-gateway", ctx -> ctx.setReceiptId("RCP-CARD-" + ctx.getOrderId()))
                .build();

        Flow<OrderContext, OrderContext> walletPaySubflow = Flows.<OrderContext>begin("wallet-pay")
                .tap("call-wallet-gateway", ctx -> ctx.setReceiptId("RCP-WALLET-" + ctx.getOrderId()))
                .build();

        Flow<OrderContext, Receipt> checkout = Flows.<OrderContext>begin("checkout")
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

        OrderContext order = new OrderContext("ORD-1001", 9900L, "CARD");
        FlowExecution<Receipt> execution = checkout.run(order, RunOptions.builder()
                .executionId("exec-order-1001")
                .trace(true)
                .build());

        Assert.assertTrue(execution.result().isSucceeded());
        Receipt receipt = execution.result().value();
        Assert.assertEquals("ORD-1001", receipt.getOrderId());
        Assert.assertEquals("RCP-CARD-ORD-1001", receipt.getReceiptId());
        Assert.assertEquals(9900L, receipt.getAmount());

        // External idempotent write recorded with invocationId
        Assert.assertEquals(1, inventory.getExternalCalls().size());
        Assert.assertTrue(inventory.getExternalCalls().containsKey("exec-order-1001#/s1:reserve-stock"));
    }
}
