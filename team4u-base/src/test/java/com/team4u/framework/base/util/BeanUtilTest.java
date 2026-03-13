package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * BeanUtil 单元测试
 *
 * @author AI
 */
public class BeanUtilTest {

    @Test
    public void testToBean() {
        Map<String, Object> map = new HashMap<>();
        map.put("userName", "testUser");
        map.put("userAge", 18);
        
        // 1. 正常精确匹配测试
        TestUser bean = BeanUtil.toBean(map, TestUser.class, new CopyOptions());
        Assert.assertNotNull(bean);
        Assert.assertEquals("testUser", bean.getUserName());
        Assert.assertEquals(18, bean.getUserAge());

        // 2. 忽略大小写及特殊字符（如下划线、横线）的匹配测试
        Map<String, Object> fuzzyMap = new HashMap<>();
        fuzzyMap.put("user_name", "fuzzyUser");
        fuzzyMap.put("USER-AGE", 20);
        
        CopyOptions ignoreCaseOptions = new CopyOptions().ignoreCase();
        TestUser fuzzyBean = BeanUtil.toBean(fuzzyMap, TestUser.class, ignoreCaseOptions);
        
        Assert.assertNotNull(fuzzyBean);
        Assert.assertEquals("fuzzyUser", fuzzyBean.getUserName());
        Assert.assertEquals(20, fuzzyBean.getUserAge());
    }

    @Test
    public void testGetProperty() {
        TestUser user = new TestUser();
        user.setUserName("propUser");
        
        // 1. 获取直接属性
        Object userName = BeanUtil.getProperty(user, "userName");
        Assert.assertEquals("propUser", userName);

        // 2. 获取不存在的属性
        Object notExist = BeanUtil.getProperty(user, "notExist");
        Assert.assertNull(notExist);
    }

    @Test
    public void testGetNestedProperty() {
        TestUser user = new TestUser();
        TestAddress address = new TestAddress();
        address.setCity("Beijing");
        user.setAddress(address);

        // 测试获取级联属性
        Object city = BeanUtil.getProperty(user, "address.city");
        Assert.assertEquals("Beijing", city);
    }

    // 测试用的辅助类
    public static class TestUser {
        private String userName;
        private int userAge;
        private TestAddress address;

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public int getUserAge() {
            return userAge;
        }

        public void setUserAge(int userAge) {
            this.userAge = userAge;
        }

        public TestAddress getAddress() {
            return address;
        }

        public void setAddress(TestAddress address) {
            this.address = address;
        }
    }

    public static class TestAddress {
        private String city;

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }
    }
}
