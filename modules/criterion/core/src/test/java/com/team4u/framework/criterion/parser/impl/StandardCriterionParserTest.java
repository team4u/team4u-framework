package com.team4u.framework.criterion.parser.impl;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.Criteria;
import com.team4u.framework.criterion.model.Criterion;
import com.team4u.framework.criterion.model.LogicCriterion;
import com.team4u.framework.criterion.model.PropertyCriterion;

/**
 * 标准规则解析器集成测试
 * <p>
 * 侧重于验证解析器的整体流程、多插件协作以及复杂的逻辑嵌套场景。
 * 具体的语法解析细节已迁移至各 SyntaxHandler 对应的单元测试类中。
 */
public class StandardCriterionParserTest {

    private final Criteria criteria = Criteria.builder().build();

    @Test
    public void testParseRelational() {
        // 验证基本关系运算路由正常
        Assert.assertNotNull(criteria.parse("it > 100"));
        Assert.assertNotNull(criteria.parse("it == 'foo'"));
    }

    @Test
    public void testParseIn() {
        // 验证集合运算路由正常
        Assert.assertNotNull(criteria.parse("it in [1, 2]"));
        Assert.assertNotNull(criteria.parse("it not in [50]"));
    }

    @Test
    public void testParseBetween() {
        // 验证区间运算路由正常
        Assert.assertNotNull(criteria.parse("it between [10, 20]"));
    }

    @Test
    public void testParseContains() {
        // 验证包含运算路由正常
        Assert.assertNotNull(criteria.parse("it contains 'vip'"));
        Assert.assertNotNull(criteria.parse("it containsAll [1, 2]"));
        Assert.assertNotNull(criteria.parse("it containsAny ['a', 'b']"));
    }

    @Test
    public void testParsePattern() {
        // 验证模式匹配路由正常
        Assert.assertNotNull(criteria.parse("it =~ '^A.*'"));
        Assert.assertNotNull(criteria.parse("it like 'user*'"));
    }

    @Test
    public void testParseProbability() {
        // 验证概率与分流路由正常
        Assert.assertNotNull(criteria.parse("it prob 0.35"));
        Assert.assertNotNull(criteria.parse("userId hash 0.1"));
    }

    @Test
    public void testParseLogic() {
        // 核心集成验证：验证多插件在复杂逻辑嵌套下的协作
        Criterion c = criteria.parse("a > 10 && (b < 20 || c == 30)");
        Assert.assertTrue(c instanceof LogicCriterion);
        LogicCriterion root = (LogicCriterion) c;
        Assert.assertEquals(LogicCriterion.Operator.AND, root.getOperator());
        Assert.assertEquals(2, root.getChildren().size());

        // 验证第一个子节点
        PropertyCriterion p1 = (PropertyCriterion) root.getChildren().get(0);
        Assert.assertEquals("a", p1.getName());

        // 验证第二个子节点是否为嵌套逻辑
        Assert.assertTrue(root.getChildren().get(1) instanceof LogicCriterion);
    }

    @Test
    public void testParseSpecial() {
        // 验证特殊语法路由正常
        Assert.assertNotNull(criteria.parse("it is null"));
        Assert.assertNotNull(criteria.parse("'foo'"));
    }

    @Test
    public void testParseExceptionWithSourceSpan() {
        try {
            criteria.parse("a >");
            Assert.fail("Expected CriterionParseException");
        } catch (com.team4u.framework.criterion.parser.CriterionParseException ex) {
            Assert.assertNotNull(ex.span());
            Assert.assertEquals(1, ex.span().startLine());
            Assert.assertTrue(ex.getMessage().contains("a >"));
        }
    }
}