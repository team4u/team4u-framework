package com.team4u.framework.bean.spring;

import com.team4u.framework.bean.BeanManager;
import com.team4u.framework.bean.provider.SpringBeanContainer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

public class Team4uBeanConfigurationTest {

    private AnnotationConfigApplicationContext context;

    @After
    public void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void explicitImportRegistersOneActiveSpringAdapter() {
        context = new AnnotationConfigApplicationContext(ApplicationConfiguration.class);

        String[] adapterNames = context.getBeanNamesForType(SpringBeanContainer.class);
        Assert.assertEquals(1, adapterNames.length);

        SpringBeanContainer adapter = context.getBean(SpringBeanContainer.class);
        Assert.assertNotNull(adapter);
        Assert.assertSame(adapter, context.getBean(adapterNames[0]));
    }

    @Test
    public void beanManagerResolvesSpringBeansByNameTypeAndCollection() {
        context = new AnnotationConfigApplicationContext(ApplicationConfiguration.class);
        TaskSixteenSpringService service = context.getBean(TaskSixteenSpringService.class);

        BeanManager manager = BeanManager.getInstance();

        Assert.assertSame(service, manager.getBean("taskSixteenSpringService"));
        Assert.assertSame(service, manager.getBean(TaskSixteenSpringService.class));

        Map<String, TaskSixteenSpringService> beans =
                manager.getBeansOfType(TaskSixteenSpringService.class);
        Assert.assertEquals(1, beans.size());
        Assert.assertSame(service, beans.get("taskSixteenSpringService"));
    }

    @Test
    public void adapterRegistersSingletonIntoActiveSpringContext() {
        context = new AnnotationConfigApplicationContext(ApplicationConfiguration.class);
        SpringBeanContainer adapter = context.getBean(SpringBeanContainer.class);
        TaskSixteenDynamicService dynamicService = new TaskSixteenDynamicService();

        Assert.assertTrue(adapter.registerBean("taskSixteenDynamicService", dynamicService));
        Assert.assertSame(
                dynamicService,
                context.getBean("taskSixteenDynamicService", TaskSixteenDynamicService.class));
    }

    @Test
    public void closedContextNoLongerSuppliesSpringBeans() {
        context = new AnnotationConfigApplicationContext(ApplicationConfiguration.class);
        SpringBeanContainer adapter = context.getBean(SpringBeanContainer.class);
        context.close();

        Assert.assertNull(BeanManager.getInstance().getBean("taskSixteenSpringService"));
        Assert.assertNull(BeanManager.getInstance().getBean(TaskSixteenSpringService.class));
        Assert.assertTrue(BeanManager.getInstance()
                .getBeansOfType(TaskSixteenSpringService.class)
                .isEmpty());
        Assert.assertFalse(adapter.registerBean(
                "taskSixteenDynamicService", new TaskSixteenDynamicService()));
    }

    @Configuration
    @Import(Team4uBeanConfiguration.class)
    public static class ApplicationConfiguration {

        @Bean
        public TaskSixteenSpringService taskSixteenSpringService() {
            return new TaskSixteenSpringService();
        }
    }

    public static class TaskSixteenSpringService {
    }

    public static class TaskSixteenDynamicService {
    }
}
