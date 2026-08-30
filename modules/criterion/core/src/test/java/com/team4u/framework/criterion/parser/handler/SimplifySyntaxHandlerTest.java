package com.team4u.framework.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.model.SmartCompareCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;

/**
 * 简化语法处理器单元测试
 */
public class SimplifySyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseSimplify() {
        // Simplify (隐式相等)
        SmartCompareCriterion c = parseLeaf("'foo'", SmartCompareCriterion.class);
        Assert.assertEquals("==", c.getOperator());
        Assert.assertEquals("foo", ((FixedValue<?>) c.getValueProvider()).get(null));
    }
}
