package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.InCriterion;
import com.team4u.criterion.model.value.FixedValue;
import com.team4u.criterion.model.value.VariableValue;

/**
 * In 语法处理器单元测试
 */
public class InSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseIn() {
        // 测试 in 列表
        InCriterion c1 = parseLeaf("it in [1, 'a', $var]", InCriterion.class);
        Assert.assertFalse(c1.isNot());
        Assert.assertEquals(3, c1.getValues().size());
        Assert.assertEquals(1L, ((FixedValue<?>) c1.getValues().get(0)).get(null));
        Assert.assertEquals("a", ((FixedValue<?>) c1.getValues().get(1)).get(null));
        Assert.assertTrue(c1.getValues().get(2) instanceof VariableValue);

        // 测试 not in
        InCriterion c2 = parseLeaf("it not in [50]", InCriterion.class);
        Assert.assertTrue(c2.isNot());
        Assert.assertEquals(1, c2.getValues().size());
    }
}
