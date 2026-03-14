package com.team4u.framework.base.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 结构化 SQL 更新构建器
 * <p>
 * 专注于构建动态的 UPDATE 语句及 WHERE 条件。
 *
 * @author jay.wu
 */
public class UpdateBuilder {

    private final String tableName;
    private final List<String> sets = new ArrayList<>();
    private final List<Object> setParams = new ArrayList<>();
    private final List<String> wheres = new ArrayList<>();
    private final List<Object> whereParams = new ArrayList<>();

    public UpdateBuilder(String tableName) {
        this.tableName = tableName;
    }

    /**
     * 设置字段更新值
     *
     * @param column 字段名
     * @param value  新值
     * @return 当前构建器实例
     */
    public UpdateBuilder set(String column, Object value) {
        sets.add(column + " = ?");
        setParams.add(value);
        return this;
    }

    /**
     * 设置字段更新为指定的 SQL 表达式（例如 version = version + 1）
     *
     * @param column     字段名
     * @param expression SQL 表达式值
     * @return 当前构建器实例
     */
    public UpdateBuilder setExpression(String column, String expression) {
        sets.add(column + " = " + expression);
        return this;
    }

    /**
     * 如果值不为 null，则设置字段更新值
     *
     * @param column 字段名
     * @param value  新值
     * @return 当前构建器实例
     */
    public UpdateBuilder setIfNotNull(String column, Object value) {
        if (value != null) {
            return set(column, value);
        }
        return this;
    }

    /**
     * 添加 WHERE 条件及其参数
     *
     * @param condition WHERE 条件片段
     * @param args      对应参数
     * @return 当前构建器实例
     */
    public UpdateBuilder where(String condition, Object... args) {
        if (condition != null) {
            wheres.add(condition);
            if (args != null) {
                Collections.addAll(whereParams, args);
            }
        }
        return this;
    }

    /**
     * 获取最终生成的 UPDATE SQL 语句
     *
     * @return SQL 字符串
     */
    public String getSql() {
        if (sets.isEmpty()) {
            throw new IllegalStateException("No columns to update for table: " + tableName);
        }

        StringBuilder sql = new StringBuilder("UPDATE ");
        sql.append(tableName).append(" SET ");
        sql.append(String.join(", ", sets));

        if (!wheres.isEmpty()) {
            sql.append(" WHERE ");
            sql.append(String.join(" AND ", wheres));
        }

        return sql.toString();
    }

    /**
     * 获取最终合并后的参数列表（SET 部分参数在前，WHERE 部分参数在后）
     *
     * @return 参数数组
     */
    public Object[] getParams() {
        Object[] result = new Object[setParams.size() + whereParams.size()];
        int i = 0;
        for (Object p : setParams) {
            result[i++] = p;
        }
        for (Object p : whereParams) {
            result[i++] = p;
        }
        return result;
    }
}
