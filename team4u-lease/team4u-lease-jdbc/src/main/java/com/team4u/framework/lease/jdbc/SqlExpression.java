package com.team4u.framework.lease.jdbc;

/**
 * SQL 表达式标记对象
 * <p>
 * 当 Entity 中某个字段的值为 SqlExpression 实例时，
 * 在构建 UPDATE 语句时会直接输出 SQL 表达式（如 "failure_count + 1"），
 * 而不是使用 ? 占位符进行参数绑定。
 * <p>
 * 该类用于替代魔法字符串（如 "__failure_count_plus_1__"），
 * 通过类型系统来区分"普通字段值"和"SQL 表达式"，
 * 从而获得编译期类型安全和更好的可读性。
 *
 * @author jay.wu
 */
public class SqlExpression {

    /**
     * SQL 表达式文本，如 "failure_count + 1"
     */
    private final String expression;

    public SqlExpression(String expression) {
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }

    /**
     * 创建一个字段自增表达式
     * <p>
     * 生成形如 "fieldName + 1" 的 SQL 表达式，
     * 用于在 UPDATE 语句中对指定字段做原子递增操作。
     *
     * @param fieldName 需要递增的数据库字段名
     * @return 包含递增表达式的 SqlExpression 实例
     */
    public static SqlExpression increment(String fieldName) {
        return new SqlExpression(fieldName + " + 1");
    }

    /**
     * 生成指定数量的 SQL 参数占位符，以逗号分隔
     * <p>
     * 例如 count=3 会生成 "?, ?, ?"，用于 IN 子句等场景。
     *
     * @param count 占位符数量
     * @return 逗号分隔的占位符字符串
     */
    public static String placeholders(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        return builder.toString();
    }
}
