package com.team4u.criterion.model.convert;

import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.Criteria;

import java.util.HashMap;
import java.util.Map;

/**
 * 版本号转换匹配单元测试
 */
public class VersionValueConverterTest {

    private final Criteria criteria = Criteria.standard();

    // ==================== null 值处理测试 ====================

    @Test
    public void actualIsNull_returnsFalse() {
        Assert.assertFalse(matches("v:version = '1.0.0'", null));
    }

    // ==================== 标准版本比较测试 (1.0.0 vs 1.0.1) ====================

    @Test
    public void greaterThan_actualGreater_returnsTrue() {
        Assert.assertTrue(matches("v:version > '1.0.0'", "1.0.1"));
        Assert.assertTrue(matches("v:version > '1.0.0'", "1.1.0"));
        Assert.assertTrue(matches("v:version > '1.0.0'", "2.0.0"));
    }

    @Test
    public void greaterThan_actualEqual_returnsFalse() {
        Assert.assertFalse(matches("v:version > '1.0.0'", "1.0.0"));
    }

    @Test
    public void greaterThan_actualLess_returnsFalse() {
        Assert.assertFalse(matches("v:version > '1.0.1'", "1.0.0"));
        Assert.assertFalse(matches("v:version > '1.0.1'", "0.9.9"));
    }

    // ==================== 操作符 >= 测试 ====================

    @Test
    public void greaterOrEqual_actualGreater_returnsTrue() {
        Assert.assertTrue(matches("v:version >= '1.0.0'", "1.0.1"));
        Assert.assertTrue(matches("v:version >= '1.0.0'", "1.1.0"));
        Assert.assertTrue(matches("v:version >= '1.0.0'", "2.0.0"));
    }

    @Test
    public void greaterOrEqual_actualEqual_returnsTrue() {
        Assert.assertTrue(matches("v:version >= '1.0.0'", "1.0.0"));
    }

    @Test
    public void greaterOrEqual_actualLess_returnsFalse() {
        Assert.assertFalse(matches("v:version >= '1.0.1'", "1.0.0"));
        Assert.assertFalse(matches("v:version >= '1.0.1'", "0.9.9"));
    }

    // ==================== 操作符 < 测试 ====================

    @Test
    public void lessThan_actualLess_returnsTrue() {
        Assert.assertTrue(matches("v:version < '1.0.1'", "1.0.0"));
        Assert.assertTrue(matches("v:version < '1.0.1'", "0.9.9"));
    }

    @Test
    public void lessThan_actualEqual_returnsFalse() {
        Assert.assertFalse(matches("v:version < '1.0.0'", "1.0.0"));
    }

    @Test
    public void lessThan_actualGreater_returnsFalse() {
        Assert.assertFalse(matches("v:version < '1.0.0'", "1.0.1"));
        Assert.assertFalse(matches("v:version < '1.0.0'", "2.0.0"));
    }

    // ==================== 操作符 <= 测试 ====================

    @Test
    public void lessOrEqual_actualLess_returnsTrue() {
        Assert.assertTrue(matches("v:version <= '1.0.1'", "1.0.0"));
        Assert.assertTrue(matches("v:version <= '1.0.1'", "0.9.9"));
    }

    @Test
    public void lessOrEqual_actualEqual_returnsTrue() {
        Assert.assertTrue(matches("v:version <= '1.0.0'", "1.0.0"));
    }

    @Test
    public void lessOrEqual_actualGreater_returnsFalse() {
        Assert.assertFalse(matches("v:version <= '1.0.0'", "1.0.1"));
        Assert.assertFalse(matches("v:version <= '1.0.0'", "2.0.0"));
    }

    // ==================== 操作符 = 测试 ====================

    @Test
    public void equal_actualEqual_returnsTrue() {
        Assert.assertTrue(matches("v:version = '1.0.0'", "1.0.0"));
    }

    @Test
    public void equal_actualNotEqual_returnsFalse() {
        Assert.assertFalse(matches("v:version = '1.0.0'", "1.0.1"));
        Assert.assertFalse(matches("v:version = '1.0.0'", "0.9.9"));
    }

    // ==================== 操作符 == 测试 ====================

    @Test
    public void equalDouble_actualEqual_returnsTrue() {
        Assert.assertTrue(matches("v:version == '1.0.0'", "1.0.0"));
    }

    @Test
    public void equalDouble_actualNotEqual_returnsFalse() {
        Assert.assertFalse(matches("v:version == '1.0.0'", "1.0.1"));
    }

    // ==================== 操作符 != 测试 ====================

    @Test
    public void notEqual_actualNotEqual_returnsTrue() {
        Assert.assertTrue(matches("v:version != '1.0.0'", "1.0.1"));
        Assert.assertTrue(matches("v:version != '1.0.0'", "0.9.9"));
        Assert.assertTrue(matches("v:version != '1.0.0'", "2.0.0"));
    }

    @Test
    public void notEqual_actualEqual_returnsFalse() {
        Assert.assertFalse(matches("v:version != '1.0.0'", "1.0.0"));
    }

    // ==================== 非标准长度版本测试 (1.0 vs 1.0.0) ====================

    @Test
    public void differentLength_equal_1_0_vs_1_0_0() {
        Assert.assertTrue(matches("v:version = '1.0.0'", "1.0"));
    }

    @Test
    public void differentLength_equal_1_0_0_vs_1_0() {
        Assert.assertTrue(matches("v:version = '1.0'", "1.0.0"));
    }

    // ==================== 带前缀版本测试 (v1.0 vs 1.0) ====================

    @Test
    public void prefixedVersion_vPrefix_equal() {
        Assert.assertFalse(matches("v:version = '1.0'", "v1.0"));
    }

    @Test
    public void prefixedVersion_bothWithPrefix_equal() {
        Assert.assertTrue(matches("v:version = 'v1.0'", "v1.0"));
    }

    // ==================== 复杂版本号测试 ====================

    @Test
    public void complexVersion_fourSegments() {
        Assert.assertTrue(matches("v:version > '1.0.0.0'", "1.0.0.1"));
        Assert.assertFalse(matches("v:version > '1.0.0.0'", "1.0.0.0"));
    }

    @Test
    public void complexVersion_numericComparison() {
        Assert.assertTrue(matches("v:version > '1.9'", "1.10"));
    }

    @Test
    public void complexVersion_leadingZeros() {
        Assert.assertTrue(matches("v:version = '1.01.001'", "1.1.1"));
    }

    // ==================== 边界值测试 ====================

    @Test
    public void emptyVersion_comparison() {
        Assert.assertTrue(matches("v:version = ''", ""));
    }

    @Test
    public void largeVersionNumbers() {
        Assert.assertTrue(matches("v:version > '100.200.300'", "100.200.301"));
        Assert.assertFalse(matches("v:version > '100.200.300'", "100.200.299"));
    }

    private boolean matches(String expression, Object actual) {
        Map<String, Object> map = new HashMap<>();
        map.put("v", actual);
        return criteria.matches(expression, map);
    }
}
