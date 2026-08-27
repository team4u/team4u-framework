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

    private static final String ENGINE_NAME =
            "com.team4u.framework.proxy.engine.ByteBuddyProxyEngine";

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
    public void childFirstLoaderDefinesEngineAndByteBuddyForParentDefinedBuilder() throws Exception {
        URLClassLoader parent = newClassLoaderWithoutByteBuddy();
        ChildFirstEngineClassLoader child = new ChildFirstEngineClassLoader(parent);
        loaderResource = new CompositeCloseable(child, parent);

        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(child);
        try {
            Class<?> builderClass = Class.forName(ProxyBuilder.class.getName(), true, parent);
            Class<?> serviceClass = Class.forName(ParentVisibleService.class.getName(), true, parent);
            Class<?> parentEngineContract = Class.forName(
                    "com.team4u.framework.proxy.core.ProxyEngine",
                    true,
                    parent);

            if (builderClass.getClassLoader() != parent
                    || serviceClass.getClassLoader() != parent
                    || parentEngineContract.getClassLoader() != parent) {
                throw new AssertionError("Filtered parent did not define proxy types and test fixture");
            }
            if (parent.getResource("net/bytebuddy/ByteBuddy.class") != null) {
                throw new AssertionError("Filtered parent unexpectedly exposes ByteBuddy");
            }

            Object target = serviceClass.getDeclaredConstructor().newInstance();
            Object proxy = builderClass.getMethod("forClass", Class.class)
                    .invoke(null, serviceClass);
            proxy = builderClass.getMethod("withDelegate", Object.class).invoke(proxy, target);
            proxy = builderClass.getMethod("build").invoke(proxy);

            Object value = serviceClass.getMethod("serve").invoke(proxy);
            if (!"service".equals(value)) {
                throw new AssertionError("Child-first class proxy did not delegate");
            }
            if (proxy.getClass().getClassLoader().getParent() != child) {
                throw new AssertionError(
                        "Expected proxy classes from the engine's child loader but got "
                                + proxy.getClass().getClassLoader());
            }

            Class<?> childEngineClass = Class.forName(ENGINE_NAME, false, child);
            if (childEngineClass.getClassLoader() != child) {
                throw new AssertionError("ByteBuddy engine was not defined by the child loader");
            }
            Object engine = childEngineClass.getField("INSTANCE").get(null);
            if (!parentEngineContract.isInstance(engine)) {
                throw new AssertionError("Child engine is not compatible with parent ProxyEngine");
            }
            Object parentView = parentEngineContract.cast(engine);
            if (parentView != engine) {
                throw new AssertionError("ProxyEngine identity changed across the loader boundary");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void engineClassNotFoundTriesTargetLoader() throws Exception {
        MissingEngineLoader loader = new MissingEngineLoader();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Object proxy = ProxyBuilder.forClass(ParentVisibleService.class)
                    .withDelegate(new ParentVisibleService())
                    .build();
            if (!"service".equals(ParentVisibleService.class.cast(proxy).serve())) {
                throw new AssertionError("Fallback class proxy did not delegate");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void byteBuddyPackageNoClassDefMissingCanTryTargetLoader() throws Exception {
        ClassLoader loader = new NoClassDefEngineLoader("net/bytebuddy/ByteBuddy");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Object proxy = ProxyBuilder.forClass(ParentVisibleService.class)
                    .withDelegate(new ParentVisibleService())
                    .build();
            if (!"service".equals(ParentVisibleService.class.cast(proxy).serve())) {
                throw new AssertionError("Fallback class proxy did not delegate");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void similarByteBuddyPackageNamesAreNotOptionalMissing() throws Exception {
        assertLinkageErrorIsInternal(new NoClassDefEngineLoader("net/bytebuddyevil/Thing"));
        assertLinkageErrorIsInternal(new NoClassDefEngineLoader("net.bytebuddyplugin.Thing"));
    }

    @Test
    public void securityFailureFromCandidateLoaderIsInternalError() throws Exception {
        ClassLoader loader = new SecurityEngineLoader();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            ProxyBuilder.forClass(ParentVisibleService.class)
                    .withDelegate(new ParentVisibleService())
                    .build();
            throw new AssertionError("Expected class proxy failure");
        } catch (ProxyException e) {
            if (!"Cannot access candidate loader for ByteBuddy proxy engine".equals(e.getMessage())
                    || !(e.getCause() instanceof SecurityException)) {
                throw new AssertionError("Unexpected candidate-loader security failure", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    public void duplicateCandidateLoadersAreAttemptedOnce() throws Exception {
        CountingEngineLoader loader = new CountingEngineLoader();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class<?> serviceClass = loader.loadClass(ParentVisibleService.class.getName());
            Object proxy = ProxyBuilder.forClass((Class<?>) serviceClass)
                    .withDelegate(serviceClass.getDeclaredConstructor().newInstance())
                    .build();
            if (!"service".equals(serviceClass.getMethod("serve").invoke(proxy))) {
                throw new AssertionError("Counting-loader class proxy did not delegate");
            }
            if (loader.engineLoadAttempts != 1) {
                throw new AssertionError(
                        "Expected one engine load attempt but got " + loader.engineLoadAttempts);
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
            ProxyBuilder.forClass(ParentVisibleService.class)
                    .withDelegate(new ParentVisibleService())
                    .build();
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

    private void assertLinkageErrorIsInternal(ClassLoader loader) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            ProxyBuilder.forClass(ParentVisibleService.class)
                    .withDelegate(new ParentVisibleService())
                    .build();
            throw new AssertionError("Expected engine linkage failure");
        } catch (ProxyException e) {
            if (MISSING_BYTE_BUDDY_MESSAGE.equals(e.getMessage())) {
                throw new AssertionError("Similar package name was mislabeled as missing ByteBuddy", e);
            }
            if (!(e.getCause() instanceof NoClassDefFoundError)) {
                throw new AssertionError("Expected preserved NoClassDefFoundError cause", e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
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

    private static void addClassPathEntries(List<URL> withoutByteBuddy, List<URL> byteBuddyJars)
            throws IOException {
        for (String element : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path path = Paths.get(element);
            String fileName = path.getFileName().toString();
            if (fileName.startsWith("byte-buddy-")) {
                byteBuddyJars.add(path.toUri().toURL());
            } else {
                withoutByteBuddy.add(path.toUri().toURL());
            }
        }
        if (withoutByteBuddy.isEmpty()) {
            throw new IllegalStateException("No non-ByteBuddy classpath entries found");
        }
        if (byteBuddyJars.isEmpty()) {
            throw new IllegalStateException("No ByteBuddy jar found on test class path");
        }
    }

    private static URLClassLoader newClassLoaderWithoutByteBuddy() throws IOException {
        List<URL> urls = new ArrayList<>();
        List<URL> byteBuddyJars = new ArrayList<>();
        addClassPathEntries(urls, byteBuddyJars);
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    private static URL engineBytecodeLocation() throws Exception {
        URL resource = ProxyOptionalByteBuddyTest.class.getClassLoader()
                .getResource("com/team4u/framework/proxy/ProxyBuilder.class");
        if (resource == null) {
            throw new IllegalStateException("Missing proxy bytecode location");
        }
        Path moduleClasses = Paths.get(resource.toURI()).getParent();
        for (int i = 0; i < 5; i++) {
            if (moduleClasses.endsWith(Paths.get("classes"))) {
                return moduleClasses.toUri().toURL();
            }
            moduleClasses = moduleClasses.getParent();
        }
        throw new IllegalStateException("Cannot locate proxy module classes directory");
    }

    private static URL byteBuddyJar() throws Exception {
        List<URL> withoutByteBuddy = new ArrayList<>();
        List<URL> byteBuddyJars = new ArrayList<>();
        addClassPathEntries(withoutByteBuddy, byteBuddyJars);
        for (URL url : byteBuddyJars) {
            if (Paths.get(url.toURI()).getFileName().toString().startsWith("byte-buddy-")
                    && !Paths.get(url.toURI()).getFileName().toString().startsWith("byte-buddy-agent")) {
                return url;
            }
        }
        throw new IllegalStateException("Cannot identify byte-buddy jar");
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

    private static final class CompositeCloseable implements Closeable {
        private final Closeable first;
        private final Closeable second;

        private CompositeCloseable(Closeable first, Closeable second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void close() throws IOException {
            try {
                first.close();
            } finally {
                second.close();
            }
        }
    }

    private static final class ChildFirstEngineClassLoader extends URLClassLoader {
        private ChildFirstEngineClassLoader(URLClassLoader parent) throws Exception {
            super(new URL[] {engineBytecodeLocation(), byteBuddyJar()}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ENGINE_NAME.equals(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    found = findClass(name);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
            return super.loadClass(name, resolve);
        }
    }

    private static class MissingEngineLoader extends ClassLoader {
        private MissingEngineLoader() {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ENGINE_NAME.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class NoClassDefEngineLoader extends ClassLoader {
        private final String missingClassName;

        private NoClassDefEngineLoader(String missingClassName) {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
            this.missingClassName = missingClassName;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ENGINE_NAME.equals(name)) {
                throw new NoClassDefFoundError(missingClassName);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class SecurityEngineLoader extends ClassLoader {
        private SecurityEngineLoader() {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ENGINE_NAME.equals(name)) {
                throw new SecurityException("Loader use denied");
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class CountingEngineLoader extends ClassLoader {
        private int engineLoadAttempts;

        private CountingEngineLoader() {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (ENGINE_NAME.equals(name)) {
                engineLoadAttempts++;
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class BrokenEngineClassLoader extends ClassLoader {
        private BrokenEngineClassLoader() {
            super(ProxyOptionalByteBuddyTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(ENGINE_NAME)) {
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
