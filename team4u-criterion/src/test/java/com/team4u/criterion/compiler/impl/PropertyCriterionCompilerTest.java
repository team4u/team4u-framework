package com.team4u.criterion.compiler.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.Assert;
import org.junit.Test;
import com.team4u.criterion.Criteria;

/**
 * 属性匹配器测试
 * <p>
 * 测试嵌套属性访问及中间属性为 null 时的安全处理
 */
public class PropertyCriterionCompilerTest {

    private final Criteria criteria = Criteria.standard();

    @Test
    public void nestedPropertyAccess() {
        User user = new User("zhangsan", new Address("Beijing", "Chaoyang"));

        // 嵌套属性访问: user.address.city
        Assert.assertTrue(criteria.matches("address.city == 'Beijing'", user));
        Assert.assertFalse(criteria.matches("address.city == 'Shanghai'", user));

        // 深层嵌套访问: user.address.district
        Assert.assertTrue(criteria.matches("address.district == 'Chaoyang'", user));
    }

    @Test
    public void nestedPropertyWithNullIntermediateShouldReturnFalse() {
        // 当中间属性 (address) 为 null 时，应安全返回 false 而不是抛出 NPE
        User user = new User("lisi", null);

        Assert.assertFalse(criteria.matches("address.city == 'Beijing'", user));
        Assert.assertFalse(criteria.matches("address.district == 'Chaoyang'", user));
    }

    @Test
    public void nestedPropertyIsNull() {
        User user = new User("wangwu", new Address(null, "Pudong"));

        // 嵌套属性本身为 null 时的处理
        Assert.assertTrue(criteria.matches("address.city is null", user));
        Assert.assertTrue(criteria.matches("address.city is empty", user));
        Assert.assertFalse(criteria.matches("address.city == 'Beijing'", user));
    }

    @Test
    public void simplePropertyAccess() {
        User user = new User("zhangsan", new Address("Beijing", "Chaoyang"));

        // 简单属性访问
        Assert.assertTrue(criteria.matches("name == 'zhangsan'", user));
        Assert.assertFalse(criteria.matches("name == 'lisi'", user));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        private String name;
        private Address address;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Address {
        private String city;
        private String district;
    }
}
