package com.team4u.framework.config.core.proxy;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.spi.InMemoryConfigSource;
import org.junit.Assert;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ConfigProxyServiceLoaderContractTest {

    private static final String SERVICE_RESOURCE =
            "META-INF/services/com.team4u.framework.config.core.ConfigProxyCreator";

    @Test
    public void serviceResourceContainsExactlyTheNoArgAdapter() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(SERVICE_RESOURCE)) {
            Assert.assertNotNull("missing service resource: " + SERVICE_RESOURCE, input);
            Assert.assertEquals(ServiceLoaderConfigProxyCreator.class.getName(),
                    readSingleServiceLine(input));
        }
        Assert.assertNotNull(ServiceLoaderConfigProxyCreator.class.getDeclaredConstructor().newInstance());
    }

    @Test
    public void managerDiscoversTheAdapterWithoutExplicitCreator() {
        ConfigManager manager = configManager("service-loader");

        AppConfig config = manager.createProxy("app", AppConfig.class);

        Assert.assertEquals("team4u", config.getName());
    }

    @Test
    public void adapterUsesManagerAndExactManagerConverterRegistry() {
        InMemoryConfigSource source = source("converter-context");
        source.put("app.marker", "raw");
        ConfigManager manager = ConfigManager.builder()
                .addSource(source)
                .addConverter(new IdentityConverter())
                .build();

        ConverterConfig config = manager.createProxy("app", ConverterConfig.class);

        Assert.assertSame(IdentityConverter.MARKER, config.getMarker());
    }

    @Test
    public void concreteClassProxyWorksFromConfigProxyRuntimeDependency() {
        AppConfig config = configManager("runtime-byte-buddy")
                .createProxy("app", AppConfig.class);

        Assert.assertNotSame(AppConfig.class, config.getClass());
        Assert.assertTrue(config instanceof SnapshotAware);
        Assert.assertEquals("team4u", config.getName());
    }

    @Test
    public void nestedConfigurationBecomesAProxyUsingTheSameContext() {
        InMemoryConfigSource source = source("nested");
        source.put("app.name", "app");
        source.put("app.database.url", "jdbc:test");
        ConfigManager manager = ConfigManager.builder().addSource(source).build();

        RootConfig config = manager.createProxy("app", RootConfig.class);

        Assert.assertEquals("app", config.getName());
        Assert.assertTrue(config.getDatabase() instanceof SnapshotAware);
        Assert.assertEquals("jdbc:test", config.getDatabase().getUrl());
    }

    private static ConfigManager configManager(String name) {
        return ConfigManager.builder().addSource(source(name)).build();
    }

    private static InMemoryConfigSource source(String name) {
        InMemoryConfigSource source = new InMemoryConfigSource(name, 1);
        source.put("app.name", "team4u");
        return source;
    }

    private static String readSingleServiceLine(InputStream input) throws IOException {
        String serviceLine = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String candidate = line.trim();
                if (candidate.isEmpty() || candidate.startsWith("#")) {
                    continue;
                }
                Assert.assertNull("service resource must contain exactly one implementation", serviceLine);
                serviceLine = candidate;
            }
        }
        return serviceLine;
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

    public static class ConverterConfig {
        @ConfigConverter(IdentityConverter.class)
        private Marker marker;

        public Marker getMarker() {
            return marker;
        }

        public void setMarker(Marker marker) {
            this.marker = marker;
        }
    }

    public static final class IdentityConverter implements PropertyConverter<Marker> {
        static final Marker MARKER = new Marker();

        @Override
        public Marker convert(String source, Class<Marker> targetType) {
            return MARKER;
        }
    }

    public static class Marker {
    }

    public static class RootConfig {
        private String name;
        private DatabaseConfig database = new DatabaseConfig();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public DatabaseConfig getDatabase() {
            return database;
        }

        public void setDatabase(DatabaseConfig database) {
            this.database = database;
        }
    }

    public static class DatabaseConfig {
        private String url;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}
