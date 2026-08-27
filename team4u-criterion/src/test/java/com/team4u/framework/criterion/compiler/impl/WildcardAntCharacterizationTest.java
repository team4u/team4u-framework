package com.team4u.framework.criterion.compiler.impl;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.util.AntPathMatcher;

/**
 * Characterizes the Spring AntPathMatcher behavior currently delegated to by
 * WildcardCriterionCompiler.
 *
 * <p>The default matcher configuration uses these semantics:</p>
 *
 * <ul>
 *     <li>the separator is {@code /}</li>
 *     <li>{@code *} does not cross a separator</li>
 *     <li>{@code ?} matches exactly one non-separator character</li>
 *     <li>{@code **} crosses directories only when a slash-delimited segment is exactly
 *     {@code **}; {@code ***} and longer runs of stars remain segment-local stars</li>
 *     <li>backslash is an ordinary literal character, not an escape</li>
 *     <li>leading, trailing, and repeated separators are normalized by tokenization, except
 *     that a pattern's leading separator must match a path's leading separator</li>
 * </ul>
 *
 * <p>Raw null behavior is deliberately not attributed to Criterion:
 * {@link AntPathMatcher#match(String, String)} throws {@code NullPointerException} for a
 * null pattern and returns {@code false} for a null path. Criterion adapter null behavior
 * is locked separately in {@code WildcardQuickstartTest}.</p>
 */
public class WildcardAntCharacterizationTest {

    private static final String[][] CASES = {
            {"", "", "true"},
            {"", "x", "false"},
            {"*", "", "false"},
            {"*", "x", "true"},
            {"*", "abc", "true"},
            {"*", "/", "false"},
            {"*", "a/b", "false"},
            {"**", "", "true"},
            {"**", "/", "false"},
            {"**", "a", "true"},
            {"**", "a/b", "true"},
            {"/**", "/", "true"},
            {"/**", "/a", "true"},
            {"/**", "a", "false"},
            {"a/**", "a", "true"},
            {"a/**", "a/", "true"},
            {"a/**", "a/b/c", "true"},
            {"/**/b", "/b", "true"},
            {"/**/b", "/a/b", "true"},
            {"/**/b", "/a/c/b", "true"},
            {"a/**/b", "a/b", "true"},
            {"a/**/b", "a/x/b", "true"},
            {"a/**/**/b", "a/x/y/b", "true"},
            {"?", "", "false"},
            {"?", "a", "true"},
            {"?", "ab", "false"},
            {"?", "/", "false"},
            {"a?c", "abc", "true"},
            {"a?c", "a/c", "false"},
            {"\\*", "*", "false"},
            {"\\*", "x", "false"},
            {"a\\*b", "a*b", "false"},
            {"a\\*b", "axb", "false"},
            {"\\*", "\\*", "true"},
            {"\\a/b", "\\a/b", "true"},
            {"\\a/b", "a/b", "false"},
            {"a\\**", "a\\*", "true"},
            {"a\\**", "a\\*/x", "false"},
            {"\\?", "?", "false"},
            {"\\?", "x", "false"},
            {"/a/", "/a/", "true"},
            {"/a/", "/a", "false"},
            {"a//b", "a//b", "true"},
            {"a//b", "a/b", "true"},
            {"**/*.java", "x.java", "true"},
            {"**/*.java", "a/x.java", "true"},
            {"a/**/*.java", "a/x.java", "true"},
            {"a/**/*.java", "x.java", "false"},
            {"**/**", "a/b", "true"},
            {"***", "a/b", "false"},
            {"***", "abc", "true"},
            {"a***b", "ab", "true"},
            {"a***b", "a/b", "false"}
    };

    private final AntPathMatcher matcher = new AntPathMatcher();

    @Test
    public void matrixCharacterizesCurrentAntPathMatcherBehavior() {
        Assert.assertEquals("Characterization matrix count", CASES.length, 53);

        for (int i = 0; i < CASES.length; i++) {
            String[] expected = CASES[i];
            String pattern = expected[0];
            String path = expected[1];
            boolean expectedResult = Boolean.parseBoolean(expected[2]);
            boolean actualResult = matcher.match(pattern, path);

            Assert.assertEquals("Matrix case " + i + " pattern=" + quote(pattern)
                            + ", path=" + quote(path) + ", expected=" + expectedResult,
                    expectedResult, actualResult);
        }
    }

    @Test(expected = NullPointerException.class)
    public void nullPatternFailsInRawMatcher() {
        matcher.match(null, "a");
    }

    @Test
    public void nullPathReturnsFalseInRawMatcher() {
        Assert.assertFalse(matcher.match("a", null));
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }
}
