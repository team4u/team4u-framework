package com.team4u.framework.singleflight.core;

import com.team4u.framework.config.test.TestConfigContext;
import com.team4u.framework.kv.CasCapable;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.observed.ObservedStore;
import com.team4u.framework.kv.test.TestKvContext;
import com.team4u.framework.singleflight.api.SingleFlightConfigException;
import com.team4u.framework.singleflight.api.SingleFlightExecution;
import com.team4u.framework.singleflight.store.SingleFlightStores;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SingleFlightStoreTest {

    private TestConfigContext config;
    private TestKvContext kv;
    private SingleFlightEngine engine;
    private String storeName;

    @Before
    public void setUp() {
        config = TestConfigContext.create();
        kv = TestKvContext.create();
        engine = new SingleFlightEngine(config.getConfigManager(), kv.store(), kv.clock());
        storeName = "singleflight-store-test-" + System.nanoTime();
    }

    @After
    public void tearDown() {
        engine.close();
        config.destroy();
        kv.close();
    }

    private void rule(String json) {
        config.put("team4u.singleflight.point", json);
    }

    @Test
    public void storeHotSwitchIsRejectedAndOldRuleKept() {
        InMemoryKvStore first = new InMemoryKvStore();
        InMemoryKvStore second = new InMemoryKvStore();
        SingleFlightStores.global().register(storeName + "-a", first);
        SingleFlightStores.global().register(storeName + "-b", second);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheEnabled\":false,\"pollIntervalMillis\":5,"
                + "\"store\":\"" + storeName + "-a\"}");
        assertEquals("first", execute("first"));

        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheEnabled\":false,\"pollIntervalMillis\":5,"
                + "\"store\":\"" + storeName + "-b\"}");
        assertEquals("second", execute("second"));
        assertEquals(0, second.scan(SingleFlightEngine.LOCK_SPACE).size());
    }

    @Test
    public void namedStoreBypassesDecoratorsForCoordination() {
        DecoratedStore decorated = new DecoratedStore(kv.store());
        SingleFlightStores.global().register(storeName, decorated);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheEnabled\":false,\"pollIntervalMillis\":5,"
                + "\"store\":\"" + storeName + "\"}");
        assertEquals("done", execute("done"));
        assertEquals(0, decorated.getGetCount());
    }

    @Test
    public void storeFailurePassThroughExecutesLoader() {
        FailingStore failing = new FailingStore();
        SingleFlightStores.global().register(storeName, failing);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"store\":\"" + storeName + "\",\"onStoreFailure\":\"PASS_THROUGH\","
                + "\"waitTimeoutMillis\":20,\"pollIntervalMillis\":5}");
        failing.failNext();
        SingleFlightExecution.SingleFlightLoader<String> loader =
                () -> "fallback-loaded";
        assertEquals("fallback-loaded", engine.execute(SingleFlightExecution.of("point",
                Collections.singletonMap("id", "same"), String.class, loader)));
    }

    @Test
    public void storeFailureFailClosedStopsLoader() {
        FailingStore failing = new FailingStore();
        SingleFlightStores.global().register(storeName, failing);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheEnabled\":false,"
                + "\"store\":\"" + storeName + "\",\"onStoreFailure\":\"FAIL_CLOSED\","
                + "\"waitTimeoutMillis\":20,\"pollIntervalMillis\":5}");
        SingleFlightExecution.SingleFlightLoader<String> loader =
                () -> "must-not-run";
        failing.failNext();
        try {
            engine.execute(SingleFlightExecution.of("point",
                    Collections.singletonMap("id", "same"), String.class, loader));
            fail("expected SingleFlightConfigException");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("store failure"));
        }
    }

    @Test
    public void failFastDefaultsToFailClosedOnStoreFailure() {
        FailingStore failing = new FailingStore();
        SingleFlightStores.global().register(storeName, failing);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"contention\":\"FAIL_FAST\","
                + "\"cacheEnabled\":false,\"store\":\"" + storeName + "\","
                + "\"waitTimeoutMillis\":20,\"pollIntervalMillis\":5}");
        failing.failNext();
        SingleFlightExecution.SingleFlightLoader<String> loader =
                () -> "must-not-run";
        try {
            engine.execute(SingleFlightExecution.of("point",
                    Collections.singletonMap("id", "same"), String.class, loader));
            fail("expected SingleFlightConfigException");
        } catch (SingleFlightConfigException e) {
            assertTrue(e.getMessage().contains("store failure"));
        }
    }

    @Test
    public void cacheWriteFailureDoesNotReExecuteLoaderInPassThrough() {
        FailingStore failing = new FailingStore();
        SingleFlightStores.global().register(storeName, failing);
        rule("{\"id\":\"point\",\"key\":\"${id}\",\"cacheTtlMillis\":60000,"
                + "\"store\":\"" + storeName + "\"}");
        SingleFlightExecution.SingleFlightLoader<String> loader = () -> "loaded-once";
        assertEquals("loaded-once", engine.execute(SingleFlightExecution.of("point",
                Collections.singletonMap("id", "same"), String.class, loader)));
    }

    private String execute(String value) {
        SingleFlightExecution.SingleFlightLoader<String> loader = () -> value;
        return engine.execute(SingleFlightExecution.of("point",
                Collections.singletonMap("id", "same"), String.class, loader));
    }

    private static final class DecoratedStore extends ObservedStore {

        private final AtomicInteger getCount = new AtomicInteger();

        private DecoratedStore(KvStore inner) {
            super(inner);
        }

        @Override
        public KvStore unwrap() {
            return super.unwrap();
        }

        @Override
        public KvRecord get(SpaceKey key) {
            getCount.incrementAndGet();
            return super.get(key);
        }

        int getGetCount() {
            return getCount.get();
        }
    }

    private static final class FailingStore extends InMemoryKvStore {

        private final AtomicBoolean failNext = new AtomicBoolean();

        void failNext() {
            failNext.set(true);
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            if (failNext.compareAndSet(true, false)) {
                throw new KvStoreException("injected put failure");
            }
            return super.put(key, record, mode);
        }
    }
}
