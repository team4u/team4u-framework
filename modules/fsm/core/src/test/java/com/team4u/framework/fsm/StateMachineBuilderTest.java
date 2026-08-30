package com.team4u.framework.fsm;

import com.team4u.framework.fsm.exception.StateMachineDefinitionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * 构建期校验与构建器生命周期测试。定义错误可能在声明时立即抛出
 * （如 null 入参、重复命名），也可能延迟到 build() 时暴露（如规则不完整、
 * 空状态机、不可达规则）；本测试同时验证：仅成功的 build() 才会关闭构建器，
 * 可恢复的失败 build() 之后构建器保持可用。
 */
public class StateMachineBuilderTest {

    private enum State { A, B, C }

    private enum Event { E1, E2 }

    // ------------------------------------------------------------------
    // 构建器入参校验
    // ------------------------------------------------------------------

    @Test
    public void testBuilderRejectsIllegalIdAndInitialState() {
        try {
            StateMachine.<State, Event, Void>builder(null, State.A);
            Assert.fail("null 状态机标识应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        try {
            StateMachine.<State, Event, Void>builder("  ", State.A);
            Assert.fail("空白状态机标识应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        try {
            StateMachine.<State, Event, Void>builder("m", null);
            Assert.fail("null 初始状态应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
    }

    @Test
    public void testNullSourceEventTargetRejected() {
        // null 来源状态
        try {
            StateMachine.<State, Event, Void>builder("m", State.A).from(null);
            Assert.fail("null 来源状态应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        // null 事件
        try {
            StateMachine.<State, Event, Void>builder("m", State.A).from(State.A).on(null);
            Assert.fail("null 事件应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        // null 目标状态
        try {
            StateMachine.<State, Event, Void>builder("m", State.A)
                    .from(State.A).on(Event.E1).to(null);
            Assert.fail("null 目标状态应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        // null 守卫 / 空守卫描述
        try {
            StateMachine.<State, Event, Void>builder("m", State.A)
                    .from(State.A).on(Event.E1).when(null, ctx -> true);
            Assert.fail("null 守卫描述应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        try {
            StateMachine.<State, Event, Void>builder("m", State.A)
                    .from(State.A).on(Event.E1).when("d", null);
            Assert.fail("null 守卫应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        // null 动作
        try {
            StateMachine.<State, Event, Void>builder("m", State.A)
                    .from(State.A).on(Event.E1).to(State.B).action(null);
            Assert.fail("null 动作应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        // 空迁移标识
        try {
            StateMachine.<State, Event, Void>builder("m", State.A)
                    .from(State.A).on(Event.E1).to(State.B).named("  ");
            Assert.fail("空白迁移标识应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
    }

    // ------------------------------------------------------------------
    // 定义完整性
    // ------------------------------------------------------------------

    @Test
    public void testEmptyMachineRejected() {
        try {
            StateMachine.<State, Event, Void>builder("empty", State.A).build();
            Assert.fail("空状态机应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("machineId=empty"));
        }
    }

    @Test
    public void testIncompleteRuleRejected() {
        StateMachineBuilder<State, Event, Void> builder = StateMachine
                .<State, Event, Void>builder("incomplete", State.A);
        builder.from(State.A).on(Event.E1).to(State.B);
        // 第二条规则声明了事件但未指定目标
        builder.from(State.B).on(Event.E2);

        try {
            builder.build();
            Assert.fail("缺少目标状态的规则应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test
    public void testRecoverableFailedBuildKeepsBuilderOpen() {
        StateMachineBuilder<State, Event, Void> builder = StateMachine
                .<State, Event, Void>builder("recoverable", State.A);
        builder.from(State.A).on(Event.E1).to(State.B);
        // 保留未完成的事件阶段：已声明事件但未指定目标
        StateMachineBuilder<State, Event, Void>.EventStage stage =
                builder.from(State.B).on(Event.E2);

        // 失败的 build() 是可恢复的：抛出定义异常但不会关闭构建器
        try {
            builder.build();
            Assert.fail("存在未完成规则时 build() 应失败");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("incomplete"));
        }

        // 构建器仍然可用：补齐目标后即可成功构建
        stage.to(State.C);
        StateMachine<State, Event, Void> machine = builder.build();
        Assert.assertEquals(2, machine.getTransitions().size());
        Assert.assertEquals(State.C, machine.nextState(State.B, Event.E2, null));

        // 只有成功的 build() 才关闭构建器
        try {
            builder.from(State.C);
            Assert.fail("成功构建后构建器应已关闭");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been built"));
        }
        try {
            stage.to(State.A);
            Assert.fail("成功构建后已存在的未完成阶段也不得再补齐");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been built"));
        }
    }

    @Test
    public void testDuplicateIdRejected() {
        try {
            StateMachine.<State, Event, Void>builder("dup", State.A)
                    .from(State.A).on(Event.E1).to(State.B).named("same")
                    .from(State.B).on(Event.E2).to(State.C).named("same")
                    .build();
            Assert.fail("重复迁移标识应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("Duplicate transition id: same"));
        }
    }

    @Test
    public void testDoubleNamedRejected() {
        try {
            StateMachine.<State, Event, Void>builder("dbl", State.A)
                    .from(State.A).on(Event.E1).to(State.B).named("first").named("second");
            Assert.fail("重复命名应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been named"));
        }
    }

    @Test
    public void testSameScopeUnreachableAfterUnconditionalRejected() {
        // 同一 (from=A, event=E1) 桶内：无条件规则之后的带守卫规则不可达
        try {
            StateMachine.<State, Event, Void>builder("unreachable", State.A)
                    .from(State.A).on(Event.E1).to(State.B)
                    .from(State.A).on(Event.E1).when("never tried", ctx -> true).to(State.C)
                    .build();
            Assert.fail("无条件兜底之后的同桶规则应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unreachable transition"));
        }

        // 单边通配桶同理：A 的 onAny 无条件规则之后不能再有 A 的 onAny 规则
        try {
            StateMachine.<State, Event, Void>builder("unreachable-any", State.A)
                    .from(State.A).onAny().to(State.B)
                    .from(State.A).onAny().to(State.C)
                    .build();
            Assert.fail("onAny 无条件兜底之后的同桶规则应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unreachable transition"));
        }

        // any-any 全局兜底之后不能再有全局规则
        try {
            StateMachine.<State, Event, Void>builder("unreachable-global", State.A)
                    .fromAny().onAny().to(State.B)
                    .fromAny().onAny().to(State.C)
                    .build();
            Assert.fail("全局兜底之后的全局规则应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unreachable transition"));
        }

        // 带守卫的兜底之后可以继续声明：守卫可能拒绝，后续规则仍可达
        StateMachine<State, Event, Void> legal = StateMachine
                .<State, Event, Void>builder("legal-chain", State.A)
                .from(State.A).on(Event.E1).when("g1", ctx -> false).to(State.B)
                .from(State.A).on(Event.E1).when("g2", ctx -> false).to(State.C)
                .from(State.A).on(Event.E1).to(State.C)
                .build();
        Assert.assertEquals(3, legal.getTransitions().size());
    }

    @Test
    public void testCrossBucketPartialShadowingIsAllowed() {
        // A 的 onAny 无条件规则会遮蔽 (A, E1) 上更晚声明的 any-state/exact-event 规则，
        // 但后者在其他状态上仍可命中，因此构建期必须放行
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("cross-bucket", State.A)
                .from(State.A).onAny().to(State.B).named("a-any")
                .fromAny().on(Event.E1).to(State.C).named("any-e1")
                .build();

        // A + E1：两条单边通配规则归并，先声明的 a-any 胜出（有意的局部遮蔽）
        Assert.assertEquals("a-any", machine.fire(State.A, Event.E1, null).getTransitionId());
        // B + E1：a-any 不适用，any-e1 命中
        Assert.assertEquals("any-e1", machine.fire(State.B, Event.E1, null).getTransitionId());
    }

    @Test
    public void testCrossLayerShadowingIsAllowed() {
        // 全局规则先声明也不会遮蔽后声明的精确规则
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("cross-layer", State.A)
                .fromAny().onAny().to(State.C).named("global-first")
                .from(State.A).on(Event.E1).to(State.B).named("exact-later")
                .build();

        Assert.assertEquals("exact-later", machine.fire(State.A, Event.E1, null).getTransitionId());
        Assert.assertEquals("global-first", machine.fire(State.B, Event.E1, null).getTransitionId());
    }

    // ------------------------------------------------------------------
    // 构建器生命周期
    // ------------------------------------------------------------------

    @Test
    public void testBuilderClosedAfterBuild() {
        StateMachineBuilder<State, Event, Void> builder =
                StateMachine.<State, Event, Void>builder("closed", State.A);

        StateMachine<State, Event, Void> machine = builder
                .from(State.A).on(Event.E1).to(State.B)
                .build();
        Assert.assertNotNull(machine);

        // 构建后再使用任何配置方法都应失败
        try {
            builder.from(State.B);
            Assert.fail("构建后 from 应抛异常");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been built"));
        }
        try {
            builder.fromAny();
            Assert.fail("构建后 fromAny 应抛异常");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        try {
            builder.build();
            Assert.fail("构建后重复 build 应抛异常");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }

        // 链式阶段的 from/fromAny/build 同样被关闭
        StateMachineBuilder<State, Event, Void> chained = StateMachine
                .<State, Event, Void>builder("closed-chained", State.A);
        StateMachineBuilder<State, Event, Void>.ConfiguredTransition stage =
                chained.from(State.A).on(Event.E1).to(State.B);
        stage.build();
        try {
            stage.from(State.B);
            Assert.fail("构建后链式 from 应抛异常");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
        try {
            stage.build();
            Assert.fail("构建后链式 build 应抛异常");
        } catch (StateMachineDefinitionException expected) {
            // 预期
        }
    }

    @Test
    public void testCompletedTransitionCannotAcceptMoreGuards() {
        // 规则完成（to）之后再追加守卫应被拒绝
        StateMachineBuilder<State, Event, Void> builder = StateMachine
                .<State, Event, Void>builder("late-guard", State.A);
        StateMachineBuilder<State, Event, Void>.EventStage stage =
                builder.from(State.A).on(Event.E1);
        stage.to(State.B);

        try {
            stage.when("late", ctx -> true);
            Assert.fail("完成的迁移再追加守卫应被拒绝");
        } catch (StateMachineDefinitionException expected) {
            Assert.assertTrue(expected.getMessage().contains("already been completed"));
        }
    }

    // ------------------------------------------------------------------
    // 自动标识与守卫描述
    // ------------------------------------------------------------------

    @Test
    public void testAutomaticTransitionIds() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("auto-id", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .from(State.B).on(Event.E2).to(State.C)
                .build();

        Assert.assertEquals("transition-1", machine.getTransitions().get(0).getId());
        Assert.assertEquals("transition-2", machine.getTransitions().get(1).getId());
    }

    @Test
    public void testAutomaticIdSkipsExplicitlyNamedCollision() {
        // 显式命名为 "transition-2" 占用了自动标识，第三条自动生成时应避开
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("auto-id-collide", State.A)
                .from(State.A).on(Event.E1).to(State.B)                                  // 自动 transition-1
                .from(State.B).on(Event.E2).to(State.C).named("transition-2")            // 显式占用
                .from(State.C).on(Event.E1).to(State.A)                                  // 自动需避开 transition-2
                .build();

        Assert.assertEquals("transition-1", machine.getTransitions().get(0).getId());
        Assert.assertEquals("transition-2", machine.getTransitions().get(1).getId());
        Assert.assertEquals("transition-3", machine.getTransitions().get(2).getId());
    }

    @Test
    public void testMultipleGuardsCombineDescriptions() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("guard-desc", State.A)
                .from(State.A).on(Event.E1)
                    .when("first condition", ctx -> true)
                    .when("second condition", ctx -> true)
                    .to(State.B)
                .build();

        Transition<State, Event, Void> t = machine.getTransitions().get(0);
        Assert.assertEquals("(first condition) && (second condition)", t.getGuardDescription());
        Assert.assertTrue(t.isGuarded());
    }

    @Test
    public void testMultipleGuardsEvaluateAsShortCircuitAnd() {
        final StringBuilder trace = new StringBuilder();
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("short-circuit", State.A)
                .from(State.A).on(Event.E1)
                    .when("a", ctx -> {
                        trace.append('a');
                        return false;
                    })
                    .when("b", ctx -> {
                        trace.append('b');
                        return true;
                    })
                    .to(State.B)
                .build();

        TransitionResult<State, Event, Void> result = machine.tryFire(State.A, Event.E1, null);
        // 第一个守卫拒绝后第二个守卫不再评估（构建期短路与）
        Assert.assertEquals(TransitionOutcome.GUARD_REJECTED, result.getOutcome());
        Assert.assertEquals("a", trace.toString());
    }

    // ------------------------------------------------------------------
    // 元数据收集
    // ------------------------------------------------------------------

    @Test
    public void testStatesAndEventsDerivedFromDefinition() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("meta", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .fromAny().on(Event.E2).to(State.C)
                .build();

        // 状态包含初始状态、全部来源与目标
        Assert.assertEquals(new java.util.LinkedHashSet<State>(
                Arrays.asList(State.A, State.B, State.C)), machine.getStates());
        // 通配事件的迁移不贡献事件；E2 由精确声明的规则贡献
        Assert.assertEquals(new java.util.LinkedHashSet<Event>(
                Arrays.asList(Event.E1, Event.E2)), machine.getEvents());

        // fromAny + onAny 不贡献状态也不贡献事件
        StateMachine<State, Event, Void> wildcardOnly = StateMachine
                .<State, Event, Void>builder("wild-only", State.A)
                .fromAny().onAny().to(State.B)
                .build();
        Assert.assertEquals(new java.util.LinkedHashSet<State>(
                Arrays.asList(State.A, State.B)), wildcardOnly.getStates());
        Assert.assertTrue(wildcardOnly.getEvents().isEmpty());
    }

    @Test
    public void testStayTransitionDoesNotContributeNewState() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("stay-meta", State.A)
                .from(State.A).on(Event.E1).stay()
                .build();

        Assert.assertEquals(new java.util.LinkedHashSet<State>(
                Arrays.asList(State.A)), machine.getStates());
    }

    @Test
    public void testTerminalDetectionWithGlobalRules() {
        // A 有精确出边；END 只有入边
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("terminal", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .from(State.B).on(Event.E2).to(State.C)
                .build();

        Assert.assertFalse(machine.isTerminal(State.A));
        Assert.assertFalse(machine.isTerminal(State.B));
        Assert.assertTrue(machine.isTerminal(State.C));
        // 初始状态也可能是终态
        StateMachine<State, Event, Void> loop = StateMachine
                .<State, Event, Void>builder("terminal-init", State.A)
                .from(State.B).on(Event.E1).to(State.C)
                .build();
        Assert.assertTrue(loop.isTerminal(State.A));

        // 存在任意全局规则时，所有状态都视为非终态
        StateMachine<State, Event, Void> global = StateMachine
                .<State, Event, Void>builder("terminal-global", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .fromAny().onAny().to(State.A)
                .build();
        Assert.assertFalse(global.isTerminal(State.B));
        Assert.assertFalse(global.isTerminal(State.C));
        // any-state/exact-event 全局规则同样使所有状态非终态
        StateMachine<State, Event, Void> globalExact = StateMachine
                .<State, Event, Void>builder("terminal-global-exact", State.A)
                .fromAny().on(Event.E1).to(State.B)
                .build();
        Assert.assertFalse(globalExact.isTerminal(State.C));
    }

    @Test
    public void testImmutabilityOfExposedCollections() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("immutable", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .build();

        try {
            machine.getTransitions().clear();
            Assert.fail("迁移列表不可修改");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
        try {
            machine.getStates().clear();
            Assert.fail("状态集合不可修改");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
        try {
            machine.getEvents().clear();
            Assert.fail("事件集合不可修改");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
        // 迭代期间的定义不会被运行期修改影响
        Iterator<Transition<State, Event, Void>> it = machine.getTransitions().iterator();
        Assert.assertTrue(it.hasNext());
    }

    // ------------------------------------------------------------------
    // toString
    // ------------------------------------------------------------------

    @Test
    public void testToStringDiagnostics() {
        StateMachine<State, Event, Void> machine = StateMachine
                .<State, Event, Void>builder("ts", State.A)
                .from(State.A).on(Event.E1).when("guarded", ctx -> true).to(State.B)
                .fromAny().onAny().stay()
                .build();

        Assert.assertEquals("StateMachine{id='ts', initialState=A, transitions=2}",
                machine.toString());

        Transition<State, Event, Void> exact = machine.getTransitions().get(0);
        Assert.assertEquals("transition-1: A --(E1)--> B", exact.toString());

        Transition<State, Event, Void> wildcard = machine.getTransitions().get(1);
        Assert.assertEquals("transition-2: * --(*)--> =", wildcard.toString());

        TransitionResult<State, Event, Void> accepted = machine.fire(State.C, Event.E2, null);
        Assert.assertEquals("TransitionResult{machineId='ts', outcome=TRANSITIONED, from=C,"
                + " event=E2, to=C, transitionId=transition-2}", accepted.toString());

        // 没有全局规则时，未声明组合返回 NO_TRANSITION 结果
        StateMachine<State, Event, Void> local = StateMachine
                .<State, Event, Void>builder("ts2", State.A)
                .from(State.A).on(Event.E1).to(State.B)
                .build();
        TransitionResult<State, Event, Void> rejected = local.tryFire(State.B, Event.E1, null);
        Assert.assertEquals("TransitionResult{machineId='ts2', outcome=NO_TRANSITION, from=B,"
                + " event=E1, to=null, transitionId=null}", rejected.toString());
    }

    @Test
    public void testGuardCombinators() throws Exception {
        TransitionGuard<State, Event, Void> yes = ctx -> true;
        TransitionGuard<State, Event, Void> no = ctx -> false;

        TransitionContext<State, Event, Void> ctx = null;
        // and / or / negate 在无异常路径下的组合语义
        Assert.assertFalse(no.and(yes).test(ctx));
        Assert.assertFalse(yes.and(no).test(ctx));
        Assert.assertTrue(yes.and(yes).test(ctx));
        Assert.assertTrue(no.or(yes).test(ctx));
        Assert.assertFalse(no.or(no).test(ctx));
        Assert.assertTrue(no.negate().test(ctx));
        Assert.assertFalse(yes.negate().test(ctx));

        // null 参数被拒绝
        try {
            yes.and(null);
            Assert.fail("and(null) 应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            yes.or(null);
            Assert.fail("or(null) 应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void testGuardAndCombinatorShortCircuit() throws Exception {
        final StringBuilder trace = new StringBuilder();
        TransitionGuard<State, Event, Void> first = ctx -> {
            trace.append('1');
            return false;
        };
        TransitionGuard<State, Event, Void> second = ctx -> {
            trace.append('2');
            return true;
        };

        Assert.assertFalse(first.and(second).test(null));
        Assert.assertEquals("1", trace.toString());
    }

    @Test
    public void testActionAndThenOrder() throws Exception {
        final List<String> order = new ArrayList<String>();
        TransitionAction<State, Event, Void> a = ctx -> order.add("a");
        TransitionAction<State, Event, Void> b = ctx -> order.add("b");

        a.andThen(b).execute(null);
        Assert.assertEquals(Arrays.asList("a", "b"), order);

        try {
            a.andThen(null);
            Assert.fail("andThen(null) 应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
