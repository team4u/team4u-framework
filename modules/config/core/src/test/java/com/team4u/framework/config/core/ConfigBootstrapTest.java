package com.team4u.framework.config.core;

import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.spi.ConfigSourceRegistry;
import com.team4u.framework.config.core.spi.ConfigWatcherRegistry;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConfigBootstrapTest {

    @Before
    @After
    public void resetBootstrap() {
        ConfigBootstrap.global().resetForTests();
    }

    @Test
    public void testResetForTestsClearsGlobalStateAndUnlocks() {
        InMemoryConfigSource source = new InMemoryConfigSource("bootstrap-test", 1);
        ConfigBootstrap.global()
                .addSource(source)
                .addWatcher(source)
                .addConverter(new TestConverter())
                .lock();

        Assert.assertEquals(1, ConfigSourceRegistry.global().getPolicies().size());
        Assert.assertEquals(1, ConfigWatcherRegistry.global().getPolicies().size());

        ConfigBootstrap.global().resetForTests();

        Assert.assertTrue(ConfigSourceRegistry.global().getPolicies().isEmpty());
        Assert.assertTrue(ConfigWatcherRegistry.global().getPolicies().isEmpty());
        Assert.assertTrue(com.team4u.framework.config.core.convert.PropertyConverterRegistry.global()
                .getPolicies().isEmpty());

        ConfigBootstrap.global().addSource(source);
    }

    private static class TestConverter implements PropertyConverter<String> {
        @Override
        public String convert(String source, Class<String> targetType) {
            return source;
        }
    }
}
