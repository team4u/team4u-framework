package com.team4u.framework.fsm;

import com.team4u.framework.fsm.exception.StateMachineDefinitionException;
import com.team4u.framework.fsm.exception.TransitionExecutionException;
import com.team4u.framework.fsm.exception.TransitionRejectedException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 状态机执行语义测试：优先级分层、守卫、动作、异常包装与并发复用。
 */
public class StateMachineExecutionTest {

    private enum State { A, B, C, D, X, END }

    private enum Event { E1, E2, E3, UNKNOWN }

    private static final class Ctx {
        final String tag;

        Ctx(String tag) {
            this.tag = tag;
        }
    }

    // ------------------------------------------------------------------
    // 分层匹配语义
    // ------------------------------------------------------------------

    @Test
    public void testExactSourceExactEventBeatsWildcardBuckets() {
        // 通配规则先声明，但精确规则必须获胜
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("priority", State.A)
                .fromAny().on(Event.E1).to(State.D).named("any-exact")
                .from(State.A).onAny().to(State.X).named("exact-any")
                .fromAny().onAny().to(State.END).named("any-any")
                .from(State.A).on(Event.E1).to(State.B).named("exact-exact")
                .build();

        Assert.assertEquals("exact-exact", machine.fire(State.A, Event.E1, null).getTransitionId());
        Assert.assertEquals(State.B, machine.nextState(State.A, Event.E1, null));
    }

    @Test
    public void testExactSourceAnyEventBucketFallsBackWhenExactEventMissing() {
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("bucket", State.A)
                .from(State.A).onAny().to(State.B).named("a-any")
                .from(State.B).on(Event.E1).to(State.C).named("b-e1")
                .build();

        // E2 未为 A 精确声明，落入 A 的 onAny 桶
        Assert.assertEquals(State.B, machine.nextState(State.A, Event.E2, null));
        // E1 在 B 上有精确规则
        Assert.assertEquals(State.C, machine.nextState(State.B, Event.E1, null));
        // C 没有任何出边
        try {
            machine.fire(State.C, Event.E1, null);
            Assert.fail("C 无出边应抛拒绝异常");
        } catch (TransitionRejectedException e) {
            Assert.assertEquals(TransitionOutcome.NO_TRANSITION, e.getOutcome());
        }
    }

    @Test
    public void testTwoSingleWildcardBucketsMergedByDeclarationOrder() {
        final List<String> guardOrder = new ArrayList<String>();

        // exact-state/any-event 桶与 any-state/exact-event 桶按全局声明顺序归并
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("merge", State.A)
                // 声明顺序 0：exactStateAnyEvent 桶（A --(*)）
                .from(State.A).onAny()
                    .when("guard-A-any", ctx -> {
                        guardOrder.add("A-any");
                        return false;
                    })
                    .to(State.X).named("a-any-guarded")
                // 声明顺序 1：anyStateExactEvent 桶（* --(E1)））
                .fromAny().on(Event.E1)
                    .when("guard-any-E1", ctx -> {
                        guardOrder.add("any-E1");
                        return false;
                    })
                    .to(State.END).named("any-e1-guarded")
                // 声明顺序 2：anyStateAnyEvent 兜底
                .fromAny().onAny().to(State.C).named("any-any-fallback")
                .build();

        TransitionResult<State, Event, Ctx> result = machine.tryFire(State.A, Event.E1, null);

        Assert.assertTrue(result.isAccepted());
        Assert.assertEquals("any-any-fallback", result.getTransitionId());
        // 两个单边通配桶按声明顺序依次评估，全部拒绝后才落入 any-any
        Assert.assertEquals(Arrays.asList("A-any", "any-E1"), guardOrder);
        Assert.assertEquals(3, result.getEvaluatedTransitionCount());
    }

    @Test
    public void testAnyStateAnyEventFallbackAlwaysMatches() {
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("fallback", State.A)
                .fromAny().onAny().to(State.END).named("catch-all")
                .build();

        // 任意 (state, event) 组合都会命中全局兜底
        for (State state : State.values()) {
            for (Event event : Event.values()) {
                Assert.assertEquals(State.END, machine.nextState(state, event, null));
            }
        }
    }

    @Test
    public void testMergedSingleWildcardBucketsFollowDeclarationOrder() {
        // 两条单边通配规则同时匹配 A + E1：归并后按全局声明顺序取第一条
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("merged-order", State.A)
                .fromAny().on(Event.E1).to(State.C).named("declared-first")
                .from(State.A).onAny().to(State.B).named("declared-second")
                .build();

        Assert.assertEquals("declared-first", machine.fire(State.A, Event.E1, null).getTransitionId());
        // 对 B + E1，只有 any-state/exact-event 匹配
        Assert.assertEquals("declared-first", machine.fire(State.B, Event.E1, null).getTransitionId());
        // 对 A + E2，只有 exact-state/any-event 匹配
        Assert.assertEquals("declared-second", machine.fire(State.A, Event.E2, null).getTransitionId());
    }

    @Test
    public void testSameBucketDeclarationOrderWithGuards() {
        final List<String> evaluated = new ArrayList<String>();

        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("same-bucket", State.A)
                .from(State.A).on(Event.E1)
                    .when("first", ctx -> {
                        evaluated.add("first");
                        return false;
                    })
                    .to(State.B).named("first-guarded")
                .from(State.A).on(Event.E1)
                    .when("second", ctx -> {
                        evaluated.add("second");
                        return true;
                    })
                    .to(State.C).named("second-guarded")
                .build();

        TransitionResult<State, Event, Ctx> result = machine.tryFire(State.A, Event.E1, null);

        Assert.assertTrue(result.isAccepted());
        Assert.assertEquals("second-guarded", result.getTransitionId());
        // 第一条守卫拒绝后继续尝试同桶后续规则
        Assert.assertEquals(Arrays.asList("first", "second"), evaluated);
        Assert.assertEquals(2, result.getEvaluatedTransitionCount());
    }

    @Test
    public void testMultipleGuardedTransitionsWithUnconditionalFallbackLast() {
        // 条件分流的标准写法：多条带守卫的边 + 一条无条件兜底
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("dispatch", State.A)
                .from(State.A).on(Event.E1).when("to B", ctx -> "b".equals(ctx.getContext().tag)).to(State.B)
                .from(State.A).on(Event.E1).when("to C", ctx -> "c".equals(ctx.getContext().tag)).to(State.C)
                .from(State.A).on(Event.E1).to(State.D).named("default-e1")
                .build();

        Assert.assertEquals(State.B, machine.nextState(State.A, Event.E1, new Ctx("b")));
        Assert.assertEquals(State.C, machine.nextState(State.A, Event.E1, new Ctx("c")));
        Assert.assertEquals(State.D, machine.nextState(State.A, Event.E1, new Ctx("other")));
        // 兜底之后不能再声明同桶规则（构建期校验）
        try {
            StateMachine.<State, Event, Ctx>builder("bad-dispatch", State.A)
                    .from(State.A).on(Event.E1).to(State.D)
                    .from(State.A).on(Event.E1).to(State.B)
                    .build();
            Assert.fail("无条件规则之后同桶规则不可达，应构建失败");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("machineId=bad-dispatch"));
        }
    }

    // ------------------------------------------------------------------
    // stay 语义
    // ------------------------------------------------------------------

    @Test
    public void testStayFromExactAndFromAny() {
        final List<String> log = new ArrayList<String>();

        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("stay", State.A)
                .from(State.A).on(Event.E1).stay().named("a-stay")
                    .action(ctx -> log.add("action@" + ctx.getFrom()))
                .fromAny().on(Event.E2).stay().named("any-stay")
                .build();

        // 精确来源的 stay：状态不变，动作照常执行
        TransitionResult<State, Event, Ctx> exact = machine.fire(State.A, Event.E1, null);
        Assert.assertTrue(exact.isAccepted());
        Assert.assertEquals(State.A, exact.getTo());
        Assert.assertEquals(State.A, exact.getState());
        Assert.assertEquals(Collections.singletonList("action@A"), log);

        // 任意来源的 stay：任意状态触发都保持原状态
        TransitionResult<State, Event, Ctx> any = machine.fire(State.D, Event.E2, null);
        Assert.assertTrue(any.isAccepted());
        Assert.assertEquals(State.D, any.getTo());
        Assert.assertEquals("any-stay", any.getTransitionId());
        Assert.assertNull(machine.getTransitions().get(1).getTo());
        Assert.assertTrue(machine.getTransitions().get(1).isStay());
    }

    // ------------------------------------------------------------------
    // 动作语义
    // ------------------------------------------------------------------

    @Test
    public void testActionsRunInDeclarationOrderAndFailFast() {
        final List<String> order = new ArrayList<String>();

        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("actions", State.A)
                .from(State.A).on(Event.E1).to(State.B).named("with-actions")
                    .action(ctx -> order.add("one"))
                    .action(ctx -> {
                        order.add("two");
                        throw new IllegalStateException("boom");
                    })
                    .action(ctx -> order.add("three"))
                .build();

        try {
            machine.fire(State.A, Event.E1, null);
            Assert.fail("动作异常应当向上抛出");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals(TransitionExecutionException.Phase.ACTION, e.getPhase());
            Assert.assertEquals("with-actions", e.getTransitionId());
        }

        // 第二个动作失败后第三个动作不再执行
        Assert.assertEquals(Arrays.asList("one", "two"), order);
    }

    @Test
    public void testActionFailureIsWrappedWithFullDiagnostics() {
        IllegalStateException cause = new IllegalStateException("kaboom");
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("diag", State.A)
                .from(State.A).on(Event.E1).to(State.B).named("failing")
                    .action(ctx -> {
                        throw cause;
                    })
                .build();

        try {
            machine.fire(State.A, Event.E1, new Ctx("ctx"));
            Assert.fail("动作异常应当抛出 TransitionExecutionException");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals(TransitionExecutionException.Phase.ACTION, e.getPhase());
            Assert.assertSame(cause, e.getCause());
            Assert.assertEquals("diag", e.getMachineId());
            Assert.assertEquals("failing", e.getTransitionId());
            Assert.assertEquals(State.A, e.getFrom());
            Assert.assertEquals(Event.E1, e.getEvent());
            Assert.assertEquals(State.B, e.getTo());
            TransitionContext<?, ?, ?> ctx = e.getTransitionContext();
            Assert.assertEquals("diag", ctx.getMachineId());
            Assert.assertEquals("failing", ctx.getTransitionId());
            Assert.assertEquals(State.A, ctx.getFrom());
            Assert.assertEquals(Event.E1, ctx.getEvent());
            Assert.assertEquals(State.B, ctx.getTo());
            Assert.assertNotNull(e.getMessage());
            Assert.assertTrue(e.getMessage().contains("failing"));
        }
    }

    @Test
    public void testActionCanReadTypedContextAndResultCarriesItBack() {
        final List<String> seen = new ArrayList<String>();
        Ctx context = new Ctx("payload");

        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("ctx", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                    .named("read-ctx")
                    .action(ctx -> seen.add(ctx.getContext().tag + "@" + ctx.getTo()))
                .build();

        TransitionResult<State, Event, Ctx> result = machine.fire(State.A, Event.E1, context);
        Assert.assertSame(context, result.getContext());
        Assert.assertEquals(Collections.singletonList("payload@B"), seen);
    }

    // ------------------------------------------------------------------
    // 守卫与动作异常
    // ------------------------------------------------------------------

    @Test
    public void testGuardExceptionFailsFastWithoutTryingOtherCandidates() {
        final AtomicInteger guardCalls = new AtomicInteger();

        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("guard-ex", State.A)
                .from(State.A).on(Event.E1)
                    .when("throws", ctx -> {
                        guardCalls.incrementAndGet();
                        throw new RuntimeException("guard blew up");
                    })
                    .to(State.B).named("throwing-guard")
                .from(State.A).on(Event.E1).to(State.C).named("later-rule")
                .build();

        try {
            machine.tryFire(State.A, Event.E1, null);
            Assert.fail("守卫异常应当快速失败");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals(TransitionExecutionException.Phase.GUARD, e.getPhase());
            Assert.assertEquals("throwing-guard", e.getTransitionId());
            Assert.assertEquals(State.A, e.getFrom());
            Assert.assertEquals(Event.E1, e.getEvent());
            // 守卫阶段的上下文里目标状态是该规则声明的固定目标
            Assert.assertEquals(State.B, e.getTo());
            Assert.assertTrue(e.getCause() instanceof RuntimeException);
        }
        // 后续同桶候选没有被尝试
        Assert.assertEquals(1, guardCalls.get());
    }

    @Test
    public void testActionExceptionDoesNotFallBackToOtherCandidates() {
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("action-ex", State.A)
                .from(State.A).on(Event.E1).to(State.B).named("primary")
                    .action(ctx -> {
                        throw new UnsupportedOperationException("no");
                    })
                .fromAny().onAny().to(State.C).named("global")
                .build();

        try {
            machine.tryFire(State.A, Event.E1, null);
            Assert.fail("动作异常不得回退到其他候选规则");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals(TransitionExecutionException.Phase.ACTION, e.getPhase());
            Assert.assertEquals("primary", e.getTransitionId());
        }
    }

    @Test
    public void testGuardExceptionInMergedSingleWildcardCandidatesFailsFast() {
        final List<String> guardOrder = new ArrayList<String>();

        // 归并后的单边通配流里，先声明的 exact-state/any-event 守卫抛异常时必须快速失败，
        // 不得继续评估同流的 any-state/exact-event 候选或 any-any 兜底
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("merge-guard-ex", State.A)
                .from(State.A).onAny()
                    .when("throws in merged stream", ctx -> {
                        guardOrder.add("a-any");
                        throw new IllegalStateException("guard blew up");
                    })
                    .to(State.B).named("a-any-throwing")
                .fromAny().on(Event.E1)
                    .when("never reached", ctx -> {
                        guardOrder.add("any-e1");
                        return true;
                    })
                    .to(State.C).named("any-e1-unreached")
                .fromAny().onAny().to(State.D).named("fallback-unreached")
                .build();

        try {
            machine.tryFire(State.A, Event.E1, null);
            Assert.fail("归并流中守卫异常应当快速失败");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals(TransitionExecutionException.Phase.GUARD, e.getPhase());
            Assert.assertEquals("a-any-throwing", e.getTransitionId());
            Assert.assertTrue(e.getCause() instanceof IllegalStateException);
        }
        // 后续候选与兜底都未被尝试
        Assert.assertEquals(Collections.singletonList("a-any"), guardOrder);
    }

    // ------------------------------------------------------------------
    // 异常消息诊断
    // ------------------------------------------------------------------

    /** toString 抛异常的诊断用对象。 */
    private static final class HostileToString {
        @Override
        public String toString() {
            throw new IllegalStateException("hostile toString");
        }
    }

    @Test
    public void testExecutionExceptionSurvivesHostileToString() {
        final HostileToString hostileState = new HostileToString();
        final HostileToString hostileEvent = new HostileToString();
        final HostileToString hostileTarget = new HostileToString();
        final IllegalStateException guardCause = new IllegalStateException("guard failure");

        // 来源状态与事件是 toString 会抛异常的对象，目标状态也是；守卫抛出的原始异常必须保留
        StateMachine<Object, Object, Ctx> machine = StateMachine
                .<Object, Object, Ctx>builder("hostile", hostileState)
                .from(hostileState).on(hostileEvent)
                    .when("hostile guarded", ctx -> {
                        throw guardCause;
                    })
                    .to(hostileTarget).named("hostile-rule")
                .build();

        try {
            machine.tryFire(hostileState, hostileEvent, null);
            Assert.fail("守卫异常应当抛出 TransitionExecutionException");
        } catch (TransitionExecutionException e) {
            // 原始守卫异常不被坏 toString 的失败替换
            Assert.assertSame(guardCause, e.getCause());
            Assert.assertEquals(TransitionExecutionException.Phase.GUARD, e.getPhase());
            Assert.assertSame(hostileState, e.getFrom());
            Assert.assertSame(hostileEvent, e.getEvent());
            Assert.assertSame(hostileTarget, e.getTo());
            // 消息仍可构造：占位符指明诊断文本生成失败，而不是丢失整条消息
            String message = e.getMessage();
            Assert.assertNotNull(message);
            Assert.assertTrue(message.contains("machineId=hostile"));
            Assert.assertTrue(message.contains("transitionId=hostile-rule"));
            Assert.assertTrue(message.contains("from=<toString failed: "));
            Assert.assertTrue(message.contains("event=<toString failed: "));
            Assert.assertTrue(message.contains("to=<toString failed: "));
            Assert.assertTrue(message
                    .contains(HostileToString.class.getName() + '>'));
        }

        // 动作阶段同理：动作异常作为 cause 保留，坏 toString 不会替换它
        final IllegalStateException actionCause = new IllegalStateException("action failure");
        StateMachine<Object, Object, Ctx> actionMachine = StateMachine
                .<Object, Object, Ctx>builder("hostile-action", hostileState)
                .from(hostileState).on(hostileEvent).to(hostileTarget).named("hostile-action-rule")
                    .action(ctx -> {
                        throw actionCause;
                    })
                .build();
        try {
            actionMachine.tryFire(hostileState, hostileEvent, null);
            Assert.fail("动作异常应当抛出 TransitionExecutionException");
        } catch (TransitionExecutionException e) {
            Assert.assertSame(actionCause, e.getCause());
            Assert.assertEquals(TransitionExecutionException.Phase.ACTION, e.getPhase());
            Assert.assertNotNull(e.getMessage());
            Assert.assertTrue(e.getMessage().contains("from=<toString failed: "));
        }
    }

    @Test
    public void testRejectedExceptionSurvivesHostileToString() {
        final HostileToString hostileState = new HostileToString();
        final HostileToString hostileEvent = new HostileToString();

        StateMachine<Object, Object, Ctx> machine = StateMachine
                .<Object, Object, Ctx>builder("hostile-reject", hostileState)
                .from(hostileState).on(hostileEvent)
                    .when("never", ctx -> false)
                    .to(new Object())
                .build();

        try {
            machine.fire(hostileState, hostileEvent, null);
            Assert.fail("守卫全部拒绝应当抛 TransitionRejectedException");
        } catch (TransitionRejectedException e) {
            Assert.assertSame(hostileState, e.getState());
            Assert.assertSame(hostileEvent, e.getEvent());
            Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, e.getOutcome());
            String message = e.getMessage();
            Assert.assertNotNull(message);
            Assert.assertTrue(message.contains("machineId=hostile-reject"));
            Assert.assertTrue(message.contains("state=<toString failed: "));
            Assert.assertTrue(message.contains("event=<toString failed: "));
            Assert.assertTrue(message.contains("outcome=GUARD_REJECTED"));
        }
    }

    @Test
    public void testExecutionExceptionOrdinaryMessageFormatUnchanged() {
        // 正常对象的异常消息格式保持与既有约定一致：直接拼接 toString，无额外包装
        IllegalStateException cause = new IllegalStateException("boom");
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("ordinary", State.A)
                .from(State.A).on(Event.E1).to(State.B).named("ordinary-rule")
                    .action(ctx -> {
                        throw cause;
                    })
                .build();

        try {
            machine.fire(State.A, Event.E1, null);
            Assert.fail("动作异常应当抛出");
        } catch (TransitionExecutionException e) {
            Assert.assertEquals("State machine transition failed|machineId=ordinary"
                    + "|transitionId=ordinary-rule|phase=ACTION|from=A|event=E1|to=B",
                    e.getMessage());
        }

        // 拒绝异常的普通消息格式同样不变
        StateMachine<State, Event, Ctx> rejector = StateMachine
                .<State, Event, Ctx>builder("ordinary-reject", State.A)
                .from(State.A).on(Event.E1).when("never", ctx -> false).to(State.B)
                .build();
        try {
            rejector.fire(State.C, Event.E2, null);
            Assert.fail("无候选迁移应当抛出拒绝异常");
        } catch (TransitionRejectedException e) {
            Assert.assertEquals("State machine transition rejected|machineId=ordinary-reject"
                    + "|state=C|event=E2|outcome=NO_TRANSITION", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 拒绝语义
    // ------------------------------------------------------------------

    @Test
    public void testNoTransitionVsGuardRejected() {
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("outcomes", State.A)
                .from(State.A).on(Event.E1).when("never", ctx -> false).to(State.B)
                .build();

        // 守卫拒绝：有候选，全部拒绝
        TransitionResult<State, Event, Ctx> rejected = machine.tryFire(State.A, Event.E1, null);
        Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, rejected.getOutcome());
        Assert.assertTrue(rejected.isRejected());
        Assert.assertEquals(1, rejected.getEvaluatedTransitionCount());
        Assert.assertEquals(State.A, rejected.getState());
        Assert.assertNull(rejected.getTo());
        Assert.assertNull(rejected.getTransition());

        // 无候选：从未声明过的状态触发
        TransitionResult<State, Event, Ctx> none = machine.tryFire(State.X, Event.E1, null);
        Assert.assertEquals(TransitionOutcome.NO_TRANSITION, none.getOutcome());
        Assert.assertEquals(0, none.getEvaluatedTransitionCount());
        Assert.assertEquals(State.X, none.getState());
        Assert.assertNull(none.getTo());
        Assert.assertNull(none.getTransition());

        // fire 对两种拒绝都抛异常，且携带可区分的 outcome
        try {
            machine.fire(State.X, Event.E1, null);
            Assert.fail("无候选迁移时 fire 应抛异常");
        } catch (TransitionRejectedException e) {
            Assert.assertEquals(TransitionOutcome.NO_TRANSITION, e.getOutcome());
            Assert.assertEquals(State.X, e.getState());
            Assert.assertEquals(Event.E1, e.getEvent());
            Assert.assertEquals("outcomes", e.getMachineId());
        }
        try {
            machine.fire(State.A, Event.E1, null);
            Assert.fail("守卫全部拒绝时 fire 应抛异常");
        } catch (TransitionRejectedException e) {
            Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, e.getOutcome());
        }
    }

    @Test
    public void testTwoArgOverloadsUseNullContext() {
        final List<Object> contexts = new ArrayList<Object>();
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("two-arg", State.A)
                .from(State.A).on(Event.E1).to(State.B).named("t")
                    .action(ctx -> contexts.add(ctx.getContext()))
                .build();

        Assert.assertEquals(State.B, machine.nextState(State.A, Event.E1));
        TransitionResult<State, Event, Ctx> r = machine.tryFire(State.A, Event.E1);
        Assert.assertTrue(r.isAccepted());
        // 上下文允许为 null，动作收到 null
        Assert.assertEquals(Arrays.asList(null, null), contexts);
        Assert.assertNull(r.getContext());
    }

    // ------------------------------------------------------------------
    // 并发复用
    // ------------------------------------------------------------------

    @Test
    public void testConcurrentReuseOfSameMachineInstance() throws Exception {
        final AtomicInteger guardHits = new AtomicInteger();
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("concurrent", State.A)
                .fromAny().on(Event.E1)
                    .when("counted", ctx -> {
                        guardHits.incrementAndGet();
                        return true;
                    })
                    .to(State.B).named("shared")
                .build();

        final int threads = 16;
        final int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    for (int j = 0; j < iterations; j++) {
                        TransitionResult<State, Event, Ctx> r = machine.tryFire(State.A, Event.E1, null);
                        if (!r.isAccepted() || r.getTo() != State.B) {
                            return false;
                        }
                    }
                    return true;
                }
            }));
        }
        pool.shutdown();
        Assert.assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<Boolean> f : futures) {
            Assert.assertTrue("并发执行结果必须一致", f.get());
        }
        Assert.assertEquals(threads * iterations, guardHits.get());
    }

    // ------------------------------------------------------------------
    // 输入校验
    // ------------------------------------------------------------------

    @Test
    public void testNullStateOrEventRejected() {
        StateMachine<State, Event, Ctx> machine = StateMachine
                .<State, Event, Ctx>builder("null-input", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .build();

        try {
            machine.tryFire(null, Event.E1, null);
            Assert.fail("null 状态应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
        try {
            machine.tryFire(State.A, null, null);
            Assert.fail("null 事件应当抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
        try {
            machine.fire(null, Event.E1, null);
            Assert.fail();
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            machine.nextState(State.A, null, null);
            Assert.fail();
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            machine.isTerminal(null);
            Assert.fail();
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
