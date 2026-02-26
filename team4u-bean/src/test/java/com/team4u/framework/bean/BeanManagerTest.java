package com.team4u.framework.bean;

import com.team4u.framework.bean.core.BeanFactory;
import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import com.team4u.framework.bean.provider.LocalBeanContainer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

/**
 * BeanManager 深度测试用例
 *
 * @author team4u
 */
public class BeanManagerTest {

    private BeanManager manager;

    @Before
    public void setUp() {
        manager = BeanManager.getInstance();
    }

    @Test
    public void testRegisterAndGetBean() {
        BeanSample sample = new BeanSample();
        sample.setName("test");

        manager.registerBean("testSample", sample);

        // 按名称获取
        Assert.assertEquals(sample, manager.getBean("testSample"));
        // 按类型获取
        Assert.assertEquals(sample, manager.getBean(BeanSample.class));
    }

    @Test
    public void testRegisterDuplicateBean() {
        LocalBeanContainer container = new LocalBeanContainer();
        Assert.assertTrue(container.registerBean("b1", "v1"));
        // 重复注册应返回 false
        Assert.assertFalse(container.registerBean("b1", "v2"));
        Assert.assertEquals("v1", container.getBean("b1"));
    }

    @Test
    public void testLoadBean() {
        // 第一次获取，通过 builder 创建
        String result = manager.loadBean(String.class, () -> "Hello");
        Assert.assertEquals("Hello", result);

        // 第二次获取，直接从容器取
        Assert.assertEquals("Hello", manager.getBean(String.class));
    }

    @Test
    public void testGetBeansOfType() {
        manager.registerBean("s1", "v1");
        manager.registerBean("s2", "v2");

        Map<String, String> beans = manager.getBeansOfType(String.class);
        Assert.assertTrue(beans.containsKey("s1"));
        Assert.assertTrue(beans.containsKey("s2"));
    }

    @Test(expected = NoSuchBeanDefinitionException.class)
    public void testGetRequiredBeanNotFound() {
        manager.getRequiredBean(Double.class);
    }

    /**
     * 测试容器优先级（Order）
     */
    @Test
    public void testProviderPriority() {
        // 创建一个高优先级的 Mock 容器
        manager.addProvider(new BeanFactory() {
            @Override
            public <T> T getBean(String name) {
                return name.equals("priorityBean") ? (T) "highPriority" : null;
            }

            @Override
            public <T> T getBean(Class<T> type) {
                return null;
            }

            @Override
            public <T> Map<String, T> getBeansOfType(Class<T> type) {
                return Collections.emptyMap();
            }

            @Override
            public int getOrder() {
                return 0; // 极高优先级
            }
        });

        // 在本地容器中也注册一个同名 Bean
        manager.registerBean("priorityBean", "lowPriority");

        // 应该获取到高优先级容器的值
        Assert.assertEquals("highPriority", manager.getBean("priorityBean"));
    }
}
