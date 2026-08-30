package com.team4u.framework.config.core;

import com.team4u.framework.config.core.internal.DefaultConfigBinder;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

public class ConfigPureJavaQuickstartTest {

    @Test
    public void scalarValuesWorkWithoutProxyCreator() {
        InMemoryConfigSource source = new InMemoryConfigSource("quickstart", 1);
        source.put("app.name", "team4u");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .build();

        Optional<String> value = manager.getString("app.name");

        Assert.assertTrue(value.isPresent());
        Assert.assertEquals("team4u", value.get());
    }

    @Test
    public void explicitBinderRemainsAvailableForPinnedBeans() {
        InMemoryConfigSource source = new InMemoryConfigSource("quickstart-binder", 1);
        source.put("app.name", "team4u");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .build();

        AppConfig bound = new DefaultConfigBinder()
                .bind(manager.currentSnapshot(), "app", AppConfig.class);

        Assert.assertNotNull(bound);
        Assert.assertEquals("team4u", bound.getName());
    }

    @Test
    public void createProxyFailsFastWhenProviderIsAbsent() {
        InMemoryConfigSource source = new InMemoryConfigSource("quickstart-no-proxy", 1);
        source.put("app.name", "team4u");

        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .build();

        try {
            manager.createProxy("app", AppConfig.class);
            Assert.fail("createProxy should require an explicit or ServiceLoader creator");
        } catch (IllegalStateException e) {
            Assert.assertEquals(
                    "Config proxy provider is unavailable: add com.team4u:team4u-config-proxy "
                            + "or provide a ConfigProxyCreator implementation.",
                    e.getMessage());
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
}
