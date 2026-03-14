package com.team4u.framework.base.jdbc;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化 SQL 插入构建器
 * <p>
 * 专注于构建动态的 INSERT 语句。
 *
 * @author jay.wu
 */
public class InsertBuilder {

    private final String tableName;
    private final List<String> columns = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();

    public InsertBuilder(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 设置字段及其对应的值
     *
     * @param column 字段名
     * @param value  字段值
     * @return 当前构建器实例
     */
    public InsertBuilder column(String column, Object value) {
        columns.add(column);
        values.add(value);
        return this;
    }

    /**
     * 如果值不为 null，则设置列及其对应的值
     *
     * @param column 字段名
     * @param value  字段值
     * @return 当前构建器实例
     */
    public InsertBuilder columnIfNotNull(String column, Object value) {
        if (value != null) {
            return column(column, value);
        }
        return this;
    }

    /**
     * 获取最终生成的 INSERT SQL 语句
     *
     * @return SQL 字符串
     */
    public String getSql() {
        if (columns.isEmpty()) {
            throw new IllegalStateException("No columns to insert for table: " + tableName);
        }

        String sql = "INSERT INTO " + tableName + " (" +
                String.join(", ", columns) +
                ") VALUES (" +
                SqlExpression.placeholders(columns.size()) +
                ")";

        return sql;
    }

    /**
     * 获取参数数组
     *
     * @return 参数数组
     */
    public Object[] getParams() {
        return values.toArray();
    }
}
