package com.team4u.framework.base.instance;

import org.junit.Assert;
import org.junit.Test;

/**
 * SingletonFactory 单元测试
 */
public class SingletonFactoryTest {

    @Test
    public void testGetInstance() {
        // 第一次获取
        TestService instance1 = SingletonFactory.getInstance(TestService.class);
        Assert.assertNotNull(instance1);

        // 第二次获取，应该是同一个对象
        TestService instance2 = SingletonFactory.getInstance(TestService.class);
        Assert.assertSame(instance1, instance2);
    }

    @Test
    public void testInvalidate() {
        TestService instance1 = SingletonFactory.getInstance(TestService.class);

        // 移除缓存
        SingletonFactory.invalidate(TestService.class);

        // 再次获取，应该是新对象
        TestService instance2 = SingletonFactory.getInstance(TestService.class);
        Assert.assertNotSame(instance1, instance2);
    }

    @Test
    public void testClear() {
        TestService instance1 = SingletonFactory.getInstance(TestService.class);

        // 清空缓存
        SingletonFactory.clear();

        // 再次获取，应该是新对象
        TestService instance2 = SingletonFactory.getInstance(TestService.class);
        Assert.assertNotSame(instance1, instance2);
    }

    /**
     * 测试用的简单类
     */
    public static class TestService {
        public TestService() {
        }
    }
}
