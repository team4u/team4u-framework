package com.team4u.framework.log.integration;

import com.team4u.framework.log.core.LogEvent;
import com.team4u.framework.log.proxy.AutoLogTrace;
import com.team4u.framework.log.spring.LogSpringConfiguration;
import com.team4u.framework.log.support.TestLogHelper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.event.Level;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

public class SpringLogTraceIntegrationTest {

    private TestLogHelper logHelper;

    @Before
    public void setup() {
        logHelper = TestLogHelper.start();
    }

    @After
    public void teardown() {
        logHelper.stop();
    }

    @Test
    public void testMethodLevelTraceWorksInSpringBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ImportedConfig.class)) {
            SpringUserService service = context.getBean(SpringUserService.class);

            String result = service.register("user-1");

            Assert.assertEquals("SUCCESS", result);
            LogEvent event = logHelper.lastEvent();
            Assert.assertNotNull(event);
            Assert.assertEquals("RegisterUser", event.getAction());
            Assert.assertEquals("success", event.getStatus());
            Assert.assertEquals("SUCCESS", event.get("resp"));
            Assert.assertTrue("应记录方法参数", hasPayloadValue(event, "userId", "user-1") || hasPayloadValue(event, "arg0", "user-1"));
        }
    }

    @Test
    public void testSlowThresholdAndIgnoreExceptionsWorkInSpringBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ImportedConfig.class)) {
            SpringUserService service = context.getBean(SpringUserService.class);

            service.slow();
            LogEvent slowEvent = logHelper.lastEvent();
            Assert.assertNotNull(slowEvent);
            Assert.assertEquals(Level.WARN, slowEvent.getLevel());
            Assert.assertEquals("slow_success", slowEvent.getStatus());

            try {
                service.failBusiness();
                Assert.fail("预期抛出业务异常");
            } catch (IllegalArgumentException expected) {
                LogEvent errorEvent = logHelper.lastEvent();
                Assert.assertNotNull(errorEvent);
                Assert.assertEquals(Level.WARN, errorEvent.getLevel());
                Assert.assertEquals("business_error", errorEvent.getStatus());
                Assert.assertEquals("bad request", errorEvent.get("errMsg"));
            }
        }
    }

    @Test
    public void testClassLevelAndInterfaceMethodAnnotationAreResolved() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ImportedConfig.class)) {
            ClassLevelService classLevelService = context.getBean(ClassLevelService.class);
            GreetingService greetingService = context.getBean(GreetingService.class);

            classLevelService.execute();
            LogEvent classEvent = logHelper.lastEvent();
            Assert.assertNotNull(classEvent);
            Assert.assertEquals("ClassLevelAction", classEvent.getAction());

            greetingService.greet("neo");
            LogEvent methodEvent = logHelper.lastEvent();
            Assert.assertNotNull(methodEvent);
            Assert.assertEquals("GreetUser", methodEvent.getAction());
            Assert.assertTrue("应记录接口方法参数", hasPayloadValue(methodEvent, "name", "neo") || hasPayloadValue(methodEvent, "arg0", "neo"));
        }
    }

    @Test
    public void testWithoutImportNoSpringAdviceRuns() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(NoImportConfig.class)) {
            SpringUserService service = context.getBean(SpringUserService.class);

            service.register("user-2");

            Assert.assertNull(logHelper.lastEvent());
        }
    }

    private boolean hasPayloadValue(LogEvent event, String key, String expected) {
        return expected.equals(event.get(key));
    }

    interface GreetingService {
        String greet(String name);
    }

    @Configuration
    @Import(LogSpringConfiguration.class)
    static class ImportedConfig {
        @Bean
        public SpringUserService springUserService() {
            return new SpringUserService();
        }

        @Bean
        public ClassLevelService classLevelService() {
            return new ClassLevelService();
        }

        @Bean
        public GreetingService greetingService() {
            return new GreetingServiceImpl();
        }
    }

    @Configuration
    static class NoImportConfig {
        @Bean
        public SpringUserService springUserService() {
            return new SpringUserService();
        }
    }

    static class SpringUserService {
        @AutoLogTrace(action = "RegisterUser")
        public String register(String userId) {
            return "SUCCESS";
        }

        @AutoLogTrace(slowThreshold = 10)
        public void slow() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        @AutoLogTrace(ignoreExceptions = IllegalArgumentException.class)
        public void failBusiness() {
            throw new IllegalArgumentException("bad request");
        }
    }

    @AutoLogTrace(action = "ClassLevelAction")
    static class ClassLevelService {
        public void execute() {
        }
    }

    static class GreetingServiceImpl implements GreetingService {
        @Override
        @AutoLogTrace(action = "GreetUser")
        public String greet(String name) {
            return "hello " + name;
        }
    }
}
