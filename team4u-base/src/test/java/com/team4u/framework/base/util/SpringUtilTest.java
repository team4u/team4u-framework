package com.team4u.framework.base.util;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

/**
 * SpringUtil 单元测试
 *
 * @author jay.wu
 */
public class SpringUtilTest {

    private ApplicationContext mockContext;

    @Before
    public void setUp() {
        // 使用 Mockito 模拟 ApplicationContext
        mockContext = Mockito.mock(ApplicationContext.class);
        SpringUtil springUtil = new SpringUtil();
        springUtil.setApplicationContext(mockContext);
    }

    @Test
    public void testGetBeanByType() {
        String mockBean = "mockValue";
        Mockito.when(mockContext.getBean(String.class)).thenReturn(mockBean);

        // 测试根据类型获取 Bean
        String result = SpringUtil.getBean(String.class);
        Assert.assertEquals("获取的 Bean 与预期不符", mockBean, result);
        Mockito.verify(mockContext).getBean(String.class);
    }

    @Test
    public void testGetBeanByName() {
        Object mockBean = new Object();
        Mockito.when(mockContext.getBean("testBean")).thenReturn(mockBean);

        // 测试根据名称获取 Bean
        Object result = SpringUtil.getBean("testBean");
        Assert.assertEquals("获取的 Bean 与预期不符", mockBean, result);
        Mockito.verify(mockContext).getBean("testBean");
    }
}
