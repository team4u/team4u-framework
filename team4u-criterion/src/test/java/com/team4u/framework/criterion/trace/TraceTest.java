package com.team4u.framework.criterion.trace;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.MatchContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 表达式追踪树测试
 */
public class TraceTest {

    private final Criteria criteria = Criteria.global();

    @Test
    public void testTraceStructure() {
        // 表达式：年龄大于 18 并且 (角色是 root 或者 角色是 admin)
        String expr = "age > 18 && (role == 'root' || role == 'admin')";

        Map<String, Object> context = new HashMap<>();
        context.put("age", 20);
        context.put("role", "admin");

        // 执行追踪
        TraceNode root = criteria.trace(expr, context);

        // 打印树形结构
        System.out.println("\n=== 树形结构 ===");
        System.out.println(root.render());

        // 验证根节点 (AND 逻辑)
        Assert.assertEquals("LogicCriterion", root.getType());
        Assert.assertTrue(root.isMatched());
        Assert.assertEquals(2, root.getChildren().size());

        // 验证第一个子节点：age > 18
        TraceNode child1 = root.getChildren().get(0);
        Assert.assertEquals("PropertyCriterion", child1.getType());
        TraceNode numberNode = child1.getChildren().get(0);
        Assert.assertEquals("SmartCompareCriterion", numberNode.getType());
        Assert.assertEquals(20, numberNode.getInput());

        // 验证第二个子节点：OR 逻辑
        TraceNode child2 = root.getChildren().get(1);
        Assert.assertEquals("LogicCriterion", child2.getType());

        // OR 逻辑因为短路特性，第一个 'root' 匹配失败后，第二个 'admin' 会继续执行并匹配
        Assert.assertEquals(2, child2.getChildren().size());
        TraceNode roleNode = child2.getChildren().get(1);
        TraceNode stringNode = roleNode.getChildren().get(0);
        Assert.assertEquals("SmartCompareCriterion", stringNode.getType());
        Assert.assertEquals("admin", stringNode.getInput());

        // 断言渲染链图形
        Assert.assertEquals(
                "(age > 18 {20}[Y] AND (role == 'root' {\"admin\"}[N] OR role == 'admin' {\"admin\"}[Y])[Y])[Y]",
                root.render());
    }

    @Test
    public void testShortCircuitTrace() {
        // 测试 AND 短路：第一个条件失败，后面不应追踪
        String expr = "age > 18 && role == 'admin'";
        Map<String, Object> context = new HashMap<>();
        context.put("age", 10); // 10 不大于 18

        TraceNode root = criteria.trace(expr, context);

        System.out.println("\n=== 短路树形结构 ===");
        System.out.println(root.render());

        Assert.assertFalse(root.isMatched());
        // 因为第一个条件 false，第二个条件短路未执行，所以 children 只有一个
        Assert.assertEquals(1, root.getChildren().size());
        Assert.assertFalse(root.getChildren().get(0).isMatched());

        // 断言渲染链图形
        Assert.assertEquals("(age > 18 {10}[N])[N]", root.render());
    }

    @Test
    public void testTraceWithSimpleExpression() {
        // 简单表达式测试
        check("it > 18", 20, "> 18 {20}[Y]");
    }

    @Test
    public void testNestedPropertyTrace() {
        // 嵌套属性测试
        check("user.age > 18",
                Collections.singletonMap("user", Collections.singletonMap("age", 25)),
                "user.age > 18 {25}[Y]");
    }

    @Test
    public void testNullExpression() {
        TraceNode root = criteria.trace(null, new HashMap<>());
        Assert.assertNull(root);
    }

    @Test
    public void testBetweenExpression() {
        // 测试 Between: age between [18, 30]
        check("age between [18, 30]",
                Collections.singletonMap("age", 25),
                "age between [ 18 , 30 ] {25}[Y]");
    }

    @Test
    public void testInExpression() {
        // 测试 In: role in ['admin', 'root']
        check("role in ['admin', 'root']",
                Collections.singletonMap("role", "admin"),
                "role in [ 'admin' , 'root' ] {\"admin\"}[Y]");
    }

    @Test
    public void testContainsExpression() {
        // 测试 Contains: name contains 'test'
        check("name contains 'test'",
                Collections.singletonMap("name", "this is a test"),
                "name contains 'test' {\"this is a test\"}[Y]");
    }

    @Test
    public void testRegexExpression() {
        // 测试 Regex: email =~ '.*@.*'
        check("email =~ '.*@.*'",
                Collections.singletonMap("email", "test@example.com"),
                "email =~ '.*@.*' {\"test@example.com\"}[Y]");
    }

    @Test
    public void testProbabilityExpression() {
        // 测试 Probability: it prob 0.5
        String expr = "it prob 0.5";

        TraceNode root = criteria.trace(expr, new HashMap<>());

        System.out.println("\n=== Probability 树形结构 ===");
        System.out.println(root.render());

        Assert.assertNotNull(root);
        // 概率匹配可能为 true 或 false，所以不检查结果

        // 断言渲染链图形格式（prob 0.5，输入为空 Map）
        String render = root.render();
        Assert.assertTrue(render.startsWith("prob 0.5"));
        Assert.assertTrue(render.contains("[Y]") || render.contains("[N]"));
    }

    @Test
    public void testWildcardExpression() {
        // 测试 Wildcard: name like 'test*'
        check("name like 'test*'",
                Collections.singletonMap("name", "testing"),
                "name like 'test*' {\"testing\"}[Y]");
    }

    @Test
    public void testNullCriterion() {
        // 测试 Null: age is null
        check("age is null",
                Collections.singletonMap("age", null),
                "age is null {null}[Y]");
    }

    @Test
    public void testDynamicExpression() {
        // 使用新的 Builder API 配置自定义算子
        Criteria dynamicCriteria = Criteria.builder()
                .addOperator("~=", (actual, expected) -> {
                    if (actual == null || expected == null)
                        return false;
                    return Math.abs(Double.parseDouble(actual.toString()) -
                            Double.parseDouble(expected.toString())) < 0.01;
                })
                .build();

        // 测试自定义算子: price ~= 100
        String expr = "price ~= 100";
        Map<String, Object> context = new HashMap<>();
        context.put("price", 100.005);

        TraceNode root = dynamicCriteria.trace(expr, context);

        System.out.println("\n=== Dynamic 树形结构 ===");
        System.out.println(root.render());

        Assert.assertNotNull(root);
        Assert.assertTrue(root.isMatched());
        // 验证输出包含捕获的原始表达式
        Assert.assertTrue(root.render().contains("price ~= 100 {100.005}[Y]"));

        // 断言渲染链图形
        Assert.assertEquals("price ~= 100 {100.005}[Y]", root.render());
    }

    @Test
    public void testVariableValueExpression() {
        // 表达式：age > $minAge
        String expr = "age > $minAge";
        Map<String, Object> actual = new HashMap<>();
        actual.put("age", 25);

        // 使用 MatchContext 传递额外属性 (minAge)
        MatchContext context = MatchContext.of(actual)
                .setAttribute("minAge", 18);

        TraceNode root = criteria.trace(expr, context);

        System.out.println("\n=== Variable Value 树形结构 ===");
        System.out.println(root.render());

        Assert.assertNotNull(root);
        Assert.assertTrue(root.isMatched());

        // 断言渲染链图形
        Assert.assertEquals("age > $minAge {25}[Y]", root.render());
    }

    private void check(String expression, Object context, String expectedRender) {
        TraceNode root = criteria.trace(expression, context);

        System.out.println("\n=== 树形结构 ===");
        System.out.println(root.render());

        Assert.assertNotNull(root);
        Assert.assertTrue(root.isMatched());
        Assert.assertEquals(expectedRender, root.render());
    }
}
