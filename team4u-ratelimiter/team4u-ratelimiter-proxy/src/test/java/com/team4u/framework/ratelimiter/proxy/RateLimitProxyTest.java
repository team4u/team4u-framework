package com.team4u.framework.ratelimiter.proxy;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.ratelimiter.api.RateLimitException;
import com.team4u.framework.ratelimiter.api.RateLimitReason;
import com.team4u.framework.ratelimiter.api.RateLimiters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 限流代理单元测试：JDK 代理（注解在接口/实现）、拒绝策略、参数名模板键渲染
 *
 * @author jay.wu
 */
public class RateLimitProxyTest {

    private TestConfigContext config;
    private TestKvContext kv;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        RateLimiters.init(config.getConfigManager(), kv.store(), kv.clock());
        config.put("team4u.ratelimiter.order.create",
                "[{\"id\":\"perOrder\",\"algorithm\":\"fixed-window\","
                        + "\"windowMillis\":60000,\"threshold\":1,\"key\":\"${orderId}\"}]");
        config.put("team4u.ratelimiter.svc.query",
                "[{\"id\":\"q\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
        config.put("team4u.ratelimiter.svc.amount",
                "[{\"id\":\"a\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
        config.put("team4u.ratelimiter.svc.notify",
                "[{\"id\":\"n\",\"algorithm\":\"fixed-window\",\"windowMillis\":60000,\"threshold\":1}]");
    }

    @After
    public void tearDown() {
        RateLimiters.destroy();
        config.destroy();
        kv.close();
    }

    // ------------------------------------------------- JDK 代理（注解在接口方法）

    public interface OrderApi {

        @RateLimit(point = "order.create")
        String create(String orderId);

        String describe();
    }

    public static class OrderApiImpl implements OrderApi {

        final AtomicInteger count = new AtomicInteger();

        @Override
        public String create(String orderId) {
            return "ok-" + orderId + "-" + count.incrementAndGet();
        }

        @Override
        public String describe() {
            count.incrementAndGet();
            return "orders";
        }
    }

    @Test
    public void jdkProxyRejectsWithException() {
        OrderApiImpl impl = new OrderApiImpl();
        OrderApi api = RateLimitProxyFactory.proxy(impl, OrderApi.class);
        assertTrue("接口代理应为 JDK 代理", Proxy.isProxyClass(api.getClass()));

        assertEquals("ok-A1-1", api.create("A1"));
        assertEquals("参数名模板键渲染：不同 orderId 独立计数", "ok-A2-2", api.create("A2"));

        try {
            api.create("A1");
            fail("expected RateLimitException");
        } catch (RateLimitException e) {
            assertEquals("order.create", e.getResult().getPoint());
            assertEquals("perOrder", e.getResult().getRuleId());
            assertEquals(RateLimitReason.THRESHOLD, e.getResult().getReason());
        }
        assertEquals("拒绝时目标方法不执行", 2, impl.count.get());
    }

    // ------------------------------------------------- JDK 代理（注解在实现方法）

    public interface QueryService {

        String query(String keyword);
    }

    public static class QueryServiceImpl implements QueryService {

        final AtomicInteger count = new AtomicInteger();

        @Override
        @RateLimit(point = "svc.query")
        public String query(String keyword) {
            return "q-" + keyword + "-" + count.incrementAndGet();
        }
    }

    @Test
    public void jdkProxyFindsAnnotationOnImplementation() {
        QueryService service = RateLimitProxyFactory.proxy(new QueryServiceImpl(), QueryService.class);
        assertEquals("q-k-1", service.query("k"));
        try {
            service.query("k");
            fail("expected RateLimitException");
        } catch (RateLimitException ignored) {
        }
    }

    // ------------------------------------------------- 拒绝策略 NULL_VALUE

    public interface NullValueService {

        @RateLimit(point = "svc.query", reject = RateLimitReject.NULL_VALUE)
        String query();

        @RateLimit(point = "svc.amount", reject = RateLimitReject.NULL_VALUE)
        int amount();

        @RateLimit(point = "svc.notify", reject = RateLimitReject.NULL_VALUE)
        void notify(String message);
    }

    public static class NullValueServiceImpl implements NullValueService {

        final AtomicInteger executions = new AtomicInteger();

        @Override
        public String query() {
            executions.incrementAndGet();
            return "never";
        }

        @Override
        public int amount() {
            executions.incrementAndGet();
            return 42;
        }

        @Override
        public void notify(String message) {
            executions.incrementAndGet();
        }
    }

    @Test
    public void nullValueRejectReturnsDefaults() {
        NullValueServiceImpl impl = new NullValueServiceImpl();
        NullValueService service = RateLimitProxyFactory.proxy(impl, NullValueService.class);

        assertEquals("never", service.query());
        assertEquals(42, service.amount());
        service.notify("first");

        assertNull("对象类型返回 null", service.query());
        assertEquals("int 返回默认值 0", 0, service.amount());
        service.notify("second");
        assertEquals("拒绝时目标方法一律不执行（含 void）", 3, impl.executions.get());
    }

    // ------------------------------------------------- value 简写别名

    public interface ShorthandService {

        @RateLimit("svc.query")
        String query(String keyword);

        @RateLimit(value = "svc.amount", point = "svc.amount")
        int amount();

        @RateLimit(value = "svc.query", point = "svc.amount")
        void conflict();
    }

    public static class ShorthandServiceImpl implements ShorthandService {

        @Override
        public String query(String keyword) {
            return "q-" + keyword;
        }

        @Override
        public int amount() {
            return 42;
        }

        @Override
        public void conflict() {
        }
    }

    @Test
    public void valueAliasRoutesToPoint() {
        ShorthandService service = RateLimitProxyFactory.proxy(new ShorthandServiceImpl(), ShorthandService.class);
        assertEquals("q-k", service.query("k"));
        try {
            service.query("k");
            fail("expected RateLimitException");
        } catch (RateLimitException ignored) {
        }
    }

    @Test
    public void consistentValueAndPointIsAccepted() {
        ShorthandService service = RateLimitProxyFactory.proxy(new ShorthandServiceImpl(), ShorthandService.class);
        assertEquals("value 与 point 一致时正常生效", 42, service.amount());
    }

    @Test
    public void inconsistentValueAndPointFails() {
        ShorthandService service = RateLimitProxyFactory.proxy(new ShorthandServiceImpl(), ShorthandService.class);
        try {
            service.conflict();
            fail("expected IllegalStateException");
        } catch (IllegalStateException ignored) {
        }
    }

    // ------------------------------------------------- 具体类代理（ByteBuddy）

    public static class ConcreteService {

        final AtomicInteger count = new AtomicInteger();

        @RateLimit(point = "svc.query")
        public String call() {
            return "c-" + count.incrementAndGet();
        }
    }

    @Test
    public void concreteClassProxyIntercepted() {
        ConcreteService service = RateLimitProxyFactory.proxy(new ConcreteService());
        assertEquals("c-1", service.call());
        try {
            service.call();
            fail("expected RateLimitException");
        } catch (RateLimitException ignored) {
        }
    }

    @Test
    public void unannotatedMethodPassesThrough() {
        OrderApiImpl impl = new OrderApiImpl();
        OrderApi api = RateLimitProxyFactory.proxy(impl, OrderApi.class);
        assertEquals("orders", api.describe());
        assertEquals("无注解方法不受限流影响", "orders", api.describe());
        assertEquals(2, impl.count.get());
    }
}
