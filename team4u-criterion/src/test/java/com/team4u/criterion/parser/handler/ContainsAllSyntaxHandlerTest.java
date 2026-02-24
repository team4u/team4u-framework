package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.ContainsAllCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.VariableValue;

/**
 * ContainsAll 语法处理器单元测试
 */
public class ContainsAllSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseStaticList() {
        // 测试静态列表包含所有
        ContainsAllCriterion c = parseLeaf("it containsAll [1, 2]", ContainsAllCriterion.class);
        Assert.assertEquals(2, c.getValues().size());
        Assert.assertEquals(1L, ((FixedValue<?>) c.getValues().get(0)).get(null));
        Assert.assertEquals(2L, ((FixedValue<?>) c.getValues().get(1)).get(null));
    }

    @Test
    public void testParseVariable() {
        // 测试动态变量包含所有
        ContainsAllCriterion c = parseLeaf("it containsAll $roles", ContainsAllCriterion.class);
        Assert.assertEquals(1, c.getValues().size());
        Assert.assertTrue(c.getValues().get(0) instanceof VariableValue);
        Assert.assertEquals("roles", ((VariableValue<?>) c.getValues().get(0)).getVariableName());
    }
}
