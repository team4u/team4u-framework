package com.team4u.framework.criterion.compiler.impl;

import lombok.Getter;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import com.team4u.framework.criterion.MatchContext;
import com.team4u.framework.criterion.MatchPredicate;
import com.team4u.framework.criterion.compiler.AbstractCriterionCompiler;
import com.team4u.framework.criterion.compiler.CompilerRegistry;
import com.team4u.framework.criterion.compiler.CompilingVisitor;
import com.team4u.framework.criterion.compiler.CriterionCompiler;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.CriterionVisitor;
import com.team4u.framework.criterion.model.LogicCriterion;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LogicCriterion 单元测试
 * <p>
 * 直接手动构建对象树，测试嵌套逻辑、短路行为、异常处理和空列表处理
 */
public class LogicCriterionCompilerTest {

    private final CompilerRegistry registry = new CompilerRegistry();

    @Before
    public void setUp() {
        // 注册测试用的 Matcher
        registry.register(new ConstantMatcher());
        registry.register(new TrackingMatcher());
        registry.register(new ExceptionMatcher());
        registry.register(new LogicCriterionCompiler());
    }

    @After
    public void tearDown() {
        // 清理测试用的 Matcher
        registry.unregisterByType(ConstantMatcher.class);
        registry.unregisterByType(TrackingMatcher.class);
        registry.unregisterByType(ExceptionMatcher.class);
    }

    // ==================== 逻辑嵌套与基本匹配测试 ====================

    @Test
    public void nestedLogicAOrBAndC() {
        // (A OR B) AND C
        // A=true, B=false, C=true => true
        Criterion a = new ConstantCriterion(true);
        Criterion b = new ConstantCriterion(false);
        Criterion c = new ConstantCriterion(true);

        LogicCriterion aOrB = new LogicCriterion(LogicCriterion.Operator.OR, Arrays.asList(a, b));
        LogicCriterion aOrBAndC = new LogicCriterion(LogicCriterion.Operator.AND, Arrays.asList(aOrB, c));

        Assert.assertTrue(matches(aOrBAndC));
    }

    @Test
    public void nestedLogicAOrBAndCWhenAOrBFalse() {
        // (A OR B) AND C
        // A=false, B=false, C=true => false
        Criterion a = new ConstantCriterion(false);
        Criterion b = new ConstantCriterion(false);
        Criterion c = new ConstantCriterion(true);

        LogicCriterion aOrB = new LogicCriterion(LogicCriterion.Operator.OR, Arrays.asList(a, b));
        LogicCriterion aOrBAndC = new LogicCriterion(LogicCriterion.Operator.AND, Arrays.asList(aOrB, c));

        Assert.assertFalse(matches(aOrBAndC));
    }

    // ==================== 短路行为测试 ====================

    @Test
    public void orShortCircuitWhenFirstIsTrue() {
        AtomicInteger callCount = new AtomicInteger(0);
        Criterion first = new TrackingCriterion(true, callCount);
        Criterion second = new TrackingCriterion(false, callCount);

        LogicCriterion or = new LogicCriterion(LogicCriterion.Operator.OR, Arrays.asList(first, second));
        Assert.assertTrue(matches(or));
        Assert.assertEquals(1, callCount.get()); // 只有第一个节点被调用
    }

    @Test
    public void andShortCircuitWhenFirstIsFalse() {
        AtomicInteger callCount = new AtomicInteger(0);
        Criterion first = new TrackingCriterion(false, callCount);
        Criterion second = new TrackingCriterion(true, callCount);

        LogicCriterion and = new LogicCriterion(LogicCriterion.Operator.AND, Arrays.asList(first, second));
        Assert.assertFalse(matches(and));
        Assert.assertEquals(1, callCount.get()); // 只有第一个节点被调用
    }

    // ==================== 异常处理测试 ====================

    @Test
    public void orShouldContinueOnExceptionInNonStrictMode() {
        // (异常 OR True) -> 非严格模式下应返回 true
        LogicCriterion orCriterion = new LogicCriterion(
                LogicCriterion.Operator.OR,
                Arrays.asList(new ExceptionCriterion(), new ConstantCriterion(true)));

        MatchContext context = MatchContext.of(null).withStrictMode(false);
        Assert.assertTrue("非严格模式下：左侧异常不应阻止右侧条件的评估", matches(orCriterion, context));
    }

    @Test(expected = RuntimeException.class)
    public void orShouldThrowInStrictMode() {
        // (异常 OR True) -> 严格模式下应抛出异常
        LogicCriterion orCriterion = new LogicCriterion(
                LogicCriterion.Operator.OR,
                Arrays.asList(new ExceptionCriterion(), new ConstantCriterion(true)));

        MatchContext context = MatchContext.of(null).withStrictMode(true);
        matches(orCriterion, context);
    }

    @Test
    public void andShouldShortCircuitOnExceptionInNonStrictMode() {
        // (异常 AND True) -> 非严格模式下应返回 false（异常被视为不匹配）
        LogicCriterion andCriterion = new LogicCriterion(
                LogicCriterion.Operator.AND,
                Arrays.asList(new ExceptionCriterion(), new ConstantCriterion(true)));

        MatchContext context = MatchContext.of(null).withStrictMode(false);
        Assert.assertFalse("非严格模式下：左侧异常应导致 AND 逻辑短路并返回 false", matches(andCriterion, context));
    }

    // ==================== 边界条件测试 ====================

    @Test
    public void emptyChildrenLogic() {
        Assert.assertTrue(matches(new LogicCriterion(LogicCriterion.Operator.AND, Collections.emptyList())));
        Assert.assertFalse(matches(new LogicCriterion(LogicCriterion.Operator.OR, Collections.emptyList())));
    }

    // ==================== 辅助方法 ====================

    private boolean matches(Criterion criterion) {
        return matches(criterion, MatchContext.of(null));
    }

    private boolean matches(Criterion criterion, MatchContext context) {
        CompilingVisitor visitor = new CompilingVisitor(registry);
        return criterion.accept(visitor).test(context);
    }

    // ==================== 辅助测试类 ====================

    @Getter
    private static class ConstantCriterion extends Criterion {
        private final boolean value;

        ConstantCriterion(boolean value) {
            this.value = value;
        }

        @Override
        public <R> R accept(CriterionVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    private static class ConstantMatcher extends AbstractCriterionCompiler<ConstantCriterion> {
        @Override
        public MatchPredicate compile(ConstantCriterion c, CriterionVisitor<MatchPredicate> v) {
            return ctx -> c.isValue();
        }

        @Override
        public Class<? extends Criterion> key() {
            return ConstantCriterion.class;
        }
    }

    private static class TrackingCriterion extends Criterion {
        @Getter
        private final boolean value;
        private final AtomicInteger callCount;

        TrackingCriterion(boolean value, AtomicInteger callCount) {
            this.value = value;
            this.callCount = callCount;
        }

        public void increment() {
            callCount.incrementAndGet();
        }

        @Override
        public <R> R accept(CriterionVisitor<R> visitor) {
            return null;
        }
    }

    private static class TrackingMatcher extends AbstractCriterionCompiler<TrackingCriterion> {
        @Override
        public MatchPredicate compile(TrackingCriterion c, CriterionVisitor<MatchPredicate> v) {
            return ctx -> {
                c.increment();
                return c.isValue();
            };
        }

        @Override
        public Class<? extends Criterion> key() {
            return TrackingCriterion.class;
        }
    }

    private static class ExceptionCriterion extends Criterion {
        @Override
        public <R> R accept(CriterionVisitor<R> visitor) {
            return visitor.visit(this);
        }
    }

    private static class ExceptionMatcher implements CriterionCompiler<ExceptionCriterion> {
        @Override
        public MatchPredicate compile(ExceptionCriterion c, CriterionVisitor<MatchPredicate> v) {
            return ctx -> {
                throw new RuntimeException("测试异常");
            };
        }

        @Override
        public Class<? extends Criterion> key() {
            return ExceptionCriterion.class;
        }
    }
}
