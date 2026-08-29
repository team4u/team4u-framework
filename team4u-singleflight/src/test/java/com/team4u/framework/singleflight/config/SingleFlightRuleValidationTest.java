package com.team4u.framework.singleflight.config;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.core.SingleFlightEngine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightRuleValidationTest {

    private TestConfigContext config;
    private TestKvContext kv;
    private SingleFlightEngine engine;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        engine = new SingleFlightEngine(config.getConfigManager(), kv.store(), kv.clock());
    }

    @After
    public void tearDown() {
        engine.close();
        config.destroy();
        kv.close();
    }

    private void rule(String point, String json) {
        config.put("team4u.singleflight." + point, json);
    }

    @Test
    public void validCacheRule() {
        rule("valid", "{\"id\":\"valid\",\"key\":\"${id}\",\"cacheTtlMillis\":60000}");
        assertEqualsValue("done", execute("valid", "done"));
    }

    @Test
    public void validPureMutexRule() {
        rule("valid", "{\"id\":\"valid\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":100,\"pollIntervalMillis\":5}");
        assertEqualsValue("done", execute("valid", "done"));
    }

    @Test
    public void cacheTtlIsRequiredWhenCacheEnabled() {
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\"}",
                "cacheTtlMillis must be > 0");
    }

    @Test
    public void cacheTtlIsForbiddenWhenCacheDisabled() {
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                        + "\"cacheTtlMillis\":60000}",
                "cacheTtlMillis is not allowed");
    }

    @Test
    public void uncacheableAndFailureTtlsMustBePositive() {
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"cacheTtlMillis\":1,"
                + "\"uncacheableTtlMillis\":0}", "uncacheableTtlMillis must be > 0");
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"cacheTtlMillis\":1,"
                + "\"failureTtlMillis\":0}", "failureTtlMillis must be > 0");
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"cacheTtlMillis\":1,"
                + "\"uncacheableTtlMillis\":-1}", "must be > 0");
    }

    @Test
    public void fallbackIsRequiredForFallbackContention() {
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"contention\":\"FALLBACK\","
                + "\"cacheEnabled\":false}", "FALLBACK requires fallback");
    }

    @Test
    public void primitiveFallbackMustNotBeNull() {
        rule("invalid", "{\"id\":\"invalid\",\"key\":\"${id}\",\"contention\":\"FALLBACK\","
                + "\"cacheEnabled\":false,\"fallback\":null}");
        SingleFlightExecution.SingleFlightLoader<Integer> loader = () -> 1;
        try {
            engine.execute(SingleFlightExecution.of("invalid",
                    Collections.singletonMap("id", "x"), int.class, loader));
            fail("expected SingleFlightConfigException");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Primitive return type"));
        }
    }

    @Test
    public void invalidRuleKeepsPreviouslyLoadedRule() {
        rule("keep", "{\"id\":\"keep\",\"key\":\"${id}\",\"cacheEnabled\":false}");
        assertEqualsValue("first", execute("keep", "first"));

        rule("keep", "{\"id\":\"keep\",\"key\":\"${id}\",\"cacheTtlMillis\":0}");
        assertEqualsValue("second", execute("keep", "second"));
    }

    @Test
    public void validRuleHotReloadKeepsInFlightExecutionSingle() throws Exception {
        rule("reload", "{\"id\":\"reload\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":5000,\"pollIntervalMillis\":5}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> waiterResult = new AtomicReference<>("missing");

        Thread leader = new Thread(() -> {
            try {
                SingleFlightExecution.SingleFlightLoader<String> leaderLoader = () -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "leader-done";
                };
                engine.execute(SingleFlightExecution.of("reload",
                        Collections.singletonMap("id", "same"), String.class, leaderLoader));
            } catch (Exception ignored) {
            }
        });
        leader.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        // hot-reload the rule while the leader is still executing
        rule("reload", "{\"id\":\"reload\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":5000,\"pollIntervalMillis\":5,"
                + "\"failureTtlMillis\":2000}");

        Thread waiter = new Thread(() -> {
            try {
                SingleFlightExecution.SingleFlightLoader<String> waiterLoader =
                        () -> "waiter-loaded";
                waiterResult.set(engine.execute(SingleFlightExecution.of("reload",
                        Collections.singletonMap("id", "same"), String.class, waiterLoader)));
            } catch (Exception e) {
                waiterResult.set("error:" + e.getMessage());
            }
        });
        waiter.start();
        release.countDown();
        leader.join(2000);
        waiter.join(2000);

        assertEquals("leader-done", waiterResult.get());
    }

    @Test
    public void keyDigestWithUnregisteredNameFailsAtLoad() {
        assertInvalidRule("{\"id\":\"invalid\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"keyDigest\":\"nope\"}",
                "key digest not registered");
    }

    @Test
    public void keyDigestHidesSensitiveBusinessKeyInStore() {
        rule("secret", "{\"id\":\"secret\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"keyDigest\":\"sha256\"}");
        SingleFlightExecution.SingleFlightLoader<String> loader = () -> "done";
        assertEqualsValue("done", engine.execute(SingleFlightExecution.of("secret",
                Collections.singletonMap("id", "13800138000"), String.class, loader)));

        // 存储里的最终 key 不含明文手机号，point 保持明文便于排查
        boolean found = kv.store().scan(SingleFlightEngine.CACHE_SPACE).stream()
                .anyMatch(spaceKey -> spaceKey.getKey().startsWith("secret_"));
        assertTrue(found);
        kv.store().scan(SingleFlightEngine.CACHE_SPACE).stream()
                .filter(spaceKey -> spaceKey.getKey().startsWith("secret_"))
                .forEach(spaceKey -> assertFalse(spaceKey.getKey().contains("13800138000")));
    }

    private void assertInvalidRule(String json, String expected) {
        rule("invalid", json);
        try {
            execute("invalid", "done");
            fail("expected SingleFlightConfigException");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    private String execute(String point, String value) {
        SingleFlightExecution.SingleFlightLoader<String> loader = () -> value;
        return engine.execute(SingleFlightExecution.of(point,
                Collections.singletonMap("id", value), String.class, loader));
    }

    private static void assertEqualsValue(Object expected, Object actual) {
        assertTrue("Expected <" + expected + "> but was <" + actual + ">",
                expected.equals(actual));
    }
}
