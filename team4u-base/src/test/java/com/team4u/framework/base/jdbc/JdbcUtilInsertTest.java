package com.team4u.framework.base.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.SQLException;

public class JdbcUtilInsertTest {

    private DataSource dataSource;

    @Before
    public void setUp() throws SQLException {
        JdbcDataSource h2DataSource = new JdbcDataSource();
        h2DataSource.setURL("jdbc:h2:mem:test_insert_db;DB_CLOSE_DELAY=-1;MODE=MySQL");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
        this.dataSource = h2DataSource;

        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS test_insert_user");
        JdbcUtil.execute(dataSource, "CREATE TABLE test_insert_user (id INT AUTO_INCREMENT PRIMARY KEY, user_name VARCHAR(255), user_age INT)");
    }

    @Test
    public void testInsertBean() throws SQLException {
        UserBean user = new UserBean();
        user.setUserName("张三");
        user.setUserAge(20);

        int affected = JdbcUtil.insert(dataSource, "test_insert_user", user);
        Assert.assertEquals("插入记录数不正确", 1, affected);

        UserBean saved = JdbcUtil.queryOne(dataSource, "SELECT user_name, user_age FROM test_insert_user WHERE user_name = ?", UserBean.class, "张三");
        Assert.assertNotNull("未查询到保存的对象", saved);
        Assert.assertEquals("姓名映射错误", "张三", saved.getUserName());
        Assert.assertEquals("年龄映射错误", 20, (int) saved.getUserAge());
    }

    @Test
    public void testInsertAndReturnKey() throws SQLException {
        UserBean user = new UserBean();
        user.setUserName("李四");
        user.setUserAge(25);

        Long id = JdbcUtil.insertAndReturnKey(dataSource, "test_insert_user", user);
        Assert.assertNotNull("未返回生成的主键", id);
        Assert.assertTrue("主键值不合法", id > 0);

        UserBean saved = JdbcUtil.queryOne(dataSource, "SELECT user_name FROM test_insert_user WHERE id = ?", UserBean.class, id);
        Assert.assertNotNull("未通过 ID 查询到保存的对象", saved);
        Assert.assertEquals("姓名不匹配", "李四", saved.getUserName());
    }

    @Test
    public void testInsertWithInheritance() throws SQLException {
        JdbcUtil.execute(dataSource, "DROP TABLE IF EXISTS test_ext_user");
        JdbcUtil.execute(dataSource, "CREATE TABLE test_ext_user (id INT AUTO_INCREMENT PRIMARY KEY, user_name VARCHAR(255), extra_info VARCHAR(255))");

        ExtUserBean user = new ExtUserBean();
        user.setUserName("王五");
        user.setExtraInfo("附加信息");

        JdbcUtil.insert(dataSource, "test_ext_user", user);

        MapResult res = JdbcUtil.queryOne(dataSource, "SELECT user_name, extra_info FROM test_ext_user WHERE user_name = ?", MapResult.class, "王五");
        Assert.assertNotNull(res);
        Assert.assertEquals("王五", res.userName);
        Assert.assertEquals("附加信息", res.extraInfo);
    }

    public static class UserBean {
        private String userName;
        private Integer userAge;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public Integer getUserAge() {
            return userAge;
        }

        public void setUserAge(Integer userAge) {
            this.userAge = userAge;
        }
    }

    public static class ExtUserBean extends UserBean {
        private String extraInfo;

        public String getExtraInfo() {
            return extraInfo;
        }

        public void setExtraInfo(String extraInfo) {
            this.extraInfo = extraInfo;
        }
    }

    public static class MapResult {
        public String userName;
        public String extraInfo;
    }
}
