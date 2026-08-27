package com.team4u.framework.base.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateless matcher for Team4u Ant-style path patterns.
 *
 * <p>The separator is {@code /}. {@code ?} matches exactly one non-separator
 * character and {@code *} matches zero or more non-separator characters. A raw
 * slash-delimited {@code **} segment matches zero or more path segments;
 * longer runs of stars such as {@code ***} remain segment-local wildcards.
 * All other characters, including backslash, are literal.</p>
 *
 * <p>This matcher is thread-safe and does not use regular expressions.</p>
 */
public final class PathPatternMatcher {

    private static final char SEPARATOR = '/';
    private static final String DOUBLE_STAR = "**";

    private PathPatternMatcher() {
    }

    /**
     * Matches a path against a Team4u Ant-style pattern.
     *
     * @param pattern the pattern to match
     * @param path the path to test
     * @return {@code true} if the path matches the pattern, or {@code false}
     *         if the path is {@code null} or does not match
     * @throws NullPointerException if {@code pattern} is {@code null}
     */
    public static boolean match(String pattern, String path) {
        Objects.requireNonNull(pattern, "pattern must not be null");

        if (path == null) {
            return false;
        }

        if (hasLeadingSeparator(pattern) != hasLeadingSeparator(path)) {
            return false;
        }

        String[] patternSegments = tokenize(pattern);
        String[] pathSegments = tokenize(path);

        // dp[i][j] is true when pattern segments i and path segments j consume
        // equal remainders. A ** segment consumes zero or more path segments.
        boolean[][] dp = new boolean[patternSegments.length + 1][pathSegments.length + 1];
        dp[patternSegments.length][pathSegments.length] = true;

        for (int j = 0; j < pathSegments.length; j++) {
            dp[patternSegments.length][j] = false;
        }

        for (int i = patternSegments.length - 1; i >= 0; i--) {
            boolean crossesDirectories = patternSegments[i].equals(DOUBLE_STAR);

            for (int j = pathSegments.length; j >= 0; j--) {
                boolean result = crossesDirectories && dp[i + 1][j];

                if (j < pathSegments.length && crossesDirectories && dp[i][j + 1]) {
                    result = true;
                } else if (j < pathSegments.length && dp[i + 1][j + 1]) {
                    result = result || matchSegment(patternSegments[i], pathSegments[j]);
                }

                dp[i][j] = result;
            }
        }

        if (!dp[0][0]) {
            return false;
        }

        // Empty separator-delimited tokens are ignored, but a raw trailing
        // separator remains observable. Matching it requires a terminal **
        // pattern segment; ordinary patterns and redundant ** segments do not
        // consume that final separator.
        if (path.endsWith(String.valueOf(SEPARATOR)) != pattern.endsWith(String.valueOf(SEPARATOR))) {
            return patternSegments.length > pathSegments.length;
        }

        return true;
    }

    private static boolean hasLeadingSeparator(String value) {
        return !value.isEmpty() && value.charAt(0) == SEPARATOR;
    }

    private static String[] tokenize(String value) {
        List<String> segments = new ArrayList<String>();
        int start = 0;

        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == SEPARATOR) {
                if (i > start) {
                    segments.add(value.substring(start, i));
                }
                start = i + 1;
            }
        }

        return segments.toArray(new String[segments.size()]);
    }

    private static boolean matchSegment(String pattern, String value) {
        // A one-dimensional dynamic-programming row represents pattern suffix
        // states as the algorithm walks backward through segment characters.
        boolean[] previous = new boolean[value.length() + 1];
        boolean[] current = new boolean[value.length() + 1];
        previous[value.length()] = true;

        if (pattern.charAt(pattern.length() - 1) == '*') {
            for (int j = value.length(); j >= 0; j--) {
                previous[j] = true;
            }
        }

        for (int i = pattern.length() - 1; i >= 0; i--) {
            char patternChar = pattern.charAt(i);
            current[value.length()] = patternChar == '*' && previous[value.length()];

            for (int j = value.length() - 1; j >= 0; j--) {
                if (patternChar == '*') {
                    current[j] = current[j + 1] || previous[j];
                } else if (patternChar == '?' || patternChar == value.charAt(j)) {
                    current[j] = previous[j + 1];
                } else {
                    current[j] = false;
                }
            }

            boolean[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[0];
    }
}
