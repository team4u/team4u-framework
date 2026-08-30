package com.team4u.framework.kv;

import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 命名 KV 存储注册表测试：注册/解析/覆盖/注销/清空与未注册异常语义
 *
 * @author jay.wu
 */
public class NamedKvStoreRegistryTest {

    private static Set<String> hashSet(String... items) {
        Set<String> set = new HashSet<>();
        for (String item : items) {
            set.add(item);
        }
        return set;
    }

    @Test
    public void registerAndGet() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        InMemoryKvStore store = new InMemoryKvStore();
        try {
            registry.register("memory", store);
            assertSame(store, registry.get("memory"));
            assertTrue(registry.contains("memory"));
            assertEquals(hashSet("memory"), registry.names());
        } finally {
            registry.clear();
        }
    }

    @Test
    public void getUnregisteredThrowsWithName() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        try {
            registry.get("nope");
            fail("unregistered name must throw");
        } catch (IllegalArgumentException e) {
            assertTrue("异常消息须包含存储名，实际: " + e.getMessage(),
                    e.getMessage().contains("nope"));
        }
    }

    @Test
    public void getOrDefaultReturnsDefaultForUnregistered() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        InMemoryKvStore fallback = new InMemoryKvStore();
        try {
            assertSame(fallback, registry.getOrDefault("nope", fallback));
            InMemoryKvStore registered = new InMemoryKvStore();
            registry.register("yes", registered);
            assertSame(registered, registry.getOrDefault("yes", fallback));
        } finally {
            registry.clear();
        }
    }

    @Test
    public void reRegisterOverwrites() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        InMemoryKvStore first = new InMemoryKvStore();
        InMemoryKvStore second = new InMemoryKvStore();
        try {
            registry.register("dup", first);
            registry.register("dup", second);
            assertSame("同名后注册者覆盖先注册者（热更新）", second, registry.get("dup"));
            assertEquals(1, registry.names().size());
        } finally {
            registry.clear();
        }
    }

    @Test
    public void namesReturnsSortedSnapshot() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        try {
            registry.register("redis", new InMemoryKvStore());
            registry.register("memory", new InMemoryKvStore());
            assertEquals(hashSet("memory", "redis"), registry.names());

            // 快照语义：返回后注册不影响已返回集合
            Set<String> snapshot = registry.names();
            registry.register("jdbc", new InMemoryKvStore());
            assertEquals(2, snapshot.size());
        } finally {
            registry.clear();
        }
    }

    @Test
    public void unregisterAndClear() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        registry.register("a", new InMemoryKvStore());
        registry.register("b", new InMemoryKvStore());

        assertTrue(registry.unregister("a"));
        assertFalse("未注册的名字注销返回 false", registry.unregister("a"));
        assertFalse(registry.contains("a"));

        registry.clear();
        assertTrue("清空后无任何名字", registry.names().isEmpty());
        assertFalse(registry.contains("b"));
    }

    @Test
    public void registerRejectsBlankNameAndNullStore() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        try {
            try {
                registry.register(null, new InMemoryKvStore());
                fail("null name must fail");
            } catch (IllegalArgumentException expected) {
                // 预期
            }
            try {
                registry.register("", new InMemoryKvStore());
                fail("blank name must fail");
            } catch (IllegalArgumentException expected) {
                // 预期
            }
            try {
                registry.register("x", null);
                fail("null store must fail");
            } catch (NullPointerException expected) {
                // 预期
            }
        } finally {
            registry.clear();
        }
    }

    @Test
    public void globalSingletonShared() {
        NamedKvStoreRegistry first = NamedKvStoreRegistry.global();
        NamedKvStoreRegistry second = NamedKvStoreRegistry.global();
        assertSame(first, second);
    }

    @Test
    public void chainedRegistering() {
        NamedKvStoreRegistry registry = new NamedKvStoreRegistry();
        try {
            NamedKvStoreRegistry returned = registry
                    .register("a", new InMemoryKvStore())
                    .register("b", new InMemoryKvStore());
            assertSame("register 返回 this 支持链式调用", registry, returned);
            assertEquals(2, registry.names().size());
        } finally {
            registry.clear();
        }
    }
}
