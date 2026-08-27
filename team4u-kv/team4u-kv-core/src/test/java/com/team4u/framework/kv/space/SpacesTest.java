package com.team4u.framework.kv.space;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import com.team4u.framework.kv.support.SettableClock;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpacesTest {

    private final SettableClock clock = new SettableClock(0L);
    private final InMemoryKvStore store = new InMemoryKvStore(clock);
    private final Spaces spaces = new Spaces(clock);

    @After
    public void tearDown() {
        spaces.policy("user.session").ifPresent(p -> spaces.unregister("user.session"));
        spaces.policy("user.profile").ifPresent(p -> spaces.unregister("user.profile"));
    }

    @Test
    public void typedSpaceRoundTrip() {
        spaces.register(new SpacePolicy()
                .setName("user.session")
                .setValueType(Session.class)
                .setDefaultTtlMillis(60_000));

        Space<Session> sessions = spaces.use("user.session", store);
        sessions.put("u1", new Session("token-abc"));

        Session session = sessions.get("u1");
        assertEquals("token-abc", session.getToken());

        // 默认 TTL 来自策略
        assertEquals(60_000, store.get(SpaceKey.of("user.session", "u1")).getExpireAt());
    }

    @Test
    public void putIfAbsentForIdempotency() {
        spaces.register(new SpacePolicy().setName("user.session").setValueType(String.class));

        Space<String> idem = spaces.use("user.session", store);
        assertTrue(idem.putIfAbsent("order-1", "1", 60_000));
        assertFalse(idem.putIfAbsent("order-1", "2", 60_000));
        assertEquals("1", idem.get("order-1"));
    }

    @Test
    public void expireAndRemoveThroughFacade() {
        spaces.register(new SpacePolicy().setName("user.profile").setValueType(String.class));

        Space<String> profiles = spaces.use("user.profile", store);
        profiles.put("u1", "jay");
        assertTrue(profiles.expire("u1", 30_000));
        assertTrue(profiles.remove("u1"));
        assertFalse(profiles.remove("u1"));
    }

    @Test
    public void registerOverwritesForHotUpdate() {
        spaces.register(new SpacePolicy().setName("user.session")
                .setValueType(String.class).setDefaultTtlMillis(1000));
        spaces.register(new SpacePolicy().setName("user.session")
                .setValueType(String.class).setDefaultTtlMillis(5000));

        spaces.use("user.session", store).put("u1", "v");
        assertEquals("同名重注册覆盖：新策略生效", 5000,
                store.get(SpaceKey.of("user.session", "u1")).getExpireAt());
    }

    @Test(expected = IllegalArgumentException.class)
    public void useUnregisteredSpaceFailsFast() {
        spaces.use("not.registered", store);
    }

    @Test(expected = IllegalArgumentException.class)
    public void spaceNameWithSeparatorRejected() {
        SpaceKey.of("bad:space", "k");
    }

    public static class Session {
        private String token;

        public Session() {
        }

        public Session(String token) {
            this.token = token;
        }

        public String getToken() {
            return token;
        }
    }
}
