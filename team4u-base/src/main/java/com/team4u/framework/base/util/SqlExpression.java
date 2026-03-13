package com.team4u.framework.base.util;

import java.util.Collections;

/**
 * SQL 表达式封装类
 * <p>
 * 用于处理 SQL 片段，如字段自增、占位符生成等。
 * </p>
 *
 * @author jay.wu
 */
public class SqlExpression {

    private final String expression;

    /**
     * 构造函数
     *
     * @param expression SQL 表达式内容
     */
    public SqlExpression(String expression) {
        this.expression = expression;
    }

    /**
     * 生成字段自增表达式，例如：column + 1
     *
     * @param column 字段名称
     * @return 自增表达式实例
     */
    public static SqlExpression increment(String column) {
        return new SqlExpression(column + " + 1");
    }

    /**
     * 生成 SQL 占位符字符串
     * <p>
     * 例如：count 为 3 时，返回 "?,?,?"。
     * </p>
     *
     * @param count 占位符数量
     * @return 以逗号分隔的占位符字符串，若数量小于等于 0 则返回空字符串
     */
    public static String placeholders(int count) {
        if (count <= 0) {
            return "";
        }
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /**
     * 获取原始表达式内容
     *
     * @return 表达式字符串
     */
    public String getExpression() {
        return expression;
    }
}
