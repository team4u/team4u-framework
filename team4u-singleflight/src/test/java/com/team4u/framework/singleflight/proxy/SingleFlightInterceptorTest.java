package com.team4u.framework.singleflight.proxy;

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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightInterceptorTest {

    private TestConfigContext config;
    private TestKvContext kv;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        SingleFlights.init(config.getConfigManager(), kv.store(), kv.clock());
        config.put("team4u.singleflight.point", "{\"id\":\"point\",\"key\":\"${id}\","
                + "\"contention\":\"FAIL_FAST\",\"cacheEnabled\":false}");
    }

    @After
    public void tearDown() {
        SingleFlights.destroy();
        config.destroy();
        kv.close();
    }

    @Test
    public void annotationProxyUsesGenericReturnTypeAndNamedArguments() {
        Service target = new ServiceImpl();
        Service proxy = SingleFlightProxyFactory.proxy(target, Service.class);

        List<String> result = proxy.users("u1");
        assertEquals(Arrays.asList("u1"), result);
    }

    @Test
    public void exceptionHandlerReceivesMethodTypeThrowableAndContext() throws Exception {
        AtomicReference<Method> method = new AtomicReference<>();
        AtomicReference<String> genericType = new AtomicReference<>();
        AtomicReference<Throwable> throwable = new AtomicReference<>();
        AtomicReference<Map<String, Object>> arguments = new AtomicReference<>();

        Service proxy = SingleFlightProxyFactory.proxy(new ServiceImpl(), Service.class,
                (m, type, t, args) -> {
                    method.set(m);
                    genericType.set(type.getTypeName());
                    throwable.set(t);
                    arguments.set(args);
                    return Arrays.asList("handled");
                });

        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE,
                SingleFlightKeys.compose("point", "same", 128)),
                KvRecord.of("other", 60000, 0), PutMode.SET);
        assertEquals(Arrays.asList("handled"), proxy.users("same"));

        assertEquals("users", method.get().getName());
        assertTrue("generic type was " + genericType.get(),
                genericType.get().contains("java.util.List"));
        assertTrue("throwable was " + throwable.get(),
                throwable.get() instanceof SingleFlightConflictException);
        assertEquals(Collections.singletonMap("id", "same"), arguments.get());
    }

    @Test
    public void exceptionHandlerMayReturnExplicitNullForObjectMethod() {
        Service proxy = SingleFlightProxyFactory.proxy(new ServiceImpl(), Service.class,
                (m, type, t, args) -> null);
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE,
                SingleFlightKeys.compose("point", "same", 128)),
                KvRecord.of("other", 60000, 0), PutMode.SET);
        assertNull(proxy.load("same"));
    }

    @Test
    public void exceptionHandlerRejectsNullForPrimitiveMethod() {
        blockPoint();
        PrimitiveService proxy = SingleFlightProxyFactory.proxy(new PrimitiveServiceImpl(),
                PrimitiveService.class, (m, type, t, args) -> null);
        try {
            proxy.value("same");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("primitive"));
        }
    }

    @Test
    public void exceptionHandlerRejectsIncompatibleResult() {
        blockPoint();
        Service proxy = SingleFlightProxyFactory.proxy(new ServiceImpl(), Service.class,
                (m, type, t, args) -> 42);
        try {
            proxy.load("same");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("incompatible type"));
        }
    }

    @Test
    public void exceptionHandlerCannotThrowUndeclaredCheckedException() {
        blockPoint();
        Service proxy = SingleFlightProxyFactory.proxy(new ServiceImpl(), Service.class,
                (m, type, t, args) -> {
                    throw new ClassNotFoundException("checked");
                });
        try {
            proxy.load("same");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("checked exception"));
        }
    }

    @Test
    public void exceptionHandlerMayThrowDeclaredCheckedException() throws Exception {
        blockPoint();
        CheckedService proxy = SingleFlightProxyFactory.proxy(new CheckedServiceImpl(),
                CheckedService.class, (m, type, t, args) -> {
                    throw new ClassNotFoundException("declared");
                });
        try {
            proxy.load("same");
            fail("expected checked exception");
        } catch (Exception e) {
            assertTrue(e instanceof ClassNotFoundException);
        }
    }


    private void blockPoint() {
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE,
                SingleFlightKeys.compose("point", "same", 128)),
                KvRecord.of("other", 60000, 0), PutMode.SET);
    }

    public interface Service {
        @SingleFlight("point")
        String load(String id);

        @SingleFlight("point")
        List<String> users(String id);
    }

    public static class ServiceImpl implements Service {
        @Override
        public String load(String id) {
            return id;
        }

        @Override
        public List<String> users(String id) {
            return Arrays.asList(id);
        }
    }

    public interface PrimitiveService {
        @SingleFlight("point")
        int value(String id);
    }

    public static class PrimitiveServiceImpl implements PrimitiveService {
        @Override
        public int value(String id) {
            return id.length();
        }
    }

    public interface CheckedService {
        @SingleFlight("point")
        String load(String id) throws ClassNotFoundException;
    }

    public static class CheckedServiceImpl implements CheckedService {
        @Override
        public String load(String id) throws ClassNotFoundException {
            return id;
        }
    }
}
