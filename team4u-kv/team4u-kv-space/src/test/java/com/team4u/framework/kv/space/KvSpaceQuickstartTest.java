package com.team4u.framework.kv.space;

import com.team4u.framework.kv.KvRecord;
import com.team4u.framework.kv.KvStore;
import com.team4u.framework.kv.PutMode;
import com.team4u.framework.kv.SpaceKey;
import com.team4u.framework.kv.memory.InMemoryKvStore;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.team4u.framework.kv.support.SettableClock;
public class KvSpaceQuickstartTest {

    private static final String SPACE_NAME = "quickstart.user";

    private final SettableClock clock = new SettableClock(1_000L);
    private final InMemoryKvStore store = new InMemoryKvStore(clock);
    private final Spaces spaces = new Spaces(clock);

    @After
    public void tearDown() {
        spaces.unregister(SPACE_NAME);
    }

    @Test
    public void typedPojoRoundTripUsesExplicitJsonProvider() {
        spaces.register(new SpacePolicy()
                .setName(SPACE_NAME)
                .setValueType(User.class)
                .setDefaultTtlMillis(60_000L));

        Space<User> users = spaces.use(SPACE_NAME, store);
        User input = new User("u-1", "jay");

        users.put("u-1", input);
        User output = users.get("u-1");

        Assert.assertEquals(input, output);
        Assert.assertEquals(61_000L,
                store.get(SpaceKey.of(SPACE_NAME, "u-1")).getExpireAt());
    }

    @Test
    public void ttlPutIfAbsentAndRemoveFormThePublicSpacePath() {
        spaces.register(new SpacePolicy()
                .setName(SPACE_NAME)
                .setValueType(User.class));

        Space<User> users = spaces.use(SPACE_NAME, store);

        Assert.assertTrue(users.putIfAbsent("u-1",
                new User("u-1", "first"), 30_000L));
        Assert.assertFalse(users.putIfAbsent("u-1",
                new User("u-1", "second"), 30_000L));
        Assert.assertEquals("first", users.get("u-1").getName());

        clock.advance(31_000L);
        Assert.assertNull(users.get("u-1"));

        users.put("u-1", new User("u-1", "returned"));
        Assert.assertTrue(users.expire("u-1", 0L));
        Assert.assertTrue(users.remove("u-1"));
        Assert.assertFalse(users.remove("u-1"));
    }

    private static final class User {
        private String id;
        private String name;

        private User() {
        }

        private User(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof User)) {
                return false;
            }
            User user = (User) other;
            return id.equals(user.id) && name.equals(user.name);
        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + name.hashCode();
            return result;
        }
    }

    private static final class SettableClock extends Clock {

        private long millis;

        private SettableClock(long initialMillis) {
            this.millis = initialMillis;
        }

        private void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
