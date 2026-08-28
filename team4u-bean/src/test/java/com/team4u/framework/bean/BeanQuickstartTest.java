package com.team4u.framework.bean;

import com.team4u.framework.bean.exception.NoSuchBeanDefinitionException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Pure Java quickstart for the local BeanManager behavior.
 */
public class BeanQuickstartTest {

    @Test
    public void localBeansAreRegisteredAndFoundByNameTypeAndCollection() {
        BeanManager manager = BeanManager.getInstance();
        TaskSixteenLocalService service = new TaskSixteenLocalService();

        manager.registerBean("taskSixteenLocalService", service);

        Assert.assertSame(service, manager.getBean("taskSixteenLocalService"));
        Assert.assertSame(service, manager.getBean(TaskSixteenLocalService.class));

        Map<String, TaskSixteenLocalService> beans =
                manager.getBeansOfType(TaskSixteenLocalService.class);
        Assert.assertEquals(1, beans.size());
        Assert.assertSame(service, beans.get("taskSixteenLocalService"));
    }

    @Test
    public void loadBeanKeepsTheFirstInstanceAndReusesItOnLaterCalls() {
        BeanManager manager = BeanManager.getInstance();

        TaskSixteenCachedService first = manager.loadBean(
                TaskSixteenCachedService.class,
                new Supplier<TaskSixteenCachedService>() {
                    @Override
                    public TaskSixteenCachedService get() {
                        return new TaskSixteenCachedService();
                    }
                });
        TaskSixteenCachedService second = manager.loadBean(
                TaskSixteenCachedService.class,
                new Supplier<TaskSixteenCachedService>() {
                    @Override
                    public TaskSixteenCachedService get() {
                        Assert.fail("existing bean must be reused");
                        return null;
                    }
                });

        Assert.assertSame(first, second);
    }

    @Test
    public void requiredBeanFailsWhenNoProviderCanSupplyIt() {
        try {
            BeanManager.getInstance().getRequiredBean(TaskSixteenMissingService.class);
            Assert.fail("expected NoSuchBeanDefinitionException");
        } catch (NoSuchBeanDefinitionException e) {
            Assert.assertEquals(
                    "No qualifying bean of type " + TaskSixteenMissingService.class.getName(),
                    e.getMessage());
        }
    }
    public static class TaskSixteenLocalService {
    }

    public static class TaskSixteenCachedService {
    }

    public static class TaskSixteenMissingService {
    }
}
