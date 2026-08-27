package com.team4u.framework.base.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JdbcUtil 及构建器单元测试
 *
 * @author jay.wu
 */
public class JdbcUtilTest {

    private DataSource dataSource;

    @Before
    public void setUp() throws SQLException {
        JdbcDataSource h2DataSource = new JdbcDataSource();
        h2DataSource.setURL("jdbc:h2:mem:test_db;DB_CLOSE_DELAY=-1;MODE=MySQL");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
        this.dataSource = h2DataSource;

        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS test_user");
        JdbcUtil.execute(dataSource, "CREATE TABLE test_user (id INT PRIMARY KEY, name VARCHAR(255), age INT)");
        JdbcUtil.execute(dataSource, "INSERT INTO test_user (id, name, age) VALUES (1, '张三', 20)");
        JdbcUtil.execute(dataSource, "INSERT INTO test_user (id, name, age) VALUES (2, '李四', 25)");
    }

    @Test
    public void query() throws SQLException {
        String sql = "SELECT id, name, age FROM test_user WHERE id = ?";
        List<Map<String, Object>> result = JdbcUtil.query(dataSource, sql, 1);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("张三", result.get(0).get("name"));
    }

    @Test
    public void queryEntity() throws SQLException {
        User user = JdbcUtil.queryOne(dataSource, "SELECT id, name as user_name, age FROM test_user WHERE id = ?", User.class, 1);
        Assert.assertNotNull(user);
        Assert.assertEquals("张三", user.getUserName());
    }

    @Test
    public void testSqlBuilder() throws SQLException {
        SqlBuilder sb = new SqlBuilder("SELECT id, name as user_name, age FROM test_user WHERE 1=1");
        sb.appendIfNotNull(" AND name = ?", "张三");
        sb.inIfNotEmpty(" AND id IN ", Arrays.asList(1, 2, 3));

        List<User> users = JdbcUtil.queryList(dataSource, sb.getSql(), User.class, sb.getParams());
        Assert.assertEquals(1, users.size());
        Assert.assertEquals("张三", users.get(0).getUserName());
    }

    @Test
    public void testSqlBuilderWithConditionCombinations() throws SQLException {
        SqlBuilder sb = new SqlBuilder("SELECT id, name as user_name, age FROM test_user WHERE 1=1");
        // 组合条件： AND (name = ? OR (age > ?))
        sb.and(b -> b.append("name = ?", "李四").or(b2 -> b2.append("age > ?", 20)));

        List<User> users = JdbcUtil.queryList(dataSource, sb.getSql(), User.class, sb.getParams());
        Assert.assertEquals(1, users.size());
        Assert.assertEquals("李四", users.get(0).getUserName());
    }

    @Test
    public void testUpdateBuilder() throws SQLException {
        UpdateBuilder ub = new UpdateBuilder("test_user")
                .set("name", "王五")
                .setIfNotNull("age", 30)
                .where("id = ?", 1);

        int affected = JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());
        Assert.assertEquals(1, affected);

        User user = JdbcUtil.queryOne(dataSource, "SELECT name, age FROM test_user WHERE id = 1", User.class);
        Assert.assertEquals("王五", user.getName());
        Assert.assertEquals(30, user.getAge().intValue());
    }

    @Test
    public void testUpdateBuilderWithExpression() throws SQLException {
        UpdateBuilder ub = new UpdateBuilder("test_user")
                .setExpression("age", "age + 1")
                .where("id = ?", 1);

        JdbcUtil.execute(dataSource, ub.getSql(), ub.getParams());

        User user = JdbcUtil.queryOne(dataSource, "SELECT age FROM test_user WHERE id = 1", User.class);
        Assert.assertEquals(21, user.getAge().intValue());
    }

    @Test
    public void queryOneMap() throws SQLException {
        Map<String, Object> result = JdbcUtil.queryOneMap(dataSource, "SELECT * FROM test_user WHERE id = ?", 1);
        Assert.assertNotNull(result);
        Assert.assertEquals("张三", result.get("name"));

        result = JdbcUtil.queryOneMap(dataSource, "SELECT * FROM test_user WHERE id = ?", 999);
        Assert.assertNull(result);
    }

    @Test
    public void queryScalar() throws SQLException {
        Long count = JdbcUtil.queryScalar(dataSource, "SELECT count(*) FROM test_user", Long.class);
        Assert.assertEquals(2L, count.longValue());

        String name = JdbcUtil.queryScalar(dataSource, "SELECT name FROM test_user WHERE id = ?", String.class, 1);
        Assert.assertEquals("张三", name);

        Integer nullVal = JdbcUtil.queryScalar(dataSource, "SELECT null FROM test_user WHERE id = 1", Integer.class);
        Assert.assertNull(nullVal);

        Integer noResult = JdbcUtil.queryScalar(dataSource, "SELECT id FROM test_user WHERE id = 999", Integer.class);
        Assert.assertNull(noResult);
    }

    @Test
    public void insertAndReturnKey() throws SQLException {
        Long key = JdbcUtil.insertAndReturnKey(dataSource, "INSERT INTO test_user (id, name, age) VALUES (3, '王五', 30)");
        // H2 返回的 key 可能取决于配置，这里主要检查不为 null 且能正确执行
        Assert.assertNotNull(key);

        User user = JdbcUtil.queryOne(dataSource, "SELECT * FROM test_user WHERE id = 3", User.class);
        Assert.assertEquals("王五", user.getName());
    }

    @Test
    public void executeWithBean() throws SQLException {
        User user = new User();
        user.setId(4L);
        user.setName("赵六");
        user.setAge(40);

        int affected = JdbcUtil.executeWithBean(dataSource,
                "INSERT INTO test_user (id, name, age) VALUES (?, ?, ?)",
                user, "id", "name", "age");
        Assert.assertEquals(1, affected);

        User saved = JdbcUtil.queryOne(dataSource, "SELECT * FROM test_user WHERE id = 4", User.class);
        Assert.assertEquals("赵六", saved.getName());
    }

    @Test
    public void testConvertValue() throws SQLException {
        // 创建一个临时表来测试各种数据类型
        JdbcUtil.execute(dataSource, "CREATE TABLE test_types (id INT, price DECIMAL(10,2), d_val DOUBLE, f_val FLOAT, s_val SMALLINT, create_time TIMESTAMP)");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        JdbcUtil.execute(dataSource, "INSERT INTO test_types VALUES (1, 10.5, 1.23, 1.1, 5, ?)", now);

        TypeEntity entity = JdbcUtil.queryOne(dataSource, "SELECT * FROM test_types WHERE id = 1", TypeEntity.class);
        Assert.assertNotNull(entity);
        Assert.assertEquals(10.5, entity.getPrice().doubleValue(), 0.01);
        Assert.assertEquals(1.23, entity.getdVal(), 0.01);
        Assert.assertEquals(1.1f, entity.getfVal(), 0.01);
        Assert.assertEquals(5, entity.getsVal().intValue());
        Assert.assertNotNull(entity.getCreateTime());
    }

    @Test(expected = SQLException.class)
    public void testMapRowToBeanError() throws SQLException {
        // 构造一个会导致反射失败的情况，例如类没有无参构造函数
        JdbcUtil.queryOne(dataSource, "SELECT * FROM test_user", NoDefaultConstructor.class);
    }

    @Test
    public void testPrivateConstructor() throws Exception {
        // 覆盖工具类私有构造函数（虽然不推荐，但有时为了覆盖率）
        java.lang.reflect.Constructor<JdbcUtil> constructor = JdbcUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        JdbcUtil util = constructor.newInstance();
        Assert.assertNotNull(util);
    }

    public static class User {
        private Long id;
        private String name;
        private String userName;
        private Integer age;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    public static class TypeEntity {
        private java.math.BigDecimal price;
        private Double dVal;
        private Float fVal;
        private Short sVal;
        private java.util.Date createTime;

        public java.math.BigDecimal getPrice() {
            return price;
        }

        public void setPrice(java.math.BigDecimal price) {
            this.price = price;
        }

        public Double getdVal() {
            return dVal;
        }

        public void setdVal(Double dVal) {
            this.dVal = dVal;
        }

        public Float getfVal() {
            return fVal;
        }

        public void setfVal(Float fVal) {
            this.fVal = fVal;
        }

        public Short getsVal() {
            return sVal;
        }

        public void setsVal(Short sVal) {
            this.sVal = sVal;
        }

        public java.util.Date getCreateTime() {
            return createTime;
        }

        public void setCreateTime(java.util.Date createTime) {
            this.createTime = createTime;
        }
    }

    public static class NoDefaultConstructor {
        public NoDefaultConstructor(String name) {
        }
    }
}
