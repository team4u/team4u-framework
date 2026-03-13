package com.team4u.framework.base.util;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * JdbcUtil 单元测试
 * <p>
 * 使用 H2 内存数据库进行真实的集成测试，验证 SQL 执行及结果映射逻辑。
 *
 * @author jay.wu
 */
public class JdbcUtilTest {

    private DataSource dataSource;

    /**
     * 初始化 H2 内存数据库并创建测试表
     */
    @Before
    public void setUp() throws SQLException {
        JdbcDataSource h2DataSource = new JdbcDataSource();
        // 使用内存数据库，DB_CLOSE_DELAY=-1 保证在测试期间数据库不会因为连接关闭而消失
        h2DataSource.setURL("jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1;MODE=MySQL");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
        this.dataSource = h2DataSource;

        // 初始化表结构及测试数据
        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS test_user");
        JdbcUtil.execute(dataSource, "CREATE TABLE test_user (id INT PRIMARY KEY, name VARCHAR(255), age INT)");
        JdbcUtil.execute(dataSource, "INSERT INTO test_user (id, name, age) VALUES (1, '张三', 20)");
        JdbcUtil.execute(dataSource, "INSERT INTO test_user (id, name, age) VALUES (2, '李四', 25)");
    }

    /**
     * 测试查询功能
     * <p>
     * 验证带占位符的查询，以及结果集列名转小写映射为 Map 的逻辑。
     */
    @Test
    public void query() throws SQLException {
        // 执行带参数查询
        String sql = "SELECT id, name, age FROM test_user WHERE id = ?";
        List<Map<String, Object>> result = JdbcUtil.query(dataSource, sql, 1);

        // 验证查询结果
        Assert.assertNotNull("结果列表不应为 null", result);
        Assert.assertEquals("应返回 1 条记录", 1, result.size());

        Map<String, Object> row = result.get(0);
        // JdbcUtil 内部将列名转为小写存储在 Map 中
        Assert.assertEquals("ID 匹配失败", 1, row.get("id"));
        Assert.assertEquals("NAME 匹配失败", "张三", row.get("name"));
        Assert.assertEquals("AGE 匹配失败", 20, row.get("age"));
    }

    /**
     * 测试查询所有数据
     */
    @Test
    public void queryAll() throws SQLException {
        List<Map<String, Object>> result = JdbcUtil.query(dataSource, "SELECT * FROM test_user ORDER BY id ASC");
        Assert.assertEquals("应返回 2 条记录", 2, result.size());
        Assert.assertEquals("第一条记录姓名不匹配", "张三", result.get(0).get("name"));
        Assert.assertEquals("第二条记录姓名不匹配", "李四", result.get(1).get("name"));
    }

    /**
     * 测试更新功能
     */
    @Test
    public void executeUpdate() throws SQLException {
        // 更新记录
        int affectedRows = JdbcUtil.execute(dataSource, "UPDATE test_user SET name = ? WHERE id = ?", "王五", 1);
        Assert.assertEquals("受影响行数应为 1", 1, affectedRows);

        // 验证更新结果
        List<Map<String, Object>> result = JdbcUtil.query(dataSource, "SELECT name FROM test_user WHERE id = 1");
        Assert.assertEquals("姓名更新验证失败", "王五", result.get(0).get("name"));
    }

    /**
     * 测试插入功能
     */
    @Test
    public void executeInsert() throws SQLException {
        int affectedRows = JdbcUtil.execute(dataSource, "INSERT INTO test_user (id, name, age) VALUES (?, ?, ?)", 3, "赵六", 30);
        Assert.assertEquals("插入受影响行数应为 1", 1, affectedRows);

        List<Map<String, Object>> result = JdbcUtil.query(dataSource, "SELECT COUNT(*) as cnt FROM test_user");
        // H2 的聚合函数结果可能是 Long 类型，取决于版本及配置，这里使用 Number 兼容
        Assert.assertEquals("总记录数验证失败", 3, ((Number) result.get(0).get("cnt")).intValue());
    }

    /**
     * 测试删除功能
     */
    @Test
    public void executeDelete() throws SQLException {
        int affectedRows = JdbcUtil.execute(dataSource, "DELETE FROM test_user WHERE id = ?", 1);
        Assert.assertEquals("删除受影响行数应为 1", 1, affectedRows);

        List<Map<String, Object>> result = JdbcUtil.query(dataSource, "SELECT * FROM test_user WHERE id = 1");
        Assert.assertTrue("记录应已被删除", result.isEmpty());
    }

    /**
     * 测试空结果集查询
     */
    @Test
    public void queryEmpty() throws SQLException {
        List<Map<String, Object>> result = JdbcUtil.query(dataSource, "SELECT * FROM test_user WHERE id = 999");
        Assert.assertNotNull("结果列表不应为 null", result);
        Assert.assertTrue("结果集应为空", result.isEmpty());
    }
}
