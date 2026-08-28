package com.team4u.framework.config.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ConfigProxyCreatorResolutionTest {

    @Test
    public void explicitCreatorSkipsServiceLoaderWhenProviderIsAlsoRegistered() throws Exception {
        URL testClasses = ConfigProxyCreatorResolutionTest.class
                .getProtectionDomain().getCodeSource().getLocation();
        Path providerRoot = Files.createTempDirectory("config-creator-provider");
        Path services = providerRoot.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.write(services.resolve(ConfigProxyCreator.class.getName()),
                FirstServiceCreator.class.getName().getBytes("UTF-8"));

        URLClassLoader isolated = new URLClassLoader(
                new URL[]{testClasses, providerRoot.toUri().toURL()},
                getClass().getClassLoader());
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        current.setContextClassLoader(isolated);
        try {
            Class<?> testType = isolated.loadClass(ConfigProxyCreatorResolutionTest.class.getName());
            Object result = testType.getMethod("buildWithExplicitCreator").invoke(null);
            Assert.assertEquals("explicit", result);
        } finally {
            current.setContextClassLoader(original);
            isolated.close();
        }
    }

    public static String buildWithExplicitCreator() {
        CountingCreator explicit = new CountingCreator();
        ConfigManager manager = ConfigManager.builder()
                .proxyCreator(explicit)
                .build();
        manager.createProxy("app", Object.class);
        Assert.assertEquals(1, explicit.calls.get());
        Assert.assertEquals(0, FirstServiceCreator.calls.get());
        return "explicit";
    }

    public static void assertServiceLoaderCreatorIsUsed() {
        ConfigManager manager = ConfigManager.builder().build();
        manager.createProxy("app", Object.class);
    }

    private static class CountingCreator implements ConfigProxyCreator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            calls.incrementAndGet();
            try {
                return configType.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static class FirstServiceCreator implements ConfigProxyCreator {
        public static final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            calls.incrementAndGet();
            try {
                return configType.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
