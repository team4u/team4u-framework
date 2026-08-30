package com.team4u.framework.criterion.parser.handler;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.framework.criterion.model.RegexCriterion;
import com.team4u.framework.criterion.model.WildcardCriterion;

/**
 * 模式匹配语法处理器单元测试
 */
public class StringPatternSyntaxHandlerTest extends AbstractSyntaxHandlerTest {

    @Test
    public void testParseRegex() {
        // Regex
        RegexCriterion c = parseLeaf("it =~ '^A.*'", RegexCriterion.class);
        Assert.assertEquals("^A.*", c.getPattern().pattern());
    }

    @Test
    public void testParseLike() {
        // Wildcard (Like)
        WildcardCriterion c = parseLeaf("it like 'user*'", WildcardCriterion.class);
        Assert.assertEquals("user*", c.getPattern());
    }
}
