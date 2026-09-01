package com.team4u.framework.config.core;

import com.team4u.framework.base.util.MapReader;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;

public class ConfigManagerFacadeTest {

    @Test
    public void testAsReaderFacadeAndLiveUpdates() {
        InMemoryConfigSource source = new InMemoryConfigSource("test-source", 1);
        source.put("app.name", "my-app");
        source.put("spring.redis.host", "127.0.0.1");
        source.put("spring.redis.port", "6379");
        source.put("spring.redis.timeout", "3s");
        source.put("spring.redis.max-attempts", "5");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addWatcher(source)
                .debounceWindow(0)
                .build();

        // 1. Root asReader()
        MapReader rootReader = manager.asReader();
        Assert.assertNotNull(rootReader);
        Assert.assertEquals("my-app", rootReader.getReader("app").getString("name"));

        // 2. Prefix asReader("spring.redis")
        MapReader redisReader = manager.asReader("spring.redis");
        Assert.assertEquals("127.0.0.1", redisReader.getString("host"));
        Assert.assertEquals(Integer.valueOf(6379), redisReader.getInt("port", 3306));
        Assert.assertEquals(Duration.ofSeconds(3), redisReader.getDuration("timeout"));
        Assert.assertEquals(Integer.valueOf(5), redisReader.getInt("maxAttempts", 1, "max-attempts"));
        Assert.assertEquals("default-db", redisReader.getString("db", "default-db"));
        Assert.assertEquals("127.0.0.1", redisReader.requireString("host", "Host is required"));

        // 3. Test dynamic live update (reflects new snapshot)
        source.putAndRefresh("spring.redis.port", "6380");
        source.putAndRefresh("spring.redis.timeout", "10s");

        MapReader reloadedRedisReader = manager.asReader("spring.redis");
        Assert.assertEquals(Integer.valueOf(6380), reloadedRedisReader.getInt("port", 3306));
        Assert.assertEquals(Duration.ofSeconds(10), reloadedRedisReader.getDuration("timeout"));

        // 4. Non-existent prefix returns empty reader with default value fallback
        MapReader missingReader = manager.asReader("spring.kafka");
        Assert.assertNotNull(missingReader);
        Assert.assertTrue(missingReader.isEmpty());
        Assert.assertEquals(Integer.valueOf(9092), missingReader.getInt("port", 9092));
        Assert.assertNull(missingReader.getString("bootstrap-servers"));

        if (manager instanceof com.team4u.framework.config.core.internal.DefaultConfigManager) {
            ((com.team4u.framework.config.core.internal.DefaultConfigManager) manager).destroy();
        }
    }
}
