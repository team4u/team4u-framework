package com.team4u.framework.criterion;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.compiler.impl.DynamicCriterionCompiler;
import com.team4u.framework.criterion.model.CriterionEvaluationException;
import com.team4u.framework.criterion.model.DynamicCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Criteria 核心入口单元测试
 */
public class CriteriaTest {

    private final Criteria criteria = Criteria.standard();

    @Test
    public void testComplexExpression() {
        String expr = "age >= 18 && (role == 'admin' || role == 'root')";

        Map<String, Object> admin = new HashMap<>();
        admin.put("age", 20);
        admin.put("role", "admin");
        Assert.assertTrue(criteria.matches(expr, admin));

        Map<String, Object> guest = new HashMap<>();
        guest.put("age", 20);
        guest.put("role", "guest");
        Assert.assertFalse(criteria.matches(expr, guest));

        Map<String, Object> youngAdmin = new HashMap<>();
        youngAdmin.put("age", 16);
        youngAdmin.put("role", "admin");
        Assert.assertFalse(criteria.matches(expr, youngAdmin));
    }

    @Test
    public void testFallback() {
        String expr = "age > 10";
        Map<String, Object> user = new HashMap<>();
        user.put("age", 18);
        Assert.assertTrue(criteria.matches(expr, user));
    }

    @Test
    public void testGetVariables() {
        Set<String> vars = criteria.getVariables("age > $minAge && role == 'admin' || score between [60, $maxScore]");
        Assert.assertTrue(vars.contains("age"));
        Assert.assertTrue(vars.contains("minAge"));
        Assert.assertTrue(vars.contains("role"));
        Assert.assertTrue(vars.contains("score"));
        Assert.assertTrue(vars.contains("maxScore"));
    }

    @Test
    public void testCustomOperator() {
        Criteria customCriteria = Criteria.builder()
                .addOperator("myOp", (actual, expected) -> "custom".equals(actual) && "logic".equals(expected))
                .addOperator("startsWith", (a, e) -> a != null && a.toString().startsWith(e.toString()))
                .build();

        Assert.assertTrue(customCriteria.matches("it myOp 'logic'", "custom"));
        Assert.assertTrue(customCriteria.matches("it startsWith 'foo'", "foobar"));
        Assert.assertTrue(customCriteria.matches("it MYOP 'logic'", "custom"));
    }

    @Test
    public void testDefaultModeShouldSwallowException() {
        MatchContext context = MatchContext.of("not a number");
        Assert.assertFalse("默认情况下出错应返回 false", criteria.matches("it > 10", context));
    }

    @Test(expected = CriterionEvaluationException.class)
    public void testStrictModeShouldThrowException() {
        MatchContext context = MatchContext.of("not a number").withStrictMode(true);
        criteria.matches("it > 10", context);
    }

    @Test
    public void testDynamicCriterionStrictMode() {
        MatchContext context = MatchContext.of(null).withStrictMode(true);
        DynamicCriterion criterion = new DynamicCriterion(
                "test",
                new FixedValue<Object>(null),
                (a, b) -> {
                    throw new RuntimeException("Dynamic Failure");
                });

        try {
            new DynamicCriterionCompiler().compile(criterion, null).test(context);
            Assert.fail("严格模式下应抛出 CriterionEvaluationException");
        } catch (CriterionEvaluationException e) {
            Assert.assertEquals("Dynamic Failure", e.getCause().getMessage());
        }
    }

    @Test
    public void testPerformance() {
        String expr = "age >= 18 && (role == 'admin' || role == 'root')";
        Map<String, Object> admin = new HashMap<>();
        admin.put("age", 20);
        admin.put("role", "admin");

        for (int i = 0; i < 1000; i++) {
            criteria.matches(expr, admin);
        }

        long start = System.currentTimeMillis();
        for (int i = 0; i < 100000; i++) {
            criteria.matches(expr, admin);
        }
        long end = System.currentTimeMillis();
        System.out.println("10万次匹配耗时: " + (end - start) + "ms");
    }
}
