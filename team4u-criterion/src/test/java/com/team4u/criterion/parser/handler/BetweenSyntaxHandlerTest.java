package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.BetweenCriterion;
import com.team4u.criterion.model.value.FixedValue;

/**
 * Between 语法处理器单元测试
 */
public class BetweenSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseBetween() {
        // 闭区间 [10, 20]
        BetweenCriterion c1 = parseLeaf("it between [10, 20]", BetweenCriterion.class);
        Assert.assertTrue(c1.isIncludeLower());
        Assert.assertTrue(c1.isIncludeUpper());
        Assert.assertEquals(10L, ((FixedValue<?>) c1.getLowerProvider()).get(null));
        Assert.assertEquals(20L, ((FixedValue<?>) c1.getUpperProvider()).get(null));

        // 左闭右开 [10, 20)
        BetweenCriterion c2 = parseLeaf("it between [10, 20)", BetweenCriterion.class);
        Assert.assertTrue(c2.isIncludeLower());
        Assert.assertFalse(c2.isIncludeUpper());

        // 全开区间 (10, 20)
        BetweenCriterion c3 = parseLeaf("it between (10, 20)", BetweenCriterion.class);
        Assert.assertFalse(c3.isIncludeLower());
        Assert.assertFalse(c3.isIncludeUpper());
    }
}
