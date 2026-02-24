package com.team4u.policy.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * ServiceLoaderUtil 单元测试
 */
public class ServiceLoaderUtilTest {

    @Test
    public void testLoadAvailableList() {
        // 测试加载 java.sql.Driver（SPI 的常见用法）
        // 如果环境中没有特殊的 SPI 实现，至少不应该报错并返回一个列表
        try {
            Class<?> clazz = Class.forName("java.sql.Driver");
            List<?> list = ServiceLoaderUtil.loadAvailableList(clazz);
            Assert.assertNotNull("加载结果不应为 null", list);
        } catch (ClassNotFoundException e) {
            // 如果 java.sql.Driver 不存在，测试加载自身的类以确保不会崩溃
            List<ServiceLoaderUtil> list = ServiceLoaderUtil.loadAvailableList(ServiceLoaderUtil.class);
            Assert.assertNotNull("加载结果不应为 null", list);
        }
    }
}
