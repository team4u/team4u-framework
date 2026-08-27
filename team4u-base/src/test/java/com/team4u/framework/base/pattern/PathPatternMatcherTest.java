package com.team4u.framework.base.pattern;

import org.junit.Assert;
import org.junit.Test;

/**
 * Locks Team4u Ant-style path matching semantics.
 */
public class PathPatternMatcherTest {

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
    public void matrixLocksTeam4uAntStyleSemantics() {
        Assert.assertEquals("Characterization matrix count", CASES.length, 53);

        for (int i = 0; i < CASES.length; i++) {
            String[] expected = CASES[i];

            assertCase(i, expected[0], expected[1], Boolean.parseBoolean(expected[2]));
        }
    }

    @Test(expected = NullPointerException.class)
    public void nullPatternThrowsBeforeNullPathIsEvaluated() {
        PathPatternMatcher.match(null, null);
    }

    @Test
    public void nullPathReturnsFalse() {
        Assert.assertFalse(PathPatternMatcher.match("a", null));
    }

    @Test
    public void emptySegmentsNormalizeButTrailingSeparatorParityIsRetained() {
        assertCase(53, "a//b", "a/b", true);
        assertCase(54, "/a//b", "/a/b", true);
        assertCase(55, "a/b//", "a/b", false);
        assertCase(56, "a/b//", "a/b//", true);
        assertCase(57, "a/", "a//", true);
        assertCase(58, "/a/", "/", false);
        assertCase(59, "**", "/", false);
    }

    @Test
    public void trailingDoubleStarConsumesAnExplicitTrailingSeparator() {
        assertCase(60, "a/**/", "a", true);
        assertCase(61, "a/**/", "a/", true);
        assertCase(62, "a/**/b", "a/b/", true);
    }

    @Test
    public void onlyExactDoubleStarSegmentCrossesDirectories() {
        assertCase(64, "a/**/b", "a/x/y/b", true);
        assertCase(65, "a/***/b", "a/x/b", true);
        assertCase(66, "a/***/b", "a/x/y/b", false);
        assertCase(67, "****", "abc", true);
        assertCase(68, "****", "a/b", false);
        assertCase(69, "****/**", "a/x/b", true);
        assertCase(70, "a/**b", "a/xyb", true);
        assertCase(71, "a/**b", "a/x/yb", false);
        assertCase(72, "a/****b", "a/xyb", true);
        assertCase(73, "a/****b", "a/x/yb", false);
    }

    @Test
    public void multipleDoubleStarSegmentsCrossDirectories() {
        assertCase(74, "**/x/**", "x", true);
        assertCase(75, "**/x/**", "a/x", true);
        assertCase(76, "**/x/**", "x/b", true);
        assertCase(77, "**/x/**", "a/x/b/c", true);
        assertCase(78, "**/x/**/y/**", "a/x/b/y/c/d", true);
        assertCase(79, "**/x/**/y/**", "a/x/b/c", false);
    }

    @Test
    public void backslashIsAnOrdinaryLiteral() {
        assertCase(80, "a\\b", "a\\b", true);
        assertCase(81, "a\\b", "ab", false);
        assertCase(82, "\\d", "d", false);
        assertCase(83, "\\d", "\\d", true);
        assertCase(84, "\\", "\\", true);
        assertCase(85, "\\", "/", false);
    }

    @Test
    public void consecutiveWildcardRunsCollapseToOneSegmentLocalStar() {
        assertCase(86, "a??**b", "axxyb", true);
        assertCase(87, "a??**b", "axyyb", true);
        assertCase(88, "a??**b", "axb", false);
        assertCase(89, "*?", "x", true);
        assertCase(90, "*?", "xy", true);
        assertCase(91, "*?", "", false);
    }

    @Test
    public void emptyValuesRemainExclusiveOfNonEmptyPatternsAndPaths() {
        assertCase(92, "a/**", "", false);
        assertCase(93, "/**", "", false);
        assertCase(94, "**", "", true);
        assertCase(95, "a", "", false);
        assertCase(96, "", "a", false);
    }

    @Test
    public void longAmbiguousPatternCompletesWithoutExponentialBacktracking() {
        String path = longText('a', 2000) + "/" + longText('b', 2000);
        String matchingPattern = longText('a', 999) + "*/*" + longText('b', 1999) + "*";
        String failingPattern = matchingPattern.substring(0, matchingPattern.length() - 1) + "!";
        Assert.assertTrue(PathPatternMatcher.match(matchingPattern, path));
        Assert.assertFalse(PathPatternMatcher.match(failingPattern, path));
    }

    private static void assertCase(int index, String pattern, String path, boolean expected) {
        Assert.assertEquals("Matrix case " + index + " pattern=" + quote(pattern)
                        + ", path=" + quote(path) + ", expected=" + expected,
                expected, PathPatternMatcher.match(pattern, path));
    }

    private static String longText(char value, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(value);
        }
        return text.toString();
    }

    private static String quote(String value) {
        return value == null ? "null" : "'" + value + "'";
    }
}
