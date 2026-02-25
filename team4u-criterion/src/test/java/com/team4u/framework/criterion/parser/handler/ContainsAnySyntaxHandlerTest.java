package com.team4u.framework.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.model.ContainsAnyCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;
import com.team4u.framework.criterion.model.value.VariableValue;

/**
 * ContainsAny 语法处理器单元测试
 */
public class ContainsAnySyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseStaticList() {
        // 测试静态列表解析
        ContainsAnyCriterion c = parseLeaf("it containsAny ['a', 'b']", ContainsAnyCriterion.class);
        Assert.assertEquals(2, c.getValues().size());
        Assert.assertEquals("a", ((FixedValue<?>) c.getValues().get(0)).get(null));
        Assert.assertEquals("b", ((FixedValue<?>) c.getValues().get(1)).get(null));
    }

    @Test
    public void testParseSingleValue() {
        // 测试单值解析
        ContainsAnyCriterion c = parseLeaf("it containsAny 'a'", ContainsAnyCriterion.class);
        Assert.assertEquals(1, c.getValues().size());
        Assert.assertEquals("a", ((FixedValue<?>) c.getValues().get(0)).get(null));
    }

    @Test
    public void testParseVariable() {
        // 测试动态变量解析
        ContainsAnyCriterion c = parseLeaf("it containsAny $myTags", ContainsAnyCriterion.class);
        Assert.assertEquals(1, c.getValues().size());
        Assert.assertTrue(c.getValues().get(0) instanceof VariableValue);
        Assert.assertEquals("myTags", ((VariableValue<?>) c.getValues().get(0)).getVariableName());
    }
}
