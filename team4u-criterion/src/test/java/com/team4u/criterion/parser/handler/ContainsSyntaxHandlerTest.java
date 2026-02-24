package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.ContainsCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.VariableValue;

/**
 * Contains 语法处理器单元测试
 */
public class ContainsSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseStaticValue() {
        // 测试静态单值包含
        ContainsCriterion c = parseLeaf("it contains 'vip'", ContainsCriterion.class);
        Assert.assertEquals("vip", ((FixedValue<?>) c.getValueProvider()).get(null));
    }

    @Test
    public void testParseVariable() {
        // 测试动态变量包含
        ContainsCriterion c = parseLeaf("it contains $role", ContainsCriterion.class);
        Assert.assertTrue(c.getValueProvider() instanceof VariableValue);
        Assert.assertEquals("role", ((VariableValue<?>) c.getValueProvider()).getVariableName());
    }
}
