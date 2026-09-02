package com.team4u.framework.parser;

import java.io.Serializable;
import java.util.Objects;

/**
 * 源码文本位置区间（Source Span），记录节点在源码文本中的起止偏移量、行号与列号。
 *
 * <p>坐标契约：
 * <ul>
 *   <li>offset: 0-based，以 Java UTF-16 code unit 为单位</li>
 *   <li>line: 1-based</li>
 *   <li>column: 1-based</li>
 *   <li>range: [start, end) 左闭右开区间，即 start inclusive, end exclusive</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class SourceSpan implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 未知或合成位置常量。
     */
    public static final SourceSpan UNKNOWN = new SourceSpan(null, -1, -1, -1, -1, -1, -1);

    private final String source;
    private final int startOffset;
    private final int startLine;
    private final int startColumn;
    private final int endOffset;
    private final int endLine;
    private final int endColumn;

    public SourceSpan(
            String source,
            int startOffset,
            int startLine,
            int startColumn,
            int endOffset,
            int endLine,
            int endColumn) {
        boolean allUnknown = startOffset == -1 && startLine == -1 && startColumn == -1
                && endOffset == -1 && endLine == -1 && endColumn == -1;
        if (!allUnknown) {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException(
                        "Invalid offset range: [" + startOffset + ", " + endOffset + ")");
            }
            if (startLine < 1 || endLine < 1) {
                throw new IllegalArgumentException(
                        "Line numbers must be >= 1 (got startLine=" + startLine + ", endLine=" + endLine + ")");
            }
            if (startColumn < 1 || endColumn < 1) {
                throw new IllegalArgumentException(
                        "Column numbers must be >= 1 (got startColumn=" + startColumn + ", endColumn=" + endColumn + ")");
            }
            if (endLine < startLine) {
                throw new IllegalArgumentException(
                        "endLine (" + endLine + ") must be >= startLine (" + startLine + ")");
            }
            if (startLine == endLine && endColumn < startColumn) {
                throw new IllegalArgumentException(
                        "On same line, endColumn (" + endColumn + ") must be >= startColumn (" + startColumn + ")");
            }
        }
        this.source = source;
        this.startOffset = startOffset;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endOffset = endOffset;
        this.endLine = endLine;
        this.endColumn = endColumn;
    }

    public String source() {
        return source;
    }

    public int startOffset() {
        return startOffset;
    }

    public int startLine() {
        return startLine;
    }

    public int startColumn() {
        return startColumn;
    }

    public int endOffset() {
        return endOffset;
    }

    public int endLine() {
        return endLine;
    }

    public int endColumn() {
        return endColumn;
    }

    /**
     * 是否为已知的有效源码位置。
     *
     * @return true 若位置已知；false 若为未知位置（如 UNKNOWN）
     */
    public boolean known() {
        return startOffset >= 0;
    }

    /**
     * 格式化输出人类可读的位置字符串（如 "order.flow:18:9" 或 "18:9" 或 "&lt;unknown&gt;"）。
     *
     * @return 格式化位置字符串
     */
    public String format() {
        if (!known()) {
            return source != null ? source : "<unknown>";
        }
        if (source != null && !source.isEmpty()) {
            return source + ":" + startLine + ":" + startColumn;
        }
        return startLine + ":" + startColumn;
    }

    @Override
    public String toString() {
        return format();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceSpan that = (SourceSpan) o;
        return startOffset == that.startOffset &&
                startLine == that.startLine &&
                startColumn == that.startColumn &&
                endOffset == that.endOffset &&
                endLine == that.endLine &&
                endColumn == that.endColumn &&
                Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, startOffset, startLine, startColumn, endOffset, endLine, endColumn);
    }
}
