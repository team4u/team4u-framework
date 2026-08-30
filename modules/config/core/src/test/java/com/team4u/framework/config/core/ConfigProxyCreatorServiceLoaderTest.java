package com.team4u.framework.config.core;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConfigProxyCreatorServiceLoaderTest {

    private static final String SERVICE = "META-INF/services/"
            + ConfigProxyCreator.class.getName();

    private final AtomicInteger sharedUseCount = new AtomicInteger();

    private Thread currentThread;
    private ClassLoader originalClassLoader;
    private URLClassLoader isolated;

    @Before
    public void saveContextClassLoader() {
        currentThread = Thread.currentThread();
        originalClassLoader = currentThread.getContextClassLoader();
    }

    @After
    public void restoreContextClassLoader() throws Exception {
        currentThread.setContextClassLoader(originalClassLoader);
        if (isolated != null) {
            isolated.close();
        }
    }

    @Test
    public void zeroProvidersLeaveCreatorAbsent() throws Exception {
        loadInIsolatedContext("zero");
        ConfigManager manager = ConfigManager.builder().build();

        try {
            manager.createProxy("app", ContractConfig.class);
            Assert.fail("createProxy must fail when no provider exists");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains(
                    "Config proxy provider is unavailable"));
        }
        Assert.assertEquals(0, sharedUseCount.get());
    }

    @Test
    public void exactlyOneProviderIsUsed() throws Exception {
        loadInIsolatedContext("one", FirstProvider.class);
        ConfigManager manager = ConfigManager.builder().build();

        Object proxy = manager.createProxy("app", ContractConfig.class);

        Assert.assertNotNull(proxy);
        Assert.assertEquals(1, sharedUseCount.get());
    }

    @Test
    public void twoProvidersFailFastWithBothImplementationNames() throws Exception {
        loadInIsolatedContext("two", FirstProvider.class, SecondProvider.class);

        try {
            ConfigManager.builder().build();
            Assert.fail("multiple providers must fail fast");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage(),
                    e.getMessage().contains(FirstProvider.class.getName()));
            Assert.assertTrue(e.getMessage(),
                    e.getMessage().contains(SecondProvider.class.getName()));
        }
        Assert.assertEquals(0, sharedUseCount.get());
    }

    @Test
    public void providerConstructorConfigurationErrorIsWrappedWithCause() throws Exception {
        loadInIsolatedContext("broken", BrokenProvider.class);

        try {
            ConfigManager.builder().build();
            Assert.fail("provider construction errors must fail fast");
        } catch (IllegalStateException e) {
            Assert.assertEquals("Failed to discover ConfigProxyCreator providers", e.getMessage());
            Assert.assertTrue(e.getCause() instanceof java.util.ServiceConfigurationError);
            Assert.assertTrue(String.valueOf(e.getCause().getMessage()),
                    e.getCause().getMessage().contains(BrokenProvider.class.getName()));
        }
    }

    private void loadInIsolatedContext(String name, Class<?>... providers) throws Exception {
        Path providerRoot = Files.createTempDirectory("config-creator-spi-" + name);
        if (providers.length > 0) {
            Path services = providerRoot.resolve(SERVICE);
            Files.createDirectories(services.getParent());
            List<String> lines = new ArrayList<>();
            for (Class<?> provider : providers) {
                lines.add(provider.getName());
            }
            Files.write(services, lines, StandardCharsets.UTF_8);
        }

        URL testClasses = ConfigProxyCreatorServiceLoaderTest.class
                .getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader childFirst = new URLClassLoader(
                new URL[]{testClasses, providerRoot.toUri().toURL()},
                getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && name.startsWith(getClass().getName())) {
                    loaded = findClass(name);
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        };
        isolated = childFirst;
        currentThread.setContextClassLoader(childFirst);
        childFirst.loadClass(ConfigProxyCreatorServiceLoaderTest.class.getName())
                .getMethod("activateIsolatedFactory", AtomicInteger.class)
                .invoke(null, sharedUseCount);
    }

    public static void activateIsolatedFactory(AtomicInteger sharedUseCount) {
        FactoryHolder.useCount = sharedUseCount;
    }

    public interface IsolatedCreator extends ConfigProxyCreator {
    }

    public static class FirstProvider implements IsolatedCreator {
        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            FactoryHolder.useCount.incrementAndGet();
            try {
                return configType.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static class SecondProvider implements IsolatedCreator {
        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            FactoryHolder.useCount.incrementAndGet();
            try {
                return configType.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static class BrokenProvider implements IsolatedCreator {
        static {
            if (true) {
                throw new IllegalStateException("provider constructor bootstrap failed");
            }
        }

        @Override
        public <T> T create(ConfigProxyContext context, String prefix, Class<T> configType) {
            return null;
        }
    }

    public static class ContractConfig {
    }

    private static final class FactoryHolder {
        private static volatile AtomicInteger useCount;
    }
}
