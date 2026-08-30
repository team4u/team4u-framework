package com.team4u.framework.fsm;

import com.team4u.framework.fsm.exception.TransitionRejectedException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 审批流快速上手测试：演示强类型状态机从声明到执行的典型用法。
 * <p>
 * 显式携带 Java 8 泛型类型证据（type witness，{@code StateMachine.<S, E, C>builder(...)}），
 * 作为新手上手示例的行为契约。
 */
public class ApprovalFlowQuickStartTest {

    private enum OrderState { CREATED, SUBMITTED, APPROVED, REJECTED, CANCELLED }

    private enum OrderEvent { SUBMIT, APPROVE, REJECT, CANCEL }

    private static final class ApprovalContext {
        private final String operator;
        private final boolean seniorApprover;
        private final List<String> auditTrail = new ArrayList<String>();

        ApprovalContext(String operator, boolean seniorApprover) {
            this.operator = operator;
            this.seniorApprover = seniorApprover;
        }
    }

    private StateMachine<OrderState, OrderEvent, ApprovalContext> machine;

    @Before
    public void setUp() {
        machine = StateMachine
                .<OrderState, OrderEvent, ApprovalContext>builder("order-approval", OrderState.CREATED)
                .from(OrderState.CREATED).on(OrderEvent.SUBMIT).to(OrderState.SUBMITTED)
                    .named("submit")
                    .action(ctx -> ctx.getContext().auditTrail.add("submit:" + ctx.getContext().operator))
                .from(OrderState.SUBMITTED).on(OrderEvent.APPROVE)
                    .when("senior approver only", ctx -> ctx.getContext().seniorApprover)
                    .to(OrderState.APPROVED)
                    .named("approve")
                    .action(ctx -> ctx.getContext().auditTrail.add("approve:" + ctx.getContext().operator))
                .from(OrderState.SUBMITTED).on(OrderEvent.REJECT).to(OrderState.REJECTED).named("reject")
                .fromAny().on(OrderEvent.CANCEL).to(OrderState.CANCELLED).named("global-cancel")
                .build();
    }

    @Test
    public void testHappyPathSubmitThenApprove() {
        ApprovalContext context = new ApprovalContext("alice", true);

        TransitionResult<OrderState, OrderEvent, ApprovalContext> submitted =
                machine.fire(OrderState.CREATED, OrderEvent.SUBMIT, context);

        Assert.assertTrue(submitted.isAccepted());
        Assert.assertEquals(TransitionOutcome.TRANSITIONED, submitted.getOutcome());
        Assert.assertEquals(OrderState.CREATED, submitted.getFrom());
        Assert.assertEquals(OrderState.SUBMITTED, submitted.getTo());
        Assert.assertEquals(OrderState.SUBMITTED, submitted.getState());
        Assert.assertEquals(OrderEvent.SUBMIT, submitted.getEvent());
        Assert.assertEquals("submit", submitted.getTransitionId());
        Assert.assertSame(context, submitted.getContext());
        Assert.assertEquals("order-approval", submitted.getMachineId());

        TransitionResult<OrderState, OrderEvent, ApprovalContext> approved =
                machine.fire(OrderState.SUBMITTED, OrderEvent.APPROVE, context);

        Assert.assertEquals(OrderState.APPROVED, approved.getTo());
        Assert.assertEquals("approve", approved.getTransitionId());
        Assert.assertEquals(Arrays.asList("submit:alice", "approve:alice"), context.auditTrail);
    }

    @Test
    public void testJuniorApproverIsGuardRejected() {
        ApprovalContext junior = new ApprovalContext("bob", false);

        machine.fire(OrderState.CREATED, OrderEvent.SUBMIT, junior);

        TransitionResult<OrderState, OrderEvent, ApprovalContext> rejected =
                machine.tryFire(OrderState.SUBMITTED, OrderEvent.APPROVE, junior);

        Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, rejected.getOutcome());
        Assert.assertTrue(rejected.isRejected());
        Assert.assertFalse(rejected.isAccepted());
        Assert.assertNull(rejected.getTo());
        Assert.assertNull(rejected.getTransition());
        Assert.assertNull(rejected.getTransitionId());
        Assert.assertEquals(OrderState.SUBMITTED, rejected.getState());

        try {
            machine.fire(OrderState.SUBMITTED, OrderEvent.APPROVE, junior);
            Assert.fail("非资深审批人应当被拒绝");
        } catch (TransitionRejectedException e) {
            Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, e.getOutcome());
            Assert.assertEquals(OrderState.SUBMITTED, e.getState());
            Assert.assertEquals(OrderEvent.APPROVE, e.getEvent());
            Assert.assertEquals("order-approval", e.getMachineId());
        }
    }

    @Test
    public void testRejectPathAndGlobalCancelFromAnyState() {
        ApprovalContext context = new ApprovalContext("carol", true);

        TransitionResult<OrderState, OrderEvent, ApprovalContext> rejected =
                machine.fire(OrderState.SUBMITTED, OrderEvent.REJECT, context);
        Assert.assertEquals(OrderState.REJECTED, rejected.getTo());
        Assert.assertEquals("reject", rejected.getTransitionId());

        // fromAny 规则可从任意状态触发，包括精确规则中已是终态的状态
        TransitionResult<OrderState, OrderEvent, ApprovalContext> cancelled =
                machine.fire(OrderState.REJECTED, OrderEvent.CANCEL, context);
        Assert.assertEquals(OrderState.CANCELLED, cancelled.getTo());
        Assert.assertEquals("global-cancel", cancelled.getTransitionId());
    }

    @Test
    public void testTypedContextVisibleToGuardAndAction() {
        final List<String> seen = new ArrayList<String>();

        StateMachine<OrderState, OrderEvent, ApprovalContext> typed = StateMachine
                .<OrderState, OrderEvent, ApprovalContext>builder("typed", OrderState.CREATED)
                .from(OrderState.CREATED).on(OrderEvent.SUBMIT)
                    .when("has operator", ctx -> ctx.getContext().operator != null)
                    .to(OrderState.SUBMITTED)
                    .named("typed-submit")
                    .action(ctx -> seen.add(ctx.getMachineId() + "/" + ctx.getTransitionId()
                            + "/" + ctx.getFrom() + "->" + ctx.getTo()
                            + "/" + ctx.getEvent() + "/" + ctx.getContext().operator))
                .build();

        TransitionResult<OrderState, OrderEvent, ApprovalContext> result =
                typed.fire(OrderState.CREATED, OrderEvent.SUBMIT, new ApprovalContext("dave", false));

        Assert.assertTrue(result.isAccepted());
        Assert.assertEquals(Collections.singletonList(
                "typed/typed-submit/CREATED->SUBMITTED/SUBMIT/dave"), seen);

        // 守卫可以读取强类型上下文并拒绝迁移
        TransitionResult<OrderState, OrderEvent, ApprovalContext> denied =
                typed.tryFire(OrderState.CREATED, OrderEvent.SUBMIT, new ApprovalContext(null, false));
        Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, denied.getOutcome());
        Assert.assertEquals(1, seen.size());
    }
}
