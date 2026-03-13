package com.team4u.framework.base.util;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简易 JDBC 工具类
 * <p>
 * 提供基于数据源的基础数据库操作，支持简单的查询与 SQL 执行。
 *
 * @author jay.wu
 */
public class JdbcUtil {

    /**
     * 执行查询并返回结果列表
     * <p>
     * 自动处理连接的打开与关闭。查询结果中的每一行被封装为一个 {@link Map}，
     * 其中 Key 为小写的列名（或别名），Value 为对应列的值。
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 查询语句（可带 ? 占位符）
     * @param params     SQL 语句中的参数列表，按顺序对应占位符
     * @return 包含所有查询结果行的列表，若无结果则返回空列表
     * @throws SQLException 如果数据库访问出错
     */
    public static List<Map<String, Object>> query(DataSource dataSource, String sql, Object... params)
            throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        // 使用 getColumnLabel 获取别名，并转换为小写作为 Key
                        row.put(metaData.getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    /**
     * 执行更新、插入或删除等非查询 SQL 语句
     * <p>
     * 自动处理连接的打开与关闭。
     *
     * @param dataSource 数据库连接池或数据源
     * @param sql        待执行的 SQL 语句（可带 ? 占位符）
     * @param params     SQL 语句中的参数列表，按顺序对应占位符
     * @return 受到影响的行数
     * @throws SQLException 如果数据库访问出错
     */
    public static int execute(DataSource dataSource, String sql, Object... params) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            setParameters(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * 设置 PreparedStatement 的占位符参数
     *
     * @param ps     PreparedStatement 对象
     * @param params 参数列表
     * @throws SQLException 如果设置参数出错
     */
    private static void setParameters(PreparedStatement ps, Object... params) throws SQLException {
        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }
}
