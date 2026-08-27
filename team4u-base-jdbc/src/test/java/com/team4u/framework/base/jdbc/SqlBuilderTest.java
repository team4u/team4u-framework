package com.team4u.framework.base.jdbc;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * SqlBuilder 单元测试
 *
 * @author jay.wu
 */
public class SqlBuilderTest {

    @Test
    public void testEmptyConstructor() {
        SqlBuilder sb = new SqlBuilder();
        Assert.assertEquals("", sb.getSql());
        Assert.assertEquals(0, sb.getParams().length);
    }

    @Test
    public void testInitialSqlConstructor() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users");
        Assert.assertEquals("SELECT * FROM users", sb.getSql());
        Assert.assertEquals(0, sb.getParams().length);
    }

    @Test
    public void testAppend() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE 1=1");
        sb.append(" AND status = ?", 1);
        sb.append(" AND type IN (?, ?)", "A", "B");

        Assert.assertEquals("SELECT * FROM users WHERE 1=1 AND status = ? AND type IN (?, ?)", sb.getSql());
        Assert.assertArrayEquals(new Object[]{1, "A", "B"}, sb.getParams());
    }

    @Test
    public void testAppendWithNullSnippet() {
        SqlBuilder sb = new SqlBuilder("SELECT *");
        sb.append(null, "param");
        Assert.assertEquals("SELECT *", sb.getSql());
        Assert.assertEquals(0, sb.getParams().length);
    }

    @Test
    public void testAppendIfNotNull() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE 1=1");
        sb.appendIfNotNull(" AND name = ?", "张三");
        sb.appendIfNotNull(" AND age = ?", null);

        Assert.assertEquals("SELECT * FROM users WHERE 1=1 AND name = ?", sb.getSql());
        Assert.assertArrayEquals(new Object[]{"张三"}, sb.getParams());
    }

    @Test
    public void testInIfNotEmpty() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE 1=1");

        // 集合不为空
        List<Integer> ids = Arrays.asList(1, 2, 3);
        sb.inIfNotEmpty(" AND id IN ", ids);

        // 集合为空
        sb.inIfNotEmpty(" AND id IN ", Collections.emptyList());

        // 集合为 null
        sb.inIfNotEmpty(" AND id IN ", null);

        Assert.assertEquals("SELECT * FROM users WHERE 1=1 AND id IN (?, ?, ?)", sb.getSql());
        Assert.assertArrayEquals(new Object[]{1, 2, 3}, sb.getParams());
    }

    @Test
    public void testAndOrCombinations() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE status = ?");
        sb.append(" AND (");

        // 实际上 SqlBuilder.and/or 会自己加括号，这里测试嵌套
        SqlBuilder mainSb = new SqlBuilder("SELECT * FROM users WHERE 1=1");
        mainSb.and(b -> b.append("name = ?", "张三").or(b2 -> b2.append("age > ?", 20)));

        Assert.assertEquals("SELECT * FROM users WHERE 1=1 AND (name = ? OR (age > ?))", mainSb.getSql());
        Assert.assertArrayEquals(new Object[]{"张三", 20}, mainSb.getParams());
    }

    @Test
    public void testNestedGroups() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE 1=1");
        sb.and(b -> b.append("a = ?", 1)
                .and(b2 -> b2.append("b = ?", 2)
                        .or(b3 -> b3.append("c = ?", 3))));

        Assert.assertEquals("SELECT * FROM users WHERE 1=1 AND (a = ? AND (b = ? OR (c = ?)))", sb.getSql());
        Assert.assertArrayEquals(new Object[]{1, 2, 3}, sb.getParams());
    }

    @Test
    public void testEmptyGroup() {
        SqlBuilder sb = new SqlBuilder("SELECT * FROM users WHERE 1=1");
        sb.and(b -> {
            // 什么都不加
        });

        Assert.assertEquals("SELECT * FROM users WHERE 1=1", sb.getSql());
        Assert.assertEquals(0, sb.getParams().length);
    }

    @Test
    public void testAndOnEmptyBuilderDoesNotPrefixOperator() {
        SqlBuilder sb = new SqlBuilder();
        sb.and(b -> b.append("name = ?", "张三"));

        Assert.assertEquals("(name = ?)", sb.getSql());
        Assert.assertArrayEquals(new Object[]{"张三"}, sb.getParams());
    }
}
