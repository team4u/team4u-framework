package com.team4u.framework.criterion.compiler.impl;

import com.team4u.framework.base.pattern.PathPatternMatcher;
import org.junit.Assert;
import org.junit.Test;

/**
 * Locks the Team4u Ant-style semantics delegated to by WildcardCriterionCompiler.
 *
 * <p>The Spring 5.3.39 AntPathMatcher was the historical source of this matrix.
 * Task 5 moved execution to Base without changing these observations.</p>
 *
 * <ul>
 *     <li>the separator is {@code /}</li>
 *     <li>{@code *} does not cross a separator</li>
 *     <li>{@code ?} matches exactly one non-separator character</li>
 *     <li>{@code **} crosses directories only when a slash-delimited segment is exactly
 *     {@code **}; {@code ***} and longer runs of stars remain segment-local stars</li>
 *     <li>backslash is an ordinary literal character, not an escape</li>
 *     <li>leading, trailing, and repeated separators follow the locked boundary
 *     behavior captured by the matrix</li>
 * </ul>
 *
 * <p>Null behavior is layer-specific: the Base matcher throws
 * {@code NullPointerException} for a null pattern and returns false for a null path.
 * {@code WildcardQuickstartTest}.</p>
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

    @Test
    public void matrixCharacterizesBaseMatcherBehavior() {
        Assert.assertEquals("Characterization matrix count", CASES.length, 53);

        for (int i = 0; i < CASES.length; i++) {
            String[] expected = CASES[i];
            String pattern = expected[0];
            String path = expected[1];
            boolean expectedResult = Boolean.parseBoolean(expected[2]);
            boolean actualResult = PathPatternMatcher.match(pattern, path);

            Assert.assertEquals("Matrix case " + i + " pattern=" + quote(pattern)
                            + ", path=" + quote(path) + ", expected=" + expectedResult,
                    expectedResult, actualResult);
        }
    }

    @Test(expected = NullPointerException.class)
    public void nullPatternFailsInBaseMatcher() {
        PathPatternMatcher.match(null, "a");
    }

    @Test
    public void nullPathReturnsFalseInBaseMatcher() {
        Assert.assertFalse(PathPatternMatcher.match("a", null));
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }
}
