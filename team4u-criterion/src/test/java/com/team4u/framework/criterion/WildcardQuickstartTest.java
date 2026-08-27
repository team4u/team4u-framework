package com.team4u.framework.criterion;

import com.team4u.framework.criterion.compiler.impl.WildcardCriterionCompiler;
import com.team4u.framework.criterion.model.WildcardCriterion;
import org.junit.Assert;
import org.junit.Test;

/**
 * Public Criterion wildcard quickstart and adapter null contract.
 *
 * <p>The like expression uses the public Criteria DSL. Null-pattern cases use the public
 * WildcardCriterion model and compiler because the expression grammar has no spelling for
 * a null pattern. These are adapter observations: null pattern/null actual is true, a null
 * pattern with another value is false, and a non-null pattern with null actual is false.</p>
 */
public class WildcardQuickstartTest {

    private final Criteria criteria = Criteria.global();
    private final WildcardCriterionCompiler compiler = new WildcardCriterionCompiler();

    @Test
    public void likeExpressionsUseCurrentAntSemantics() {
        Assert.assertTrue(message("a/**", "a/b/c"), criteria.matches("it like 'a/**'", "a/b/c"));
        Assert.assertTrue(message("**/*.java", "x.java"), criteria.matches("it like '**/*.java'", "x.java"));
        Assert.assertFalse(message("*", "a/b"), criteria.matches("it like '*'", "a/b"));
        Assert.assertFalse(message("**", "/"), criteria.matches("it like '**'", "/"));
    }

    @Test
    public void likeTreatsBackslashAsLiteralCharacter() {
        Assert.assertTrue(message("\\*", "\\*"), criteria.matches("it like '\\*'", "\\*"));
        Assert.assertFalse(message("\\*", "*"), criteria.matches("it like '\\*'", "*"));
        Assert.assertFalse(message("\\*", "x"), criteria.matches("it like '\\*'", "x"));
    }

    @Test
    public void likeTreatsThreeStarsAsSegmentLocalStar() {
        Assert.assertTrue(message("***", "abc"), criteria.matches("it like '***'", "abc"));
        Assert.assertFalse(message("***", "a/b"), criteria.matches("it like '***'", "a/b"));
    }

    @Test
    public void nullActualIsFalseThroughPublicDsl() {
        Assert.assertFalse(criteria.matches("it like 'a'", MatchContext.of(null)));
    }

    @Test
    public void criterionNullPatternBehavesAsCurrentAdapterContract() {
        Assert.assertTrue(matches(null, null));
        Assert.assertFalse(matches(null, "a"));
        Assert.assertFalse(matches("a", null));
    }

    private boolean matches(String pattern, Object actual) {
        return compiler.compile(new WildcardCriterion(pattern), null).test(MatchContext.of(actual));
    }

    private static String message(String pattern, String actual) {
        return "Unexpected Criterion wildcard result for pattern='" + pattern + "', actual='" + actual + "'";
    }
}
