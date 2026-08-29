package com.team4u.framework.singleflight.spring;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlights;
import com.team4u.framework.singleflight.core.SingleFlightEngine;
import com.team4u.framework.singleflight.core.SingleFlightKeys;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Spring 集成环境下的 BPP 冒烟测试：@EnableSingleFlight 自动代理、
 * 注解仅在实现类方法（JDK 代理场景）时拦截依然生效（回归测试）、
 * 无注解 Bean 不代理、final 类快速失败
 *
 * @author jay.wu
 */
public class SingleFlightSpringTest {

    private TestConfigContext config;
    private TestKvContext kv;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        SingleFlights.init(config.getConfigManager(), kv.store(), kv.clock());
        // FAIL_FAST：锁被他人持有时抛冲突异常，用于断言拦截确实生效
        config.put("team4u.singleflight.spring.point",
                "{\"id\":\"spring.point\",\"key\":\"${id}\",\"contention\":\"FAIL_FAST\","
                        + "\"cacheEnabled\":false}");
    }

    @After
    public void tearDown() {
        SingleFlights.destroy();
        config.destroy();
        kv.close();
    }

    /**
     * 含 @SingleFlight 方法的 Bean 被自动代理包装，注解方法进入协调流程
     */
    @Test
    public void annotatedBeanInterceptedByAutoProxy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            UserService service = context.getBean(UserService.class);

            assertNotSame("含 @SingleFlight 方法的 bean 应被代理包装",
                    UserServiceImpl.class, service.getClass());
            assertEquals("u-1", service.load("u"));
            assertEquals("不同 id 独立窗口", "v-2", service.load("v"));
            assertEquals(2, app.userService.count.get());
        }
    }

    /**
     * 回归测试（bug 修复验收点）：注解仅标注在实现类方法、接口未标注时，
     * JDK 接口代理下拦截必须生效——旧实现不查 targetClass 导致误判直通
     */
    @Test
    public void annotationOnlyOnImplementationStillInterceptedUnderJdkProxy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            ReportService report = context.getBean(ReportService.class);

            assertTrue("接口未标注注解、实现方法标注：注入的仍是可调用代理",
                    ReportService.class.isAssignableFrom(report.getClass()));

            // 用已持有的锁占住窗口：若拦截生效，后续调用抛冲突异常；
            // 旧 bug 下注解被漏判直通，会直接执行并返回 "raw"
            kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE,
                    SingleFlightKeys.compose("spring.point", "u", null)),
                    KvRecord.of("other", 60000, 0), PutMode.SET);
            try {
                report.report("u");
                fail("expected SingleFlightConflictException (annotation must be resolved via targetClass)");
            } catch (SingleFlightConflictException expected) {
                // 拦截生效：注解经 targetClass 实现方法命中
            }
        }
    }

    /**
     * 无注解 Bean 原样返回，不被代理
     */
    @Test
    public void plainBeanNotProxied() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            PlainService plain = context.getBean(PlainService.class);

            assertSame("无注解 Bean 不应被包装", PlainService.class, plain.getClass());
            assertEquals("raw", plain.run());
            assertEquals(1, app.plainService.count.get());
        }
    }

    /**
     * final 类无法代理：singleflight 的失败策略是快速失败（覆盖 onProxyFailure），
     * 容器启动即暴露而非静默直通（独立配置类，避免阻断其他用例的容器）
     */
    @Test
    public void finalClassFailsFastInsteadOfSilentPassThrough() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(FinalApp.class)) {
            fail("final 类含注解方法应导致启动失败");
        } catch (Exception e) {
            assertTrue("应传播代理构建失败|" + e.getMessage(),
                    String.valueOf(e.getMessage()).contains("finalService"));
        }
    }

    // ------------------------------------------------- 测试装配

    @Configuration
    @EnableSingleFlight
    public static class TestApp {

        final UserServiceImpl userService = new UserServiceImpl();
        final ReportServiceImpl reportService = new ReportServiceImpl();
        final PlainService plainService = new PlainService();

        @Bean
        public UserService userService() {
            return userService;
        }

        @Bean
        public ReportService reportService() {
            return reportService;
        }

        @Bean
        public PlainService plainService() {
            return plainService;
        }
    }

    /**
     * 仅含 final 类 Bean 的独立配置：启动失败用例专用
     */
    @Configuration
    @EnableSingleFlight
    public static class FinalApp {

        @Bean
        public FinalService finalService() {
            return new FinalService();
        }
    }

    /**
     * 注解在接口方法上：常规场景
     */
    public interface UserService {

        @com.team4u.framework.singleflight.proxy.SingleFlight("spring.point")
        String load(String id);
    }

    public static class UserServiceImpl implements UserService {

        final AtomicInteger count = new AtomicInteger();

        @Override
        public String load(String id) {
            return id + "-" + count.incrementAndGet();
        }
    }

    /**
     * 注解仅在实现方法（接口未标注）：JDK 代理场景的回归靶心
     */
    public interface ReportService {

        String report(String id);
    }

    public static class ReportServiceImpl implements ReportService {

        @com.team4u.framework.singleflight.proxy.SingleFlight("spring.point")
        @Override
        public String report(String id) {
            return "raw";
        }
    }

    public static class PlainService {

        final AtomicInteger count = new AtomicInteger();

        public String run() {
            count.incrementAndGet();
            return "raw";
        }
    }

    public static final class FinalService {

        @com.team4u.framework.singleflight.proxy.SingleFlight("spring.point")
        public String call() {
            return "raw";
        }
    }
}
