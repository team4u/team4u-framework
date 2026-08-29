package com.team4u.framework.singleflight.core;

import com.fasterxml.jackson.databind.node.NullNode;
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightConflictException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.api.SingleFlightExecutionException;
import com.team4u.framework.singleflight.api.SingleFlightTimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightEngineTest {

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

    private String finalKey(String point, String id) {
        return SingleFlightKeys.compose(point, id, 128);
    }

    @Test
    public void cacheHitExecutesLoaderOnceAndUsesPointKey() {
        rule("cache", "{\"id\":\"cache\",\"key\":\"${productId}\",\"cacheTtlMillis\":60000}");
        User first = executeUser("cache", "productId", "p1", () -> new User("p1", "first"));
        User second = executeUser("cache", "productId", "p1", () -> new User("p1", "second"));

        assertEquals("first", first.getName());
        assertEquals("first", second.getName());
        assertEquals(1, kv.store().scan(SingleFlightEngine.CACHE_SPACE).size());
        assertTrue(kv.store().get(SpaceKey.of(SingleFlightEngine.CACHE_SPACE,
                finalKey("cache", "p1"))) != null);
    }

    @Test
    public void concurrentWaitExecutesOnceAndAllCallersReceiveResult() throws Exception {
        rule("wait", "{\"id\":\"wait\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":5000,\"pollIntervalMillis\":5}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicBoolean concurrentExecution = new AtomicBoolean(false);
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            Future<String>[] futures = new Future[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return execute("wait", "same", () -> {
                            if (executions.incrementAndGet() > 1) {
                                concurrentExecution.set(true);
                            }
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return "done";
                        });
                    } finally {
                    }
                });
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // No waiter barrier: they must finish by observing the terminal session.
            release.countDown();
            for (Future<String> future : futures) {
                assertEquals("done", future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, executions.get());
            assertTrue(!concurrentExecution.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void successCacheableSessionIsPublishedAndExpiresByUncacheableTtl() {
        rule("cacheable", "{\"id\":\"cacheable\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"uncacheableTtlMillis\":1000}");
        assertEquals("ok", execute("cacheable", "same", () -> "ok"));

        SpaceKey sessionKey = SpaceKey.of(SingleFlightEngine.SESSION_SPACE,
                finalKey("cacheable", "same"));
        SessionEnvelope session = SessionEnvelope.of(kv.store().get(sessionKey).getValue());
        assertTrue(session.hasState(SessionEnvelope.STATE_SUCCESS_CACHEABLE));
        assertEquals("ok", session.result().asText());
        kv.advanceMillis(1000);
        assertNull(kv.store().get(sessionKey));
    }

    @Test
    public void successNotCacheableSessionDoesNotWriteResultCache() {
        rule("uncacheable", "{\"id\":\"uncacheable\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"cacheWhen\":\"code == 'empty'\",\"uncacheableTtlMillis\":1000}");
        Result result = executeResult("uncacheable", "same", () -> new Result("success"));

        assertEquals("success", result.getCode());
        assertEquals(0, kv.store().scan(SingleFlightEngine.CACHE_SPACE).size());
        SessionEnvelope session = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE, finalKey("uncacheable", "same"))).getValue());
        assertTrue(session.hasState(SessionEnvelope.STATE_SUCCESS_NOT_CACHEABLE));
    }

    @Test
    public void failureSessionIsReconstructedAndExpiresByFailureTtl() throws Exception {
        rule("failure", "{\"id\":\"failure\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"failureTtlMillis\":1000,\"waitTimeoutMillis\":5000,\"pollIntervalMillis\":5}");
        AtomicReference<Exception> leaderFailure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);

        Thread leader = new Thread(() -> {
            try {
                execute("failure", "same", () -> { throw new IllegalArgumentException("boom"); });
            } catch (Exception e) {
                leaderFailure.set(e);
                failed.countDown();
            }
        });
        leader.start();
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        leader.join(2000);
        assertTrue(leaderFailure.get() instanceof IllegalArgumentException);

        try {
            execute("failure", "same", () -> "unreachable");
            fail("expected reconstructed failure");
        } catch (SingleFlightExecutionException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("boom"));
        }

        SessionEnvelope session = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE, finalKey("failure", "same"))).getValue());
        assertTrue(session.hasState(SessionEnvelope.STATE_FAILURE));
        kv.advanceMillis(1000);
        assertNull(kv.store().get(SpaceKey.of(SingleFlightEngine.SESSION_SPACE,
                finalKey("failure", "same"))));
    }

    @Test
    public void failFastThrowsLowCostConflict() {
        rule("fast", "{\"id\":\"fast\",\"key\":\"${id}\",\"contention\":\"FAIL_FAST\","
                + "\"cacheEnabled\":false}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("fast", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        try {
            execute("fast", "same", () -> "unreachable");
            fail("expected conflict");
        } catch (SingleFlightConflictException e) {
            assertEquals(0, e.getStackTrace().length);
        }
    }

    @Test
    public void errorFallbackCoversFailFastConflict() {
        rule("ef-fast", "{\"id\":\"ef-fast\",\"key\":\"${id}\",\"contention\":\"FAIL_FAST\","
                + "\"cacheEnabled\":false,\"errorFallback\":\"conflict-safe\"}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("ef-fast", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        assertEquals("conflict-safe", execute("ef-fast", "same", () -> "unreachable"));
    }

    @Test
    public void errorFallbackCoversWaitTimeout() {
        rule("ef-timeout", "{\"id\":\"ef-timeout\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":20,\"pollIntervalMillis\":5,"
                + "\"errorFallback\":\"timeout-safe\"}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("ef-timeout", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        assertEquals("timeout-safe", execute("ef-timeout", "same", () -> "unreachable"));
    }

    @Test
    public void errorFallbackCoversFailureSessionReconstruction() throws Exception {
        rule("ef-failure", "{\"id\":\"ef-failure\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"failureTtlMillis\":1000,\"waitTimeoutMillis\":5000,"
                + "\"pollIntervalMillis\":5,\"errorFallback\":\"failure-safe\"}");

        try {
            execute("ef-failure", "same", () -> {
                throw new IllegalArgumentException("boom");
            });
            fail("leader should fail with original exception");
        } catch (IllegalArgumentException expected) {
            // 本地执行者收到原始异常，不被 errorFallback 覆盖
        }

        // 等待者复用失败会话，但 errorFallback 兑底为返回值
        assertEquals("failure-safe", execute("ef-failure", "same", () -> "unreachable"));
    }

    @Test
    public void errorFallbackExplicitNullAllowedOnlyForObjectType() {
        rule("ef-null", "{\"id\":\"ef-null\",\"key\":\"${id}\",\"contention\":\"FAIL_FAST\","
                + "\"cacheEnabled\":false,\"errorFallback\":null}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("ef-null", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        assertNull(execute("ef-null", "same", () -> "unreachable"));
    }

    @Test
    public void errorFallbackDoesNotCoverConfigError() {
        rule("ef-config", "{\"id\":\"ef-config\",\"key\":\"${missing}\","
                + "\"cacheEnabled\":false,\"onInvalidKey\":\"ERROR\","
                + "\"errorFallback\":\"safe\"}");

        try {
            execute("ef-config", "same", () -> "unreachable");
            fail("config error must not be swallowed by errorFallback");
        } catch (SingleFlightConfigException expected) {
            // 配置错误不兑底：静默会掩盖部署问题
        }
    }

    @Test
    public void fallbackUsesNativeJsonAndGenericType() {
        rule("fallback", "{\"id\":\"fallback\",\"key\":\"${id}\",\"contention\":\"FALLBACK\","
                + "\"cacheEnabled\":false,\"fallback\":[{\"name\":\"a\"}]}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("fallback", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        List<User> result = engine.execute(SingleFlightExecution.of("fallback",
                Collections.singletonMap("id", "same"), new UserList(), () -> {
            throw new IllegalStateException("must not execute");
        }));

        assertEquals(1, result.size());
        assertEquals("a", result.get(0).getName());
    }

    @Test
    public void explicitNullFallbackIsAllowedOnlyForObjectReturnType() {
        rule("null-fallback", "{\"id\":\"null-fallback\",\"key\":\"${id}\","
                + "\"contention\":\"FALLBACK\",\"cacheEnabled\":false,\"fallback\":null}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE,
                finalKey("null-fallback", "same")), KvRecord.of("other", 60000, 0), PutMode.SET);

        assertNull(execute("null-fallback", "same", () -> "unreachable"));
    }

    @Test
    public void nullResultPublishesSessionAndWaitersReceiveNull() throws Exception {
        rule("null-result", "{\"id\":\"null-result\",\"key\":\"${id}\","
                + "\"cacheEnabled\":false,\"waitTimeoutMillis\":5000,"
                + "\"pollIntervalMillis\":5}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> waiterResult = new AtomicReference<>("missing");

        Thread leader = new Thread(() -> {
            try {
                execute("null-result", "same", () -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return null;
                });
            } catch (Exception ignored) {
            }
        });
        leader.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        Thread waiter = new Thread(() -> {
            try {
                SingleFlightExecution.SingleFlightLoader<String> fallbackLoader =
                        () -> "non-null";
                if (engine.execute(SingleFlightExecution.of("null-result",
                        Collections.singletonMap("id", "same"), String.class,
                        fallbackLoader)) == null) {
                    waiterResult.set("null");
                } else {
                    waiterResult.set("non-null");
                }
            } catch (Exception e) {
                waiterResult.set("error:" + e.getMessage());
            }
        });
        waiter.start();
        release.countDown();
        leader.join(2000);
        waiter.join(2000);

        assertEquals("null", waiterResult.get());
        SessionEnvelope session = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE,
                        finalKey("null-result", "same"))).getValue());
        assertTrue(session.isTerminal());
        assertTrue(session.result().isNull());
    }

    @Test
    public void waitTimeoutThrowsLowCostException() {
        rule("timeout", "{\"id\":\"timeout\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":20,\"pollIntervalMillis\":5}");
        kv.store().put(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, finalKey("timeout", "same")),
                KvRecord.of("other", 60000, 0), PutMode.SET);

        try {
            execute("timeout", "same", () -> "unreachable");
            fail("expected timeout");
        } catch (SingleFlightTimeoutException e) {
            assertEquals(0, e.getStackTrace().length);
        }
    }

    @Test
    public void pendingWithoutLockIsTakenOver() {
        rule("crash", "{\"id\":\"crash\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":3000,\"pollIntervalMillis\":5}");
        SpaceKey key = SpaceKey.of(SingleFlightEngine.SESSION_SPACE, finalKey("crash", "same"));
        kv.store().put(key, KvRecord.of(
                SessionEnvelope.pending("owner:dead", 0).toJson(), 60000, 0), PutMode.SET);

        assertEquals("recovered", execute("crash", "same", () -> "recovered"));
    }

    @Test
    public void staleExecutorCannotOverwriteSessionAfterLockTakeover() throws Exception {
        rule("fence", "{\"id\":\"fence\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"waitTimeoutMillis\":5000,\"pollIntervalMillis\":5}");
        String key = finalKey("fence", "same");
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch newEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch releaseNew = new CountDownLatch(1);
        AtomicReference<String> oldResult = new AtomicReference<>();

        Thread oldLeader = new Thread(() -> {
            try {
                oldResult.set(execute("fence", "same", () -> {
                    oldEntered.countDown();
                    releaseOld.await(5, TimeUnit.SECONDS);
                    return "old";
                }));
            } catch (Exception ignored) {
                oldResult.set("old-failed");
            }
        });
        oldLeader.start();
        assertTrue(oldEntered.await(2, TimeUnit.SECONDS));

        // Simulate lease expiry followed by another instance taking the same key.
        kv.store().remove(SpaceKey.of(SingleFlightEngine.LOCK_SPACE, key));
        Thread newLeader = new Thread(() -> {
            try {
                execute("fence", "same", () -> {
                    newEntered.countDown();
                    releaseNew.await(5, TimeUnit.SECONDS);
                    return "new";
                });
            } catch (Exception ignored) {
                // Assertions below inspect the published session.
            }
        });
        newLeader.start();
        assertTrue(newEntered.await(2, TimeUnit.SECONDS));

        SessionEnvelope pending = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key)).getValue());
        String newToken = pending.token();

        releaseOld.countDown();
        oldLeader.join(2000);
        assertEquals("old", oldResult.get());
        SessionEnvelope afterOld = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key)).getValue());
        assertEquals(newToken, afterOld.token());

        releaseNew.countDown();
        newLeader.join(2000);
        SessionEnvelope terminal = SessionEnvelope.of(kv.store().get(
                SpaceKey.of(SingleFlightEngine.SESSION_SPACE, key)).getValue());
        assertEquals(newToken, terminal.token());
        assertEquals("new", terminal.result().asText());
    }

    @Test
    public void cacheWhenUsesReturnValueAttributes() {
        rule("decide", "{\"id\":\"decide\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"cacheWhen\":\"code == 'success'\",\"uncacheableTtlMillis\":1000}");
        executeResult("decide", "cacheable", () -> new Result("success"));
        executeResult("decide", "uncacheable", () -> new Result("empty"));

        assertTrue(kv.store().get(SpaceKey.of(SingleFlightEngine.CACHE_SPACE,
                finalKey("decide", "cacheable"))) != null);
        assertNull(kv.store().get(SpaceKey.of(SingleFlightEngine.CACHE_SPACE,
                finalKey("decide", "uncacheable"))));
    }

    @Test
    public void skipWhenDoesNotCoordinateOrCache() {
        rule("skip", "{\"id\":\"skip\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"skipWhen\":\"id == 'same'\"}");
        SingleFlightExecution.SingleFlightLoader<Boolean> loader = () -> true;
        Boolean result = engine.execute(SingleFlightExecution.of("skip",
                Collections.singletonMap("id", "same"), Boolean.class, loader));

        assertEquals(Boolean.TRUE, result);
        assertEquals(0, kv.store().size());
    }

    @Test
    public void pureMutexSkipsResultCacheButUsesSession() {
        rule("mutex", "{\"id\":\"mutex\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"uncacheableTtlMillis\":1000}");
        assertEquals("value", execute("mutex", "same", () -> "value"));
        assertEquals(0, kv.store().scan(SingleFlightEngine.CACHE_SPACE).size());
        assertTrue(kv.store().get(SpaceKey.of(SingleFlightEngine.SESSION_SPACE,
                finalKey("mutex", "same"))) != null);
    }

    @Test
    public void missingRuleDefaultsToPassThrough() {
        assertEquals("direct", execute("missing", "same", () -> "direct"));
        assertEquals(0, kv.store().size());
    }

    @Test
    public void missingRuleErrorPolicyThrowsConfigurationException() {
        config.put("team4u.singleflight.on_rule_missing", "ERROR");
        try {
            execute("missing", "same", () -> "direct");
            fail("expected configuration exception");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("missing"));
        }
    }

    @Test
    public void invalidKeyErrorPolicyFailsFast() {
        rule("invalid-key", "{\"id\":\"invalid-key\",\"key\":\"${missing}\","
                + "\"cacheEnabled\":false,\"onInvalidKey\":\"ERROR\"}");
        try {
            execute("invalid-key", "same", () -> "unreachable");
            fail("expected configuration exception");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("key"));
        }
    }

    @Test
    public void invalidKeyPassThroughExecutesLoader() {
        rule("invalid-key", "{\"id\":\"invalid-key\",\"key\":\"${missing}\","
                + "\"cacheEnabled\":false,\"onInvalidKey\":\"PASS_THROUGH\"}");
        assertEquals("direct", execute("invalid-key", "same", () -> "direct"));
        assertEquals(0, kv.store().size());
    }

    @Test
    public void criterionVariableIsValidatedWhenParameterNamesAreKnown() {
        rule("criterion", "{\"id\":\"criterion\",\"key\":\"${missing}\",\"cacheEnabled\":false,"
                + "\"skipWhen\":\"$unknown == 'x'\"}");
        try {
            SingleFlightExecution.SingleFlightLoader<String> loader = () -> "unreachable";
            engine.execute(SingleFlightExecution.of("criterion",
                    Collections.singletonMap("id", "same"),
                    Collections.singleton("id"), String.class, loader));
            fail("expected configuration exception");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("$unknown"));
        }
    }

    @Test
    public void ruleIdMustMatchPoint() {
        rule("point", "{\"id\":\"different\",\"key\":\"${id}\",\"cacheEnabled\":false}");
        try {
            execute("point", "same", () -> "unreachable");
            fail("expected configuration exception");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("id"));
        }
    }

    private String execute(String point, String id,
                           SingleFlightExecution.SingleFlightLoader<String> loader) {
        return engine.execute(SingleFlightExecution.of(point,
                Collections.singletonMap("id", id), String.class, loader));
    }

    private User executeUser(String point, String name, String id,
                             SingleFlightExecution.SingleFlightLoader<User> loader) {
        return engine.execute(SingleFlightExecution.of(point,
                Collections.singletonMap(name, id), User.class, loader));
    }

    private Result executeResult(String point, String id,
                                 SingleFlightExecution.SingleFlightLoader<Result> loader) {
        return engine.execute(SingleFlightExecution.of(point,
                Collections.singletonMap("id", id), Result.class, loader));
    }

    private static final class LoaderType<T> extends TypeReference<T> {
    }

    public static class UserList extends TypeReference<List<User>> {
    }

    public static class User {
        private String id;
        private String name;

        public User() {
        }

        public User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class Result {
        private String code;

        public Result() {
        }

        public Result(String code) {
            this.code = code;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
    }
}
