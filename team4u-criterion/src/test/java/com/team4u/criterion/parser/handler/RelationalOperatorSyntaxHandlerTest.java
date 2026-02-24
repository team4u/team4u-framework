package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.SmartCompareCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.VariableValue;

/**
 * 关系运算符语法处理器单元测试
 */
public class RelationalOperatorSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseRelational() {
        // 测试数字比较
        SmartCompareCriterion c1 = parseLeaf("it > 100", SmartCompareCriterion.class);
        Assert.assertEquals(">", c1.getOperator());
        Assert.assertEquals(100L, ((FixedValue<?>) c1.getValueProvider()).get(null));

        // 测试字符串等于
        SmartCompareCriterion c2 = parseLeaf("it == 'foo'", SmartCompareCriterion.class);
        Assert.assertEquals("==", c2.getOperator());
        Assert.assertEquals("foo", ((FixedValue<?>) c2.getValueProvider()).get(null));

        // 测试动态变量
        SmartCompareCriterion c3 = parseLeaf("it != $minAge", SmartCompareCriterion.class);
        Assert.assertEquals("!=", c3.getOperator());
        Assert.assertTrue(c3.getValueProvider() instanceof VariableValue);
        Assert.assertEquals("minAge", ((VariableValue<?>) c3.getValueProvider()).getVariableName());
    }

    @Test
    public void testParsePropertyComparison() {
        // 测试属性间比较
        SmartCompareCriterion c = parseLeaf("age > $limit", SmartCompareCriterion.class);
        Assert.assertEquals(">", c.getOperator());
        Assert.assertTrue(c.getValueProvider() instanceof VariableValue);
    }
}
