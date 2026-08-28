package com.team4u.framework.proxy;

import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.ProxyEngine;
import com.team4u.framework.proxy.core.ProxyException;
import com.team4u.framework.proxy.engine.JdkProxyEngine;
import com.team4u.framework.proxy.interceptor.DelegateInterceptor;
import com.team4u.framework.proxy.interceptor.EmptyValueInterceptor;
import com.team4u.framework.proxy.interceptor.HotSwapInterceptor;
import com.team4u.framework.proxy.interceptor.TrackInterceptor;
import com.team4u.framework.proxy.support.Swappable;
import com.team4u.framework.proxy.support.Tracker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 现代 Java 动态代理统一构建器 (Fluent API)
 * <p>
 * 它是外界访问代理组件的唯一门面。负责智能选择底层引擎（JDK 或 ByteBuddy），
 * 并根据用户的配置装配对应的 AOP 拦截器链。
 * </p>
 *
 * @param <T> 代理的目标类型
 * @author jay.wu
 */
public final class ProxyBuilder<T> {

    private final Class<T> primaryType;
    private final Set<Class<?>> additionalInterfaces = new LinkedHashSet<>();
    private final List<MethodInterceptor> interceptors = new ArrayList<>();

    /**
     * 核心行为状态标识
     */
    private Object delegateObject;
    private boolean enableHotswap = false;
    private boolean enableEmptyObject = false;

    private ProxyBuilder(Class<T> targetClass) {
        if (targetClass == null) {
            throw new IllegalArgumentException("Target class cannot be null");
        }
        if (targetClass.isPrimitive()) {
            throw new IllegalArgumentException("Cannot create proxy for primitive type: " + targetClass.getName());
        }
        this.primaryType = targetClass;
    }

    /**
     * 静态工厂入口：指定要代理的主类型（可以是接口，也可以是普通类）
     *
     * @param targetClass 主目标类型
     * @param <T>         泛型类型
     * @return 构建器实例
     */
    public static <T> ProxyBuilder<T> forClass(Class<T> targetClass) {
        return new ProxyBuilder<>(targetClass);
    }

    /**
     * 静态工厂入口：为现有对象创建代理（自动推导类型并设置委托）
     *
     * @param delegate 委托对象
     * @param <T>      泛型类型
     * @return 构建器实例
     */
    public static <T> ProxyBuilder<T> forObject(T delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate object cannot be null");
        }
        // 注意：此处默认推导的是实现类类型，若需指定接口请使用 forClass()
        @SuppressWarnings("unchecked")
        Class<T> type = (Class<T>) delegate.getClass();
        return new ProxyBuilder<>(type).delegate(delegate);
    }

    /**
     * 极简快捷入口：一步到位生成带拦截器的代理对象
     *
     * @param delegate     委托对象
     * @param interceptors 拦截器列表
     * @param <T>          对象类型
     * @return 代理对象实例
     */
    public static <T> T proxy(T delegate, MethodInterceptor... interceptors) {
        ProxyBuilder<T> builder = forObject(delegate);
        if (interceptors != null) {
            for (MethodInterceptor i : interceptors) {
                builder.intercept(i);
            }
        }
        return builder.build();
    }

    /**
     * 添加代理类需要额外实现的接口
     *
     * @param interfaces 接口列表
     * @return 构建器自身
     */
    public ProxyBuilder<T> withInterfaces(Class<?>... interfaces) {
        if (interfaces != null) {
            for (Class<?> intf : interfaces) {
                if (intf != null && intf.isInterface()) {
                    this.additionalInterfaces.add(intf);
                }
            }
        }
        return this;
    }

    /**
     * 设定真实的底层委托目标对象。
     * 必须在 build() 之前调用（除非开启了空对象模式）
     *
     * @param delegate 委托对象
     * @return 构建器自身
     */
    public ProxyBuilder<T> withDelegate(Object delegate) {
        this.delegateObject = delegate;
        return this;
    }

    /**
     * 设定委托对象的简写方法
     *
     * @see #withDelegate(Object)
     */
    public ProxyBuilder<T> delegate(Object delegate) {
        return withDelegate(delegate);
    }

    /**
     * 添加自定义的底层方法拦截器。
     * 执行顺序与添加顺序一致（先进先出）。
     *
     * @param interceptor 拦截器实例
     * @return 构建器自身
     */
    public ProxyBuilder<T> addInterceptor(MethodInterceptor interceptor) {
        if (interceptor != null) {
            this.interceptors.add(interceptor);
        }
        return this;
    }

    /**
     * 添加拦截器的简写方法
     *
     * @see #addInterceptor(MethodInterceptor)
     */
    public ProxyBuilder<T> intercept(MethodInterceptor interceptor) {
        return addInterceptor(interceptor);
    }

    /**
     * 添加追踪器（日志、耗时审计等）
     *
     * @param tracker 追踪器契约实现
     * @return 构建器自身
     */
    public ProxyBuilder<T> withTracker(Tracker tracker) {
        if (tracker != null) {
            this.interceptors.add(new TrackInterceptor(tracker));
        }
        return this;
    }

    /**
     * 开启热交换功能。
     * 开启后，代理对象会自动实现 {@link Swappable} 接口，并允许在运行时无锁替换 delegate 对象。
     *
     * @return 构建器自身
     */
    public ProxyBuilder<T> enableHotswap() {
        this.enableHotswap = true;
        this.additionalInterfaces.add(Swappable.class);
        return this;
    }

    /**
     * 开启空对象（Null Object）防御模式。
     * 开启后，调用任何返回对象的方法都会自动返回安全的空值或嵌套的空代理，彻底消除 NPE。
     * 注意：开启此模式后，withDelegate() 设定的目标将被忽略。
     *
     * @return 构建器自身
     */
    public ProxyBuilder<T> asEmptyObject() {
        this.enableEmptyObject = true;
        return this;
    }

    /**
     * 最终构建代理对象实例
     *
     * @return 代理对象
     * @throws ProxyException 如果引擎不支持或参数配置错误
     */
    public T build() {
        // 1. 根据目标类型智能选择最合适的代理引擎
        ProxyEngine engine = selectEngine(this.primaryType, this.additionalInterfaces);

        // 2. 装配并封口拦截器链 (组装顺序极其重要)
        List<MethodInterceptor> finalInterceptors = assembleInterceptors(engine);

        // 3. 将 Set 转换为数组，供底层引擎消费
        Class<?>[] interfacesArray = this.additionalInterfaces.toArray(new Class<?>[0]);

        // 4. 生成代理类并返回实例
        return engine.createProxy(this.primaryType, interfacesArray, this.delegateObject, finalInterceptors);
    }

    /**
     * 引擎路由策略：
     * 如果主类和附加类全是接口 -> 使用超轻量级 JDK Proxy Engine
     * 如果主类是普通的 Class -> 使用高性能 ByteBuddy Engine
     */
    private ProxyEngine selectEngine(Class<?> primaryType, Set<Class<?>> interfaces) {
        if (primaryType.isInterface() && interfaces.stream().allMatch(Class::isInterface)) {
            return JdkProxyEngine.INSTANCE;
        }
        ProxyEngine engine = loadByteBuddyEngine(primaryType);
        if (engine.supports(primaryType)) {
            return engine;
        }

        throw new ProxyException("No suitable proxy engine found for class: " + primaryType.getName()
                + ". Ensure it is not a final class or unsupported type.");
    }

    private ProxyEngine loadByteBuddyEngine(Class<?> primaryType) {
        Throwable bestMissingCause = null;
        for (ClassLoader loader : candidateLoaders(primaryType)) {
            Class<?> engineClass;
            try {
                engineClass = Class.forName(
                        "com.team4u.framework.proxy.engine.ByteBuddyProxyEngine",
                        true,
                        loader);
            } catch (ClassNotFoundException e) {
                if (bestMissingCause == null) {
                    bestMissingCause = e;
                }
                continue;
            } catch (LinkageError e) {
                if (isMissingByteBuddy(e)) {
                    bestMissingCause = e;
                    continue;
                }
                throw new ProxyException("Cannot load ByteBuddy proxy engine from " + loader, e);
            } catch (SecurityException e) {
                throw new ProxyException("Cannot access candidate loader for ByteBuddy proxy engine", e);
            }

            try {
                Object instance = engineClass.getField("INSTANCE").get(null);
                return (ProxyEngine) instance;
            } catch (IllegalAccessException | NoSuchFieldException e) {
                throw new ProxyException("Cannot access ByteBuddy proxy engine INSTANCE", e);
            } catch (ClassCastException e) {
                throw new ProxyException("ByteBuddy proxy engine does not implement ProxyEngine", e);
            } catch (SecurityException e) {
                throw new ProxyException("Cannot reflect ByteBuddy proxy engine INSTANCE", e);
            } catch (LinkageError e) {
                if (isMissingByteBuddy(e)) {
                    bestMissingCause = e;
                    continue;
                }
                throw new ProxyException("Cannot initialize ByteBuddy proxy engine from " + loader, e);
            }
        }

        throw missingByteBuddy(bestMissingCause);
    }

    private ClassLoader[] candidateLoaders(Class<?> primaryType) {
        ClassLoader contextLoader;
        try {
            contextLoader = Thread.currentThread().getContextClassLoader();
        } catch (SecurityException e) {
            throw new ProxyException("Cannot obtain thread context class loader for ByteBuddy proxy engine", e);
        }

        ClassLoader targetLoader;
        try {
            targetLoader = primaryType.getClassLoader();
        } catch (SecurityException e) {
            throw new ProxyException(
                    "Cannot obtain target type class loader for ByteBuddy proxy engine", e);
        }

        ClassLoader builderLoader;
        try {
            builderLoader = ProxyBuilder.class.getClassLoader();
        } catch (SecurityException e) {
            throw new ProxyException(
                    "Cannot obtain proxy builder class loader for ByteBuddy proxy engine", e);
        }

        ClassLoader[] loaders = {
                contextLoader,
                targetLoader,
                builderLoader
        };
        Set<ClassLoader> distinct = new LinkedHashSet<>();
        for (ClassLoader loader : loaders) {
            if (loader != null) {
                distinct.add(loader);
            }
        }
        return distinct.toArray(new ClassLoader[0]);
    }

    private boolean isMissingByteBuddy(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NoClassDefFoundError
                    && isInByteBuddyPackage(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isInByteBuddyPackage(String className) {
        if (className == null) {
            return false;
        }
        String normalized = className.replace('.', '/');
        int detail = normalized.indexOf(' ');
        if (detail >= 0) {
            normalized = normalized.substring(0, detail);
        }
        return normalized.equals("net/bytebuddy") || normalized.startsWith("net/bytebuddy/");
    }

    private ProxyException missingByteBuddy(Throwable cause) {
        return new ProxyException(
                "Class proxy requires the optional dependency net.bytebuddy:byte-buddy.\n"
                        + "JDK interface proxies run without ByteBuddy.",
                cause);
    }

    /**
     * 智能组装最终的职责链
     */
    private List<MethodInterceptor> assembleInterceptors(ProxyEngine engine) {
        List<MethodInterceptor> chain = new ArrayList<>(this.interceptors);

        // 如果是空对象模式，直接接管整个链条的末端，不再需要委托到底层对象
        if (this.enableEmptyObject) {
            chain.add(new EmptyValueInterceptor(this.primaryType, engine));
            return chain;
        }

        // 校验 delegate 存在性
        if (this.delegateObject == null && !this.enableHotswap) {
            throw new ProxyException(
                    "A delegate object must be provided using withDelegate() unless 'asEmptyObject()' is enabled.");
        }

        // 尾部收口拦截器：处理委托逻辑
        if (this.enableHotswap) {
            // 热切换拦截器包含了委托能力，并且拦截 hotswap 方法
            chain.add(new HotSwapInterceptor(this.delegateObject));
        } else {
            // 基础委托拦截器
            chain.add(new DelegateInterceptor(this.delegateObject));
        }

        return chain;
    }
}
