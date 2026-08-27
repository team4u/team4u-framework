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
        if (!MISSING_BYTE_BUDDY_MESSAGE.equals(failure)) {
            throw new AssertionError("Expected missing ByteBuddy message but was:\n" + failure);
        }
    }

    private String runIsolated(URLClassLoader loader, String methodName) throws Exception {
        Class<?> runner = Class.forName(
                "com.team4u.framework.proxy.ProxyOptionalByteBuddyTest$IsolatedRunner",
                true,
                loader);
        Method run = runner.getMethod(methodName);
        run.setAccessible(true);
        return (String) run.invoke(null);
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
                    return current.getMessage();
                }
                return "class proxy failed unexpectedly: " + describe(current);
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
}
