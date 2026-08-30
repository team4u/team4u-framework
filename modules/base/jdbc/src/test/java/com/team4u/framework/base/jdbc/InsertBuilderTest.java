package com.team4u.framework.base.jdbc;

import org.junit.Assert;
import org.junit.Test;

/**
 * InsertBuilder 单元测试
 *
 * @author jay.wu
 */
public class InsertBuilderTest {

    @Test
    public void testBuildSql() {
        InsertBuilder builder = new InsertBuilder("test_table")
                .column("name", "jay")
                .column("age", 18);

        String expectedSql = "INSERT INTO test_table (name, age) VALUES (?, ?)";
        Assert.assertEquals(expectedSql, builder.getSql());
        Assert.assertArrayEquals(new Object[]{"jay", 18}, builder.getParams());
    }

    @Test
    public void testColumnIfNotNull() {
        InsertBuilder builder = new InsertBuilder("test_table")
                .column("name", "jay")
                .columnIfNotNull("age", null)
                .columnIfNotNull("gender", "male");

        String expectedSql = "INSERT INTO test_table (name, gender) VALUES (?, ?)";
        Assert.assertEquals(expectedSql, builder.getSql());
        Assert.assertArrayEquals(new Object[]{"jay", "male"}, builder.getParams());
    }

    @Test(expected = IllegalStateException.class)
    public void testNoColumns() {
        InsertBuilder builder = new InsertBuilder("test_table");
        builder.getSql();
    }

    @Test
    public void testSingleColumn() {
        InsertBuilder builder = new InsertBuilder("t")
                .column("c", 1);

        Assert.assertEquals("INSERT INTO t (c) VALUES (?)", builder.getSql());
        Assert.assertArrayEquals(new Object[]{1}, builder.getParams());
    }
}
