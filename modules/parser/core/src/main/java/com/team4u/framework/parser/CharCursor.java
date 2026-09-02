package com.team4u.framework.parser;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 字符流游标（Char Cursor），用于对源码字符串进行顺序遍历并增量维护源码坐标（行号、列号与偏移量）。
 *
 * <p>特性：
 * <ul>
 *   <li>O(1) 的 advance、peek、mark 与 spanFrom 计算</li>
 *   <li>支持 LF (\n)、CR (\r) 与 CRLF (\r\n) 换行规则</li>
 *   <li>不使用 '\0' sentinel，严格遵循 hasNext 契约</li>
 * </ul>
 * </p>
 *
 * @author jay.wu
 */
public final class CharCursor {

    private final String source;
    private final String sourceName;
    private int offset;
    private int line;
    private int column;

    public CharCursor(String source, String sourceName) {
        this.source = source != null ? source : "";
        this.sourceName = sourceName;
        this.offset = 0;
        this.line = 1;
        this.column = 1;
    }

    public CharCursor(String source) {
        this(source, null);
    }

    /**
     * 是否还有待读取字符。
     *
     * @return true 若游标未到达源码末尾
     */
    public boolean hasNext() {
        return offset < source.length();
    }

    /**
     * 检查指定前瞻偏移量位置是否存在字符。
     *
     * @param lookaheadOffset 前瞻偏移量（0 表示当前字符，1 表示下一个字符，依此类推）
     * @return true 若该位置有效
     */
    public boolean has(int lookaheadOffset) {
        return lookaheadOffset >= 0 && offset + lookaheadOffset < source.length();
    }

    /**
     * 查看当前字符，但不推进游标。
     *
     * @return 当前字符
     * @throws NoSuchElementException 若已无字符可读
     */
    public char peek() {
        return peek(0);
    }

    /**
     * 查看指定前瞻偏移量处的字符，但不推进游标。
     *
     * @param lookaheadOffset 前瞻偏移量（0 表示当前字符，1 表示下一个字符，依此类推）
     * @return 目标字符
     * @throws NoSuchElementException 若偏移量越界
     */
    public char peek(int lookaheadOffset) {
        if (!has(lookaheadOffset)) {
            throw new NoSuchElementException("No character at offset " + (offset + lookaheadOffset));
        }
        return source.charAt(offset + lookaheadOffset);
    }

    /**
     * 消费并返回当前字符，同时推进游标并更新源码坐标。
     *
     * @return 被消费的字符
     * @throws NoSuchElementException 若已无字符可读
     */
    public char advance() {
        if (!hasNext()) {
            throw new NoSuchElementException("No character available at offset " + offset);
        }
        char c = source.charAt(offset);
        offset++;

        if (c == '\r') {
            if (offset < source.length() && source.charAt(offset) == '\n') {
                // CRLF: \r 属于 \r\n 序列的第一部分，暂不递增 line，仅递增 column
                column++;
            } else {
                // 独立 \r 换行
                line++;
                column = 1;
            }
        } else if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }

        return c;
    }

    /**
     * 获取当前 0-based 字符偏移量。
     */
    public int offset() {
        return offset;
    }

    /**
     * 获取当前 1-based 行号。
     */
    public int line() {
        return line;
    }

    /**
     * 获取当前 1-based 列号。
     */
    public int column() {
        return column;
    }

    /**
     * 获取完整源码字符串。
     */
    public String source() {
        return source;
    }

    /**
     * 获取源码资源标识/文件名。
     */
    public String sourceName() {
        return sourceName;
    }

    /**
     * 创建当前位置的不可变标记快照（Mark）。
     *
     * @return 当前位置快照
     */
    public Mark mark() {
        return new Mark(offset, line, column);
    }

    /**
     * 从指定的起始标记到当前游标位置生成 {@link SourceSpan}。
     *
     * @param start 起始标记快照
     * @return 区间源码位置
     */
    public SourceSpan spanFrom(Mark start) {
        Objects.requireNonNull(start, "start mark must not be null");
        return new SourceSpan(
                sourceName,
                start.offset(),
                start.line(),
                start.column(),
                offset,
                line,
                column
        );
    }

    /**
     * 游标位置快照。
     */
    public static final class Mark {
        private final int offset;
        private final int line;
        private final int column;

        public Mark(int offset, int line, int column) {
            this.offset = offset;
            this.line = line;
            this.column = column;
        }

        public int offset() {
            return offset;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Mark mark = (Mark) o;
            return offset == mark.offset && line == mark.line && column == mark.column;
        }

        @Override
        public int hashCode() {
            return Objects.hash(offset, line, column);
        }

        @Override
        public String toString() {
            return "Mark{offset=" + offset + ", line=" + line + ", column=" + column + '}';
        }
    }
}
