package com.team4u.framework.config.core;

import com.team4u.framework.config.core.internal.DefaultConfigManager;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class ConfigProxyProviderContractTest {

    @Test
    public void explicitCreatorReceivesManagerAndManagerConverterRegistry() {
        InMemoryConfigSource source = new InMemoryConfigSource("creator-context", 1);
        source.put("app.name", "team4u");
        ConfigSourceRegistry sourceRegistry = new ConfigSourceRegistry();
        sourceRegistry.register(source);
        PropertyConverterRegistry converterRegistry = new PropertyConverterRegistry();

        RecordingCreator creator = new RecordingCreator();
        DefaultConfigManager manager = new DefaultConfigManager(
                sourceRegistry, new ConfigWatcherRegistry(), converterRegistry, creator, 0);

        Object proxy = manager.createProxy("app", AppConfig.class);

        assertNotNull(proxy);
        assertSame(manager, creator.contextRef.get().manager());
        assertSame(converterRegistry, creator.contextRef.get().converterRegistry());
    }

    @Test
    public void explicitCreatorTakesPrecedenceOverServiceLoader() {
        // ServiceLoader registration is deliberately not tested here until Task 9 introduces
        // the proxy artifact's service file. Explicit injection remains deterministic.
        InMemoryConfigSource source = new InMemoryConfigSource("explicit-precedence", 1);
        source.put("app.name", "explicit");

        RecordingCreator creator = new RecordingCreator();
        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .proxyCreator(creator)
                .build();

        assertNotNull(manager.createProxy("app", AppConfig.class));
        assertSame(creator, creator.createdBy.get());
        assertEquals("explicit", creator.contextRef.get().manager()
                .getString("app.name").orElse(null));
    }

    @Test
    public void creatorFailurePropagatesWithoutBinderFallback() {
        InMemoryConfigSource source = new InMemoryConfigSource("creator-failure", 1);
        source.put("app.name", "team4u");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .proxyCreator(new FailingCreator())
                .build();

        try {
            manager.createProxy("app", AppConfig.class);
            throw new AssertionError("Creator failure must propagate");
        } catch (IllegalStateException e) {
            assertEquals("provider boom", e.getMessage());
        }
    }

    @Test
    public void creatorNullResultFailsFastWithTypeAndPrefix() {
        InMemoryConfigSource source = new InMemoryConfigSource("null-creator", 1);
        source.put("app.name", "team4u");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .proxyCreator(new NullCreator())
                .build();

        try {
            manager.createProxy("app", AppConfig.class);
            throw new AssertionError("Null creator result must fail fast");
        } catch (IllegalStateException e) {
            assertEquals("ConfigProxyCreator returned null: prefix=[app], configType=["
                            + AppConfig.class.getName() + "]", e.getMessage());
        }
    }

    private static class NullCreator implements ConfigProxyCreator {
        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            return null;
        }
    }

    private static class RecordingCreator implements ConfigProxyCreator {
        private final AtomicReference<ConfigProxyContext> contextRef = new AtomicReference<>();
        private final AtomicReference<Object> createdBy = new AtomicReference<>(this);

        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            contextRef.set(context);
            try {
                return configType.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class FailingCreator implements ConfigProxyCreator {
        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            throw new IllegalStateException("provider boom");
        }
    }

    public static class AppConfig {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static class RecordingBean {
        private final ConfigProxyContext context;
        private final String prefix;

        private RecordingBean(ConfigProxyContext context, String prefix) {
            this.context = context;
            this.prefix = prefix;
        }

        @Override
        public String toString() {
            return prefix + ":" + context.manager().getString(prefix + ".name").orElse(null);
        }
    }
}
