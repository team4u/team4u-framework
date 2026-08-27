package com.team4u.framework.kv.observed;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStoreException;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObservedStoreTest {

    @Test
    public void maskerAppliedToLoggedValue() {
        List<String> masked = new ArrayList<>();
        ObservedStore store = new ObservedStore(new InMemoryKvStore(),
                new ObservedStore.Config(),
                (key, value) -> {
                    masked.add(value);
                    return "****";
                });

        SpaceKey key = SpaceKey.of("user", "phone");
        store.put(key, KvRecord.of("13800138000"), PutMode.SET);
        store.get(key);

        assertEquals(2, masked.size());
        assertTrue("日志前脱敏，原文不出现在返回值以外", masked.contains("13800138000"));
        assertEquals("13800138000", store.get(key).getValue());
    }

    @Test
    public void longValueExcerptedInLog() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("0123456789");
        }
        ObservedStore store = new ObservedStore(new InMemoryKvStore(),
                new ObservedStore.Config().setMaxValueLogLength(20),
                ObservedStore.ValueMasker.NONE);

        SpaceKey key = SpaceKey.of("user", "big");
        store.put(key, KvRecord.of(sb.toString()), PutMode.SET);
        store.get(key); // debug 级日志默认不输出，仅验证不抛异常与正确性
        assertEquals(100, store.get(key).getValue().length());
    }

    @Test
    public void failurePropagatesAfterErrorLog() {
        ObservedStore store = new ObservedStore(new FailingStore());

        try {
            store.get(SpaceKey.of("user", "u1"));
            fail("exception must propagate");
        } catch (KvStoreException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }

    static class FailingStore extends InMemoryKvStore {

        @Override
        public KvRecord get(SpaceKey key) {
            throw new KvStoreException("boom");
        }
    }
}
