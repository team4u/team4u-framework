package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.NullCriterion;

/**
 * Is 语法处理器单元测试
 */
public class IsSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseIs() {
        // is null
        NullCriterion c = parseLeaf("it is null", NullCriterion.class);
        Assert.assertEquals(NullCriterion.Type.NULL, c.getType());
    }
}
