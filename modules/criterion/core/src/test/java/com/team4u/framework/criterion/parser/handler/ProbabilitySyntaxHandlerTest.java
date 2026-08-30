package com.team4u.framework.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.model.ProbabilityCriterion;
import com.team4u.framework.criterion.model.value.FixedValue;

import java.math.BigDecimal;

/**
 * 概率语法处理器单元测试
 */
public class ProbabilitySyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseProbability() {
        // Prob
        ProbabilityCriterion c = parseLeaf("it prob 0.35", ProbabilityCriterion.class);
        Assert.assertEquals(new BigDecimal("0.35"), ((FixedValue<?>) c.getThreshold()).get(null));
    }
}
