package com.team4u.framework.ratelimiter.spring;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.ratelimiter.api.RateLimitException;
import com.team4u.framework.ratelimiter.api.RateLimiters;
import com.team4u.framework.ratelimiter.proxy.RateLimit;
import com.team4u.framework.ratelimiter.proxy.RateLimitReject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * Spring 集成环境下的注解限流测试：@EnableRateLimit 自动代理、final 类跳过
 *
 * @author jay.wu
 */
public class RateLimitSpringTest {

    private TestConfigContext config;
    private TestKvContext kv;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());
        config.put("team4u.ratelimiter.spring.order",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":2,\"key\":\"${orderId}\"}]");
        config.put("team4u.ratelimiter.spring.report",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
        config.put("team4u.ratelimiter.spring.finalsvc",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
        config.put("team4u.ratelimiter.spring.lookup",
                "[{\"id\":\"fw\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
    }

    @After
    public void tearDown() {
        RateLimiters.destroy();
        config.destroy();
        kv.close();
    }

    @Test
    public void annotatedBeanInterceptedByAutoProxy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            OrderService orderService = context.getBean(OrderService.class);

            assertNotSame("含 @RateLimit 方法的 bean 应被代理包装",
                    OrderServiceImpl.class, orderService.getClass());
            assertEquals("ok-A1-1", orderService.create("A1"));
            assertEquals("键模板按参数名渲染：不同 orderId 独立计数", "ok-A2-2", orderService.create("A2"));
            assertEquals("ok-A1-3", orderService.create("A1"));

            try {
                orderService.create("A1");
                fail("expected RateLimitException");
            } catch (RateLimitException e) {
                assertEquals("spring.order", e.getResult().getPoint());
            }
            assertEquals("拒绝时目标方法不执行", 3, app.orderService.count.get());
        }
    }

    @Test
    public void nullValueRejectReturnsNullInSpring() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            ReportService reportService = context.getBean(ReportService.class);

            assertEquals("report-1", reportService.report());
            assertNull("NULL_VALUE 拒绝返回 null", reportService.report());
            assertEquals(1, app.reportService.count.get());
        }
    }

    /**
     * 注解仅在实现方法（接口未标注）时同样被代理与拦截：与 singleflight 的
     * targetClass 解析修复对称的回归用例（JDK 代理下拦截到的是接口方法）
     */
    @Test
    public void annotationOnlyOnImplementationStillIntercepted() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            LookupService lookup = context.getBean(LookupService.class);

            assertNotSame("注解在实现方法的 bean 也应被代理包装",
                    LookupServiceImpl.class, lookup.getClass());
            assertEquals("l-1", lookup.find("k"));
            try {
                lookup.find("k");
                fail("expected RateLimitException");
            } catch (RateLimitException e) {
                assertEquals("spring.lookup", e.getResult().getPoint());
            }
            assertEquals("拒绝时目标方法不执行", 1, app.lookupService.count.get());
        }
    }

    @Test
    public void finalClassSkippedWithWarnInsteadOfFailure() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestApp.class)) {
            TestApp app = context.getBean(TestApp.class);
            FinalService finalService = context.getBean(FinalService.class);

            assertSame("final 类无法代理：warn 并跳过，返回原 bean",
                    FinalService.class, finalService.getClass());
            assertEquals("raw-1", finalService.call());
            assertEquals("final 类方法不受限流影响", "raw-2", finalService.call());
            assertEquals(2, app.finalService.count.get());
        }
    }

    // ------------------------------------------------- 测试装配

    @Configuration
    @EnableRateLimit
    public static class TestApp {

        final OrderServiceImpl orderService = new OrderServiceImpl();
        final ReportService reportService = new ReportService();
        final FinalService finalService = new FinalService();
        final LookupServiceImpl lookupService = new LookupServiceImpl();

        @Bean
        public OrderService orderService() {
            return orderService;
        }

        @Bean
        public ReportService reportService() {
            return reportService;
        }

        @Bean
        public FinalService finalService() {
            return finalService;
        }

        @Bean
        public LookupService lookupService() {
            return lookupService;
        }
    }

    public interface OrderService {

        @RateLimit(point = "spring.order")
        String create(String orderId);
    }

    public static class OrderServiceImpl implements OrderService {

        final AtomicInteger count = new AtomicInteger();

        @Override
        public String create(String orderId) {
            return "ok-" + orderId + "-" + count.incrementAndGet();
        }
    }

    public static class ReportService {

        final AtomicInteger count = new AtomicInteger();

        @RateLimit(point = "spring.report", reject = RateLimitReject.NULL_VALUE)
        public String report() {
            return "report-" + count.incrementAndGet();
        }
    }

    /**
     * final 类：无法子类代理，后置处理器应 warn 并跳过
     */
    public static final class FinalService {

        final AtomicInteger count = new AtomicInteger();

        @RateLimit(point = "spring.finalsvc")
        public String call() {
            return "raw-" + count.incrementAndGet();
        }
    }

    /**
     * 注解仅在实现方法（接口未标注）：JDK 代理场景的回归靶心
     */
    public interface LookupService {

        String find(String keyword);
    }

    public static class LookupServiceImpl implements LookupService {

        final AtomicInteger count = new AtomicInteger();

        @Override
        @RateLimit(point = "spring.lookup")
        public String find(String keyword) {
            return "l-" + count.incrementAndGet();
        }
    }
}
