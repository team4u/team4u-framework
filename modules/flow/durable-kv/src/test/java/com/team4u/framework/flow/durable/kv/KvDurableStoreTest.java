package com.team4u.framework.flow.durable.kv;

import com.team4u.framework.flow.durable.DurableLifecycle;
import com.team4u.framework.flow.durable.snapshot.DurableSnapshot;
import com.team4u.framework.flow.durable.snapshot.StoredValue;
import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.observed.ObservedStore;
import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KvDurableStoreTest {

    private InMemoryKvStore kvStore;
    private KvDurableStore durableStore;

    @Before
    public void setUp() {
        kvStore = new InMemoryKvStore();
        durableStore = new KvDurableStore(kvStore);
    }

    private DurableSnapshot createSnapshot(String execId, long revision) {
        return new DurableSnapshot(
                execId,
                "order-flow",
                1,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision,
                DurableLifecycle.ACTIVE,
                new byte[]{1, 2, 3},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{10, 20})),
                null,
                false
        );
    }

    private DurableSnapshot completedSnapshot(String execId, long revision) {
        return new DurableSnapshot(
                execId,
                "order-flow",
                1,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision,
                DurableLifecycle.COMPLETED,
                new byte[]{1, 2, 3},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{10, 20})),
                null,
                false
        );
    }

    private DurableSnapshot suspendedSnapshot(String execId, long revision) {
        return new DurableSnapshot(
                execId,
                "order-flow",
                1,
                DurableSnapshot.CURRENT_FORMAT_ID,
                DurableSnapshot.CURRENT_FORMAT_VERSION,
                revision,
                DurableLifecycle.SUSPENDED,
                new byte[]{1, 2, 3},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{10, 20})),
                "approval",
                false
        );
    }

    /** 可手动推进的测试时钟。 */
    static final class MutableTestClock extends Clock {
        private volatile Instant current;

        MutableTestClock(long epochMillis) {
            this.current = Instant.ofEpochMilli(epochMillis);
        }

        void advanceMillis(long millis) {
            this.current = current.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    @Test
    public void rejectsInvalidSpaceNames() {
        InMemoryKvStore kv = new InMemoryKvStore();
        // space 含 ':' 分隔符：拒绝（物理键 space:key 拼接歧义）
        try {
            new KvDurableStore(kv, "flow:durable");
            fail("space 含 ':' 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(":"));
        }
        // 空白 space：拒绝
        try {
            new KvDurableStore(kv, "  ");
            fail("空白 space 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // blank 校验
        }
        try {
            new KvDurableStore(kv, "flow durable");
            fail("space 含内部空格必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // whitespace 校验
        }
        // 合法 space 正常创建
        assertNotNull(new KvDurableStore(kv, "flow_durable-1"));
    }

    @Test
    public void scanDueReturnsActiveSnapshotsPastFirstWakeAt() {
        InMemoryKvStore kv = new InMemoryKvStore();
        KvDurableStore store = new KvDurableStore(kv);
        Instant now = Instant.now();

        // 到期的 ACTIVE 快照（firstWakeAt 已过）
        DurableSnapshot due = new DurableSnapshot("due", "f", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                0L, DurableLifecycle.ACTIVE, new byte[]{1},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{1})),
                null, false, now.minusSeconds(10));
        assertTrue(store.compareAndSet("due", -1L, due));

        // 未到期的 ACTIVE 快照（firstWakeAt 在未来）
        DurableSnapshot future = new DurableSnapshot("future", "f", 1,
                DurableSnapshot.CURRENT_FORMAT_ID, DurableSnapshot.CURRENT_FORMAT_VERSION,
                0L, DurableLifecycle.ACTIVE, new byte[]{1},
                Collections.singletonMap("in", new StoredValue("json", 1, new byte[]{1})),
                null, false, now.plusSeconds(3600));
        assertTrue(store.compareAndSet("future", -1L, future));

        // 无 wake 的 ACTIVE 快照与终态快照：均不返回
        assertTrue(store.compareAndSet("plain", -1L, createSnapshot("plain", 0L)));
        assertTrue(store.compareAndSet("done", -1L, completedSnapshot("done", 0L)));

        Optional<List<DurableSnapshot>> dueList = store.scanDue(now, 10);
        assertTrue(dueList.isPresent());
        assertEquals(1, dueList.get().size());
        assertEquals("due", dueList.get().get(0).executionId());

        // limit 截断：limit=1 时仅返回第一条
        assertEquals(1, store.scanDue(now, 1).get().size());
    }

    @Test
    public void scanDueRejectsInvalidLimit() {
        InMemoryKvStore kv = new InMemoryKvStore();
        KvDurableStore store = new KvDurableStore(kv);
        try {
            store.scanDue(Instant.now(), 0);
            fail("limit=0 必须被拒绝");
        } catch (IllegalArgumentException expected) {
            // limit 校验
        }
    }

    @Test
    public void legacyThreeArgConstructorAppliesTtlToTerminalOnly() {
        // 语义修复回归：旧签名 (store, space, ttl) 仅对终态生效，非终态永不过期
        InMemoryKvStore kv = new InMemoryKvStore();
        KvDurableStore store = new KvDurableStore(kv, "legacy", 1000L);
        assertEquals(1000L, store.terminalTtlMillis());
        assertEquals("非终态必须默认永不过期", 0L, store.activeTtlMillis());

        String suspendedId = "legacy-suspended";
        assertTrue(store.compareAndSet(suspendedId, -1L, suspendedSnapshot(suspendedId, 0L)));
        // 非终态快照写入时不携带过期时间戳（永不过期）
        assertEquals(0L, kv.get(SpaceKey.of("legacy", suspendedId)).getExpireAt());
        assertTrue("非终态快照不得被静默过期删除", store.load(suspendedId).isPresent());

        // 终态快照写入时携带 terminalTtl 过期时间戳
        String doneId = "legacy-done";
        assertTrue(store.compareAndSet(doneId, -1L, completedSnapshot(doneId, 0L)));
        assertTrue(kv.get(SpaceKey.of("legacy", doneId)).canExpire());
    }

    @Test
    public void initialCreateAndLoad() {
        String execId = "order-001";
        DurableSnapshot initSnapshot = createSnapshot(execId, 0L);

        // 1. 首次创建 expectedRevision = -1
        boolean created = durableStore.compareAndSet(execId, -1L, initSnapshot);
        assertTrue(created);

        // 2. 加载验证
        Optional<DurableSnapshot> loaded = durableStore.load(execId);
        assertTrue(loaded.isPresent());
        assertEquals(initSnapshot, loaded.get());

        // 3. 重复首次创建返回 false
        boolean duplicate = durableStore.compareAndSet(execId, -1L, initSnapshot);
        assertFalse(duplicate);
    }

    @Test
    public void casUpdateLifecycle() {
        String execId = "order-002";
        DurableSnapshot v0 = createSnapshot(execId, 0L);
        DurableSnapshot v1 = createSnapshot(execId, 1L);
        DurableSnapshot v2 = createSnapshot(execId, 2L);

        // 创建 v0
        assertTrue(durableStore.compareAndSet(execId, -1L, v0));

        // 期望版本不匹配 (期望 1，但实际是 0) -> false
        assertFalse(durableStore.compareAndSet(execId, 1L, v2));

        // 期望版本匹配 (期望 0，更新到 v1) -> true
        assertTrue(durableStore.compareAndSet(execId, 0L, v1));
        assertEquals(1L, durableStore.load(execId).get().revision());

        // 再次以 0 更新 -> false (已被更新为 1)
        assertFalse(durableStore.compareAndSet(execId, 0L, v1));

        // 期望 1 更新到 v2 -> true
        assertTrue(durableStore.compareAndSet(execId, 1L, v2));
        assertEquals(2L, durableStore.load(execId).get().revision());
    }

    @Test
    public void updateNonExistentReturnsFalse() {
        String execId = "non-existent";
        DurableSnapshot v1 = createSnapshot(execId, 1L);
        assertFalse(durableStore.compareAndSet(execId, 0L, v1));
        assertFalse(durableStore.load(execId).isPresent());
    }

    @Test
    public void customSpaceAndLifecycleSplitTtl() {
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        InMemoryKvStore clockKvStore = new InMemoryKvStore(clock);
        KvDurableStore customStore = new KvDurableStore(clockKvStore, "custom_flow",
                5000L, 0L, clock);

        assertEquals("custom_flow", customStore.space());
        assertEquals(5000L, customStore.terminalTtlMillis());
        assertEquals(0L, customStore.activeTtlMillis());
        assertEquals(clock, customStore.clock());

        String activeId = "order-active";
        DurableSnapshot active = createSnapshot(activeId, 0L);
        assertTrue(customStore.compareAndSet(activeId, -1L, active));
        // 非终态（ACTIVE）快照：activeTtl=0 → 永不过期
        KvRecord activeRecord = clockKvStore.get(SpaceKey.of("custom_flow", activeId));
        assertNotNull(activeRecord);
        assertEquals(0L, activeRecord.getExpireAt());

        // 推进到终态（COMPLETED）：写入按 terminalTtl=5000 计算过期时间戳
        DurableSnapshot completed = completedSnapshot(activeId, 1L);
        assertTrue(customStore.compareAndSet(activeId, 0L, completed));
        KvRecord completedRecord = clockKvStore.get(SpaceKey.of("custom_flow", activeId));
        assertNotNull(completedRecord);
        assertEquals(1_000_000L + 5000L, completedRecord.getExpireAt());
    }

    @Test
    public void terminalSnapshotExpiresWhileActiveSnapshotSurvives() throws Exception {
        // 语义修复回归：同一 TTL 下，终态快照到期被清理，挂起等审批的
        // 非终态快照永不过期、仍可 load。
        MutableTestClock clock = new MutableTestClock(1_000_000L);
        InMemoryKvStore kv = new InMemoryKvStore(clock);
        KvDurableStore store = new KvDurableStore(kv, "ttl_split", 1000L, 0L, clock);

        String doneId = "done-exec";
        String suspendedId = "suspended-exec";
        assertTrue(store.compareAndSet(doneId, -1L, completedSnapshot(doneId, 0L)));
        assertTrue(store.compareAndSet(suspendedId, -1L, suspendedSnapshot(suspendedId, 0L)));

        // 时间推进越过 terminalTtl：终态记录过期，非终态记录存活
        clock.advanceMillis(1500L);
        assertFalse("终态快照到期后必须被清理", store.load(doneId).isPresent());
        assertTrue("非终态（SUSPENDED）快照不得被静默过期删除",
                store.load(suspendedId).isPresent());
        assertEquals(DurableLifecycle.SUSPENDED, store.load(suspendedId).get().lifecycle());
    }

    @Test
    public void rejectsNonCasCapableStore() {
        KvStore nonCasStore = new KvStore() {
            @Override
            public KvRecord get(SpaceKey key) { return null; }
            @Override
            public boolean put(SpaceKey key, KvRecord record, PutMode mode) { return false; }
            @Override
            public boolean remove(SpaceKey key) { return false; }
            @Override
            public boolean expire(SpaceKey key, long ttlMillis) { return false; }
        };

        try {
            new KvDurableStore(nonCasStore);
            fail("Should reject non-CasCapable store");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("CasCapable"));
        }
    }

    @Test
    public void supportsDecoratorChain() {
        ObservedStore observed = new ObservedStore(kvStore);
        KvDurableStore store = new KvDurableStore(observed);

        String execId = "order-obs";
        DurableSnapshot snapshot = createSnapshot(execId, 0L);
        assertTrue(store.compareAndSet(execId, -1L, snapshot));
        assertTrue(store.load(execId).isPresent());
    }

    @Test
    public void invalidArgumentsValidation() {
        DurableSnapshot snapshot = createSnapshot("exec-1", 0L);

        // blank executionId
        try {
            durableStore.load("");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // null update
        try {
            durableStore.compareAndSet("exec-1", -1L, null);
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {}

        // executionId mismatch
        try {
            durableStore.compareAndSet("exec-2", -1L, snapshot);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // expectedRevision < -1
        try {
            durableStore.compareAndSet("exec-1", -2L, snapshot);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}

        // revision != expectedRevision + 1
        try {
            durableStore.compareAndSet("exec-1", 0L, snapshot); // snapshot.revision is 0, expected is 0 (should be 1)
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {}
    }
}
