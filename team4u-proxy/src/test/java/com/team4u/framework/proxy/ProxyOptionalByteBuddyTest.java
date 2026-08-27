package com.team4u.framework.proxy;

import com.team4u.framework.proxy.core.ProxyException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ProxyOptionalByteBuddyTest {

    private static final String MISSING_BYTE_BUDDY_MESSAGE =
            "Class proxy requires the optional dependency net.bytebuddy:byte-buddy.\n"
                    + "JDK interface proxies run without ByteBuddy.";

    private Closeable loaderResource;

    @Before
    public void setUp() {
        loaderResource = null;
    }

    @After
    public void tearDown() throws IOException {
        if (loaderResource != null) {
            loaderResource.close();
        }
    }

    @Test
    public void interfaceProxyWorksAndClassProxyFailsCleanlyWithoutByteBuddy() throws Exception {
        URLClassLoader loader = newClassLoaderWithoutByteBuddy();
        loaderResource = loader;

        final String result = runIsolated(loader, "runInterface");
        if (!"interface".equals(result)) {
            throw new AssertionError("Isolated interface proxy failed: " + result);
        }
        loader.close();

        loader = newClassLoaderWithoutByteBuddy();
        loaderResource = loader;
        final String failure = runIsolated(loader, "runClass");
        if (!failure.startsWith(MISSING_BYTE_BUDDY_MESSAGE + "|")) {
            throw new AssertionError("Expected missing ByteBuddy message but was:\n" + failure);
        }
        if (!failure.contains("net.bytebuddy") && !failure.contains("net/bytebuddy")) {
            throw new AssertionError("Expected ByteBuddy missing-dependency cause but was:\n" + failure);
        }
    }

    @Test
    public void childFirstEngineLoaderCanSupplyByteBuddyEngine() throws Exception {
        ClassLoader child = new ChildFirstEngineClassLoader();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);
        try {
            Class<?> serviceClass = child.loadClass(ParentVisibleService.class.getName());
            Object target = serviceClass.getDeclaredConstructor().newInstance();
            Object proxy = ProxyBuilder.forClass(serviceClass)
                    .withDelegate(target)
                    .build();
            Object value = serviceClass.getMethod("serve").invoke(proxy);
            if (!"service".equals(value)) {
                throw new AssertionError("Child-first class proxy did not delegate");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void engineLinkageFailureIsNotReportedAsMissingByteBuddy() throws Exception {
        BrokenEngineClassLoader broken = new BrokenEngineClassLoader();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(broken);
        try {
            Class<?> serviceClass = ParentVisibleService.class;
            Object target = serviceClass.getDeclaredConstructor().newInstance();
            Object proxy = ProxyBuilder.forClass(serviceClass)
                    .withDelegate(target)
                    .build();
            serviceClass.getMethod("serve").invoke(proxy);
            throw new AssertionError("Expected class proxy failure");
        } catch (ProxyException e) {
            if (MISSING_BYTE_BUDDY_MESSAGE.equals(e.getMessage())) {
                throw new AssertionError("Linkage failure was mislabeled as missing ByteBuddy", e);
            }
            if (!(e.getCause() instanceof VerifyError)) {
                throw new AssertionError("Expected preserved VerifyError cause", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void finalClassWithByteBuddyKeepsUnsupportedTargetError() {
        try {
            ProxyBuilder.forClass(FinalService.class)
                    .withDelegate(new FinalService())
                    .build();
            throw new AssertionError("Expected final-class proxy failure");
        } catch (ProxyException e) {
            if (!e.getMessage().startsWith("No suitable proxy engine found for class:")) {
                throw new AssertionError("Unexpected final-class failure", e);
            }
        }
    }

    private String runIsolated(URLClassLoader loader, String methodName) throws Exception {
        Class<?> runner = Class.forName(
                "com.team4u.framework.proxy.ProxyOptionalByteBuddyTest$IsolatedRunner",
                true,
                loader);
        Method run = runner.getMethod(methodName);
        run.setAccessible(true);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            return (String) run.invoke(null);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static URLClassLoader newClassLoaderWithoutByteBuddy() throws IOException {
        List<URL> urls = new ArrayList<>();
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Paths.get(element);
            String fileName = path.getFileName().toString();
            if (!fileName.startsWith("byte-buddy-")) {
                urls.add(path.toUri().toURL());
            }
        }
        if (urls.isEmpty()) {
            throw new IllegalStateException("No non-ByteBuddy classpath entries found");
        }

        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    public static class IsolatedRunner {
        public static String runInterface() throws ClassNotFoundException {
            return runInterface(IsolatedRunner.class.getClassLoader());
        }

        public static String runClass() throws ClassNotFoundException {
            return runClass(IsolatedRunner.class.getClassLoader());
        }

        private static String runInterface(ClassLoader loader) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> builderClass = Class.forName(ProxyBuilder.class.getName(), true, loader);
                Class<?> greetingClass = Class.forName(
                        "com.team4u.framework.proxy.ProxyOptionalByteBuddyTest$IsolatedGreeting",
                        true,
                        loader);
                Object delegate = Class.forName(
                        "com.team4u.framework.proxy.ProxyOptionalByteBuddyTest$IsolatedGreetingDelegate",
                        true,
                        loader).getDeclaredConstructor().newInstance();

                Object proxy = builderClass.getMethod("forClass", Class.class)
                        .invoke(null, greetingClass);
                proxy = builderClass.getMethod("withDelegate", Object.class).invoke(proxy, delegate);
                proxy = builderClass.getMethod("build").invoke(proxy);

                Object value = greetingClass.getMethod("greet", String.class).invoke(proxy, "Team4u");
                if (!"Hello, Team4u!".equals(value)) {
                    return "wrong interface result: " + value;
                }
                return "interface";
            } catch (Throwable e) {
                return "interface proxy failed: " + describe(e);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        private static String runClass(ClassLoader loader) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Class<?> builderClass = Class.forName(ProxyBuilder.class.getName(), true, loader);
                Class<?> serviceClass = Class.forName(
                        "com.team4u.framework.proxy.ProxyOptionalByteBuddyTest$IsolatedService",
                        true,
                        loader);
                Object delegate = serviceClass.getDeclaredConstructor().newInstance();

                Object proxy = builderClass.getMethod("forClass", Class.class)
                        .invoke(null, serviceClass);
                proxy = builderClass.getMethod("withDelegate", Object.class).invoke(proxy, delegate);
                builderClass.getMethod("build").invoke(proxy);
                return "class proxy unexpectedly succeeded";
            } catch (Throwable e) {
                Throwable current = e;
                while (current instanceof java.lang.reflect.InvocationTargetException
                        && current.getCause() != null) {
                    current = current.getCause();
                }
                if (current instanceof ProxyException) {
                    return current.getMessage() + "|" + current.getCause();
                }
                return "class proxy failed unexpectedly: " + describe(current);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        private static String describe(Throwable throwable) {
            StringBuilder text = new StringBuilder(throwable.toString());
            Throwable current = throwable;
            while (current.getCause() != null) {
                current = current.getCause();
                text.append(" <- ").append(current);
            }
            return text.toString();
        }
    }

    public interface IsolatedGreeting {
        String greet(String message);
    }

    public static class IsolatedGreetingDelegate implements IsolatedGreeting {
        @Override
        public String greet(String message) {
            return "Hello, " + message + "!";
        }
    }

    public static class IsolatedService {
        public String serve() {
            return "service";
        }
    }

    private static final class ChildFirstEngineClassLoader extends ClassLoader {
        private Class<?> engineClass;

        private ChildFirstEngineClassLoader() throws IOException {
            super(newClassLoaderWithoutByteBuddy());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.team4u.framework.proxy.engine.ByteBuddyProxyEngine")) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    found = defineChildEngine();
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
            return super.loadClass(name, resolve);
        }

        private synchronized Class<?> defineChildEngine() throws ClassNotFoundException {
            if (engineClass != null) {
                return engineClass;
            }
            byte[] bytes = readResource("com/team4u/framework/proxy/engine/ByteBuddyProxyEngine.class");
            engineClass = defineClass(
                    "com.team4u.framework.proxy.engine.ByteBuddyProxyEngine",
                    bytes, 0, bytes.length);
            resolveClass(engineClass);
            return engineClass;
        }

        private byte[] readResource(String resource) {
            try {
                java.net.URL url = getParent().getResource(resource);
                if (url == null) {
                    ClassLoader system = ClassLoader.getSystemClassLoader();
                    url = loadEngineResourceFromSystem(system, resource);
                }
                if (url == null) {
                    throw new IllegalStateException("Missing test resource: " + resource);
                }
                try (java.io.InputStream input = url.openStream()) {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read test resource: " + resource, e);
            }
        }

        private java.net.URL loadEngineResourceFromSystem(ClassLoader loader, String resource)
                throws IOException {
            while (loader != null) {
                java.net.URL url = loader.getResource(resource);
                if (url != null) {
                    return url;
                }
                loader = loader.getParent();
            }
            return ClassLoader.getSystemResource(resource);
        }
    }

    private static final class BrokenEngineClassLoader extends ClassLoader {
        private BrokenEngineClassLoader() {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.team4u.framework.proxy.engine.ByteBuddyProxyEngine")) {
                throw new VerifyError("Deliberate engine verification failure");
            }
            return super.loadClass(name, resolve);
        }
    }

    public static class ParentVisibleService {
        public String serve() {
            return "service";
        }
    }

    public static final class FinalService {
        public String serve() {
            return "service";
        }
    }
}
