package com.team4u.framework.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 泛型 Token 序列游标（Token Cursor）。
 *
 * <p>提供 O(1) 的位置查看、单步推进、快照标记与回滚机制，适用于各种手写递归下降解析器及投机解析（Speculative Parsing）。</p>
 *
 * @param <T> Token 元素类型
 * @author jay.wu
 */
public final class TokenCursor<T> {

    private final List<T> tokens;
    private int position;

    public TokenCursor(List<T> tokens) {
        this.tokens = tokens != null
                ? Collections.unmodifiableList(new ArrayList<>(tokens))
                : Collections.emptyList();
        this.position = 0;
    }

    /**
     * 是否还有后续 Token。
     *
     * @return true 若游标未到达序列末尾
     */
    public boolean hasNext() {
        return position < tokens.size();
    }

    /**
     * 查看当前位置的 Token，但不推进游标。
     *
     * @return 当前 Token；若已到达末尾则返回 null
     */
    public T peek() {
        return peek(0);
    }

    /**
     * 查看指定前瞻偏移量处的 Token，但不推进游标。
     *
     * @param offset 偏移量（0 表示当前，1 表示下一个，依此类推）
     * @return 目标 Token；若越界则返回 null
     */
    public T peek(int offset) {
        int index = position + offset;
        if (offset < 0 || index < 0 || index >= tokens.size()) {
            return null;
        }
        return tokens.get(index);
    }

    /**
     * 获取最近刚刚消费过的上一个 Token。
     *
     * @return 上一个 Token；若当前位于起始位置则返回 null
     */
    public T previous() {
        if (position <= 0) {
            return null;
        }
        return tokens.get(position - 1);
    }

    /**
     * 消费并返回当前 Token，同时游标向后推进一位。
     *
     * @return 被消费的 Token
     * @throws NoSuchElementException 若已无 Token 可读
     */
    public T advance() {
        if (!hasNext()) {
            throw new NoSuchElementException("No more tokens at position " + position);
        }
        return tokens.get(position++);
    }

    /**
     * 获取当前 0-based 游标索引位置。
     *
     * @return 当前游标位置
     */
    public int position() {
        return position;
    }

    /**
     * 创建当前游标位置标记（用于后续回滚）。
     *
     * @return 标记位置索引
     */
    public int mark() {
        return position;
    }

    /**
     * 将游标位置回滚至指定标记。
     *
     * @param mark 标记位置索引
     * @throws IndexOutOfBoundsException 若标记索引非法
     */
    public void reset(int mark) {
        if (mark < 0 || mark > tokens.size()) {
            throw new IndexOutOfBoundsException("Invalid mark: " + mark + ", token size: " + tokens.size());
        }
        this.position = mark;
    }
}
