package com.team4u.framework.base.jdbc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * SQL 构建器
 * <p>
 * 致力于简化动态 SQL 及参数链的构建过程，支持条件拼接和集合参数展开（如 IN 语句）。
 *
 * @author jay.wu
 */
public class SqlBuilder {

    private final StringBuilder sql = new StringBuilder();
    private final List<Object> params = new ArrayList<>();
    private boolean hasConditionGroup;

    public SqlBuilder() {
    }

    public SqlBuilder(String initialSql) {
        sql.append(initialSql);
        hasConditionGroup = initialSql != null && !initialSql.trim().isEmpty();
    }

    /**
     * 追加 SQL 片段及对应参数
     *
     * @param snippet SQL 片段
     * @param args    对应的参数列表
     * @return 当前构建器实例
     */
    public SqlBuilder append(String snippet, Object... args) {
        if (snippet != null) {
            sql.append(snippet);
            if (args != null) {
                Collections.addAll(params, args);
            }
            if (!snippet.trim().isEmpty()) {
                hasConditionGroup = true;
            }
        }
        return this;
    }

    /**
     * 如果值不为 null，则追加 SQL 片段及参数
     *
     * @param snippet SQL 片段
     * @param value   参数值
     * @return 当前构建器实例
     */
    public SqlBuilder appendIfNotNull(String snippet, Object value) {
        if (value != null) {
            return append(snippet, value);
        }
        return this;
    }

    /**
     * 如果集合不为空，则追加 SQL 片段及 IN 语句占位符，并展开集合参数
     * <p>
     * 示例：builder.inIfNotEmpty(" AND status IN ", statusList)
     * 构建结果：" AND status IN (?, ?, ?)"
     *
     * @param snippetPrefix SQL 片段前奏（如 " AND col IN "）
     * @param values        集合参数值
     * @return 当前构建器实例
     */
    public SqlBuilder inIfNotEmpty(String snippetPrefix, Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }

        sql.append(snippetPrefix).append("(");
        int i = 0;
        for (Object value : values) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            params.add(value);
            i++;
        }
        sql.append(")");
        hasConditionGroup = true;
        return this;
    }

    /**
     * 追加组合条件（使用 AND 连接），并使用括号包裹
     *
     * @param consumer 组合条件的构建逻辑
     * @return 当前构建器实例
     */
    public SqlBuilder and(Consumer<SqlBuilder> consumer) {
        return appendGroup(" AND ", consumer);
    }

    /**
     * 追加组合条件（使用 OR 连接），并使用括号包裹
     *
     * @param consumer 组合条件的构建逻辑
     * @return 当前构建器实例
     */
    public SqlBuilder or(Consumer<SqlBuilder> consumer) {
        return appendGroup(" OR ", consumer);
    }

    /**
     * 追加带前缀的组合条件，并使用括号包裹
     *
     * @param prefix   SQL 前缀（如 " AND "、" OR "）
     * @param consumer 组合条件的构建逻辑
     * @return 当前构建器实例
     */
    private SqlBuilder appendGroup(String prefix, Consumer<SqlBuilder> consumer) {
        SqlBuilder subBuilder = new SqlBuilder();
        consumer.accept(subBuilder);

        String subSql = subBuilder.getSql();
        if (!subSql.isEmpty()) {
            if (hasConditionGroup) {
                sql.append(prefix);
            }
            sql.append("(").append(subSql).append(")");
            Collections.addAll(params, subBuilder.getParams());
            hasConditionGroup = true;
        }
        return this;
    }

    /**
     * 获取最终生成的 SQL 语句
     *
     * @return SQL 字符串
     */
    public String getSql() {
        return sql.toString();
    }

    /**
     * 获取最终收集到的参数数组
     *
     * @return 参数数组
     */
    public Object[] getParams() {
        return params.toArray();
    }
}
