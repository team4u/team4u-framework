package com.team4u.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.model.HashProbabilityCriterion;
import com.team4u.criterion.model.value.FixedValue;

import java.math.BigDecimal;

/**
 * Hash 分流语法处理器单元测试
 */
public class HashProbabilitySyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseHashProbability() {
        // Hash
        HashProbabilityCriterion c = parseLeaf("userId hash 0.1", HashProbabilityCriterion.class);
        Assert.assertEquals(new BigDecimal("0.1"), ((FixedValue<?>) c.getThreshold()).get(null));
    }
}
