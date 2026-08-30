package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * {@link ServiceLoaderUtil} 单元测试
 */
public class ServiceLoaderUtilTest {

    @Test
    public void testLoadAvailableList() {
        // 测试加载 java.sql.Driver（SPI 的常见用法）
        // 若当前环境中无特定 SPI 实现，应至少返回空列表而非报错
        try {
            Class<?> clazz = Class.forName("java.sql.Driver");
            List<?> list = ServiceLoaderUtil.loadAvailableList(clazz);
            Assert.assertNotNull("加载结果不应为 null", list);
        } catch (ClassNotFoundException e) {
            // 若 java.sql.Driver 不存在，则测试加载当前类以确保基础逻辑正常
            List<ServiceLoaderUtil> list = ServiceLoaderUtil.loadAvailableList(ServiceLoaderUtil.class);
            Assert.assertNotNull("加载结果不应为 null", list);
        }
    }
}
