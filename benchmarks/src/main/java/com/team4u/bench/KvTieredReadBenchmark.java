package com.team4u.bench;

import com.team4u.framework.base.cache.Cache;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.tiered.TieredStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class KvTieredReadBenchmark {

    private static final class CountingL2 implements KvStore {
        private final AtomicInteger getCalls = new AtomicInteger();
        private KvRecord record;

        @Override
        public KvRecord get(SpaceKey key) {
            getCalls.incrementAndGet();
            return record;
        }

        @Override
        public boolean put(SpaceKey key, KvRecord record, PutMode mode) {
            this.record = record;
            return true;
        }

        @Override
        public boolean remove(SpaceKey key) {
            record = null;
            return true;
        }

        @Override
        public boolean expire(SpaceKey key, long ttlMillis) {
            return true;
        }
    }

    private static final class NeverEvictCache implements Cache<SpaceKey, TieredStore.Entry> {
        private final java.util.concurrent.ConcurrentHashMap<SpaceKey, TieredStore.Entry> map =
                new java.util.concurrent.ConcurrentHashMap<SpaceKey, TieredStore.Entry>();

        @Override
        public TieredStore.Entry get(SpaceKey key) {
            return map.get(key);
        }

        @Override
        public void put(SpaceKey key, TieredStore.Entry value) {
            map.put(key, value);
        }

        @Override
        public void remove(SpaceKey key) {
            map.remove(key);
        }

        @Override
        public void clear() {
            map.clear();
        }

        @Override
        public int size() {
            return map.size();
        }
    }

    private CountingL2 l2;
    private TieredStore store;
    private SpaceKey key;

    @Setup
    public void setUp() {
        l2 = new CountingL2();
        store = new TieredStore(l2, new NeverEvictCache(), new TieredStore.Config(), java.time.Clock.systemUTC());
        key = SpaceKey.of("bench", "stable");
        store.put(key, KvRecord.of("value"), PutMode.SET);

        // write-through warmed L1; the first read must stay on L1
        if (l2.getCalls.get() != 0) {
            throw new IllegalStateException("Setup write unexpectedly read L2");
        }
        if (!"value".equals(store.get(key).getValue()) || l2.getCalls.get() != 0) {
            throw new IllegalStateException("Warmed TieredStore L1 read unexpectedly accessed L2");
        }
    }

    @TearDown
    public void tearDown() {
        store.close();
    }

    @Benchmark
    public KvRecord l1Read() {
        return store.get(key);
    }
}
