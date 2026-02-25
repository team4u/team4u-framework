package com.team4u.framework.config.core.proxy;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.annotation.ConfigDefault;
import com.team4u.framework.config.core.annotation.ConfigKey;
import com.team4u.framework.config.core.annotation.ConfigRequired;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigMissingException;
import com.team4u.framework.config.core.domain.ConfigSnapshot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 快照感知动态代理调用处理器
 * <p>
 * 通过元数据预解析（全局静态缓存）和二级值缓存（版本号对齐），
 * 在高并发下实现低延迟的配置属性读取。
 *
 * <h3>核心设计</h3>
 * <ul>
 * <li>{@link #METADATA_CACHE}：全局静态缓存，所有实例共享方法元数据，仅解析一次注解</li>
 * <li>{@link #valueCache}：实例级二级缓存，按版本号失效，避免重复类型转换</li>
 * </ul>
 */
public class SnapshotAwareInvocationHandler implements InvocationHandler {

    private final Class<?> interfaceType;
    private final String prefix;
    private final Supplier<ConfigSnapshot> snapshotProvider;
    private final boolean isPinned;
    private final ConfigProxyFactory proxyFactory;
    private final PropertyConverterRegistry converterRegistry;

    /**
     * 全局方法元数据缓存：注解是静态不变的，因此所有实例可安全共享
     */
    private static final Map<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 实例级二级值缓存：版本对齐，缓存类型转换后的结果
     */
    private final Map<Method, CacheNode> valueCache = new ConcurrentHashMap<>();

    public SnapshotAwareInvocationHandler(Class<?> interfaceType,
                                          String prefix,
                                          Supplier<ConfigSnapshot> snapshotProvider,
                                          boolean isPinned,
                                          ConfigProxyFactory proxyFactory,
                                          PropertyConverterRegistry converterRegistry) {
        this.interfaceType = interfaceType;
        this.prefix = normalizePrefix(prefix);
        this.snapshotProvider = snapshotProvider;
        this.isPinned = isPinned;
        this.proxyFactory = proxyFactory;
        this.converterRegistry = converterRegistry;

        warmUp();
    }

    // ---------------------------------------------------------------------------
    // invoke 主流程
    // ---------------------------------------------------------------------------

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // 拦截 Object 基础方法（toString / hashCode / equals）
        if (Object.class.equals(method.getDeclaringClass())) {
            return handleObjectMethods(proxy, method, args);
        }

        // 处理 SnapshotAware.pin() 快照锚定
        if (isPinMethod(method)) {
            return handlePin(proxy);
        }

        ConfigSnapshot snapshot = snapshotProvider.get();
        long currentVersion = snapshot.getVersion();

        // 快速路径：版本命中则直接返回缓存值
        CacheNode cached = valueCache.get(method);
        if (cached != null && cached.version == currentVersion) {
            return cached.value;
        }

        // 慢路径：获取元数据并解析配置值
        MethodMetadata metadata = METADATA_CACHE.computeIfAbsent(
                method, m -> createMetadata(m, converterRegistry));

        Object value = resolveValue(metadata, snapshot);

        // 回写二级缓存（允许并发写入，最终一致即可）
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    // ---------------------------------------------------------------------------
    // 配置值解析
    // ---------------------------------------------------------------------------

    /**
     * 解析配置值，依次执行：键定位 → 嵌套代理 → 必填校验 → 默认值 → 类型转换
     */
    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot) {
        String key = buildKey(metadata);

        // 1. 从快照中松散匹配原始字符串值
        String rawValue = snapshot.getSmart(key).orElse(null);

        // 2. 原始值为空且返回类型是接口，委托为嵌套配置代理
        if (rawValue == null && metadata.returnType.isInterface()) {
            return proxyFactory.createProxy(snapshotProvider, key, metadata.returnType, isPinned);
        }

        // 3. 必填校验：无值且无默认值时抛出异常
        if (rawValue == null && metadata.required && metadata.defaultValue == null) {
            throw new ConfigMissingException("配置项缺失: [" + key + "]");
        }

        // 4. 无原始值，返回预计算的默认值（来自 @ConfigDefault 或类型零值）
        if (rawValue == null) {
            return metadata.defaultValue;
        }

        // 5. 优先使用自定义转换器
        if (metadata.converter != null) {
            return convertWithCustomConverter(rawValue, metadata);
        }

        // 6. 回退到 Hutool 通用类型转换
        return convert(rawValue, metadata);
    }

    /**
     * 使用自定义 {@link PropertyConverter} 完成转换，失败时回退默认值
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object convertWithCustomConverter(String rawValue, MethodMetadata metadata) {
        try {
            return ((PropertyConverter) metadata.converter).convert(rawValue, metadata.returnType);
        } catch (Exception e) {
            return metadata.defaultValue;
        }
    }

    /**
     * 使用 {@link Convert} 完成通用类型转换，失败时回退默认值
     */
    private Object convert(String rawValue, MethodMetadata metadata) {
        try {
            return Convert.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            return metadata.defaultValue;
        }
    }

    // ---------------------------------------------------------------------------
    // 元数据构建
    // ---------------------------------------------------------------------------

    /**
     * 构建方法对应的完整配置键
     */
    private String buildKey(MethodMetadata metadata) {
        return metadata.absolute ? metadata.baseName : prefix + metadata.baseName;
    }

    /**
     * 创建方法元数据（全生命周期只执行一次，结果放入全局缓存）
     */
    private static MethodMetadata createMetadata(Method method,
                                                 PropertyConverterRegistry converterRegistry) {
        String baseName = resolveBaseName(method);
        boolean absolute = isAbsoluteKey(method);
        Class<?> returnType = method.getReturnType();
        boolean required = method.isAnnotationPresent(ConfigRequired.class);

        Object defaultValue = resolveDefaultValue(method, returnType);
        PropertyConverter<?> converter = ConverterLoader.load(method, converterRegistry);

        return new MethodMetadata(baseName, returnType, defaultValue, required, absolute, converter);
    }

    /**
     * 解析方法对应的配置键基础名
     * <p>
     * 优先读取 {@link ConfigKey}，否则按 Getter 命名规范自动推断。
     * </p>
     */
    private static String resolveBaseName(Method method) {
        if (method.isAnnotationPresent(ConfigKey.class)) {
            String keyValue = method.getAnnotation(ConfigKey.class).value();
            // 以 "." 开头表示绝对路径，剥去前缀点号
            return keyValue.startsWith(".") ? keyValue.substring(1) : keyValue;
        }
        return inferNameFromGetter(method.getName());
    }

    /**
     * 判断 {@link ConfigKey} 是否声明为绝对路径（以 "." 开头）
     */
    private static boolean isAbsoluteKey(Method method) {
        if (!method.isAnnotationPresent(ConfigKey.class)) {
            return false;
        }
        return method.getAnnotation(ConfigKey.class).value().startsWith(".");
    }

    /**
     * 按照 Java Getter 命名惯例剥离前缀，推断属性名
     */
    private static String inferNameFromGetter(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return StrUtil.lowerFirst(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return StrUtil.lowerFirst(methodName.substring(2));
        }
        return methodName;
    }

    /**
     * 解析 {@link ConfigDefault} 注解中的默认值，并提前完成类型转换
     * <p>
     * 若注解不存在或转换失败，则返回 Java 类型零值（int=0 / boolean=false / Object=null）。
     * </p>
     */
    private static Object resolveDefaultValue(Method method, Class<?> returnType) {
        if (method.isAnnotationPresent(ConfigDefault.class)) {
            String annotationValue = method.getAnnotation(ConfigDefault.class).value();
            try {
                return Convert.convert(returnType, annotationValue);
            } catch (Exception ignore) {
                // 注解值无法转换时，回退到类型零值
            }
        }
        return ClassUtil.getDefaultValue(returnType);
    }

    // ---------------------------------------------------------------------------
    // Object 方法与 Pin 处理
    // ---------------------------------------------------------------------------

    /**
     * 判断是否为 {@link SnapshotAware#pin()} 方法
     */
    private static boolean isPinMethod(Method method) {
        return "pin".equals(method.getName())
                && (method.getParameterCount() == 0);
    }

    /**
     * 处理快照锚定请求，返回固定版本的代理对象
     */
    private Object handlePin(Object proxy) {
        if (isPinned) {
            // 已经是固定快照代理，直接返回自身
            return proxy;
        }
        return proxyFactory.createPinnedProxy(snapshotProvider.get(), prefix, interfaceType);
    }

    /**
     * 处理标准 Object 方法
     */
    private Object handleObjectMethods(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return "ConfigProxy[" + interfaceType.getSimpleName()
                        + "|pinned=" + isPinned
                        + "|prefix=" + prefix + "]";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------------------
    // 预热
    // ---------------------------------------------------------------------------

    /**
     * 构造时预热：遍历接口所有方法，提前填充元数据缓存，消除首次调用延迟
     */
    private void warmUp() {
        for (Method method : interfaceType.getMethods()) {
            if (method.getDeclaringClass() != Object.class && !method.isDefault()) {
                METADATA_CACHE.computeIfAbsent(method, m -> createMetadata(m, converterRegistry));
            }
        }
    }

    // ---------------------------------------------------------------------------
    // 工具方法
    // ---------------------------------------------------------------------------

    /**
     * 规范化前缀：保证非空前缀以 "." 结尾，便于后续直接拼接键名
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    // ---------------------------------------------------------------------------
    // 内部类
    // ---------------------------------------------------------------------------

    /**
     * 负责加载和注册 {@link ConfigConverter} 标注的自定义转换器
     */
    private static final class ConverterLoader {

        private ConverterLoader() {
        }

        /**
         * 从注解中加载转换器实例：优先从注册表取，取不到则反射创建后注册
         */
        static PropertyConverter<?> load(Method method, PropertyConverterRegistry registry) {
            if (!method.isAnnotationPresent(ConfigConverter.class)) {
                return null;
            }

            Class<? extends PropertyConverter<?>> converterClass = method.getAnnotation(ConfigConverter.class).value();

            return registry.get(converterClass).orElseGet(() -> instantiateAndRegister(converterClass, registry));
        }

        /**
         * 反射创建转换器实例并注册到注册表中，供后续调用复用
         */
        private static PropertyConverter<?> instantiateAndRegister(
                Class<? extends PropertyConverter<?>> converterClass,
                PropertyConverterRegistry registry) {
            try {
                PropertyConverter<?> instance = converterClass.getDeclaredConstructor().newInstance();
                registry.register(instance);
                return instance;
            } catch (Exception e) {
                throw new IllegalStateException("无法实例化转换器: " + converterClass.getName(), e);
            }
        }
    }

    /**
     * 方法静态元数据，归档注解解析后的所有静态属性
     */
    private static final class MethodMetadata {
        final String baseName;
        final Class<?> returnType;
        final Object defaultValue;
        final boolean required;
        final boolean absolute;
        final PropertyConverter<?> converter;

        MethodMetadata(String baseName,
                       Class<?> returnType,
                       Object defaultValue,
                       boolean required,
                       boolean absolute,
                       PropertyConverter<?> converter) {
            this.baseName = baseName;
            this.returnType = returnType;
            this.defaultValue = defaultValue;
            this.required = required;
            this.absolute = absolute;
            this.converter = converter;
        }
    }

    /**
     * 二级缓存节点，绑定版本号与转换后的值，实现版本驱动失效
     */
    private static final class CacheNode {
        final long version;
        final Object value;

        CacheNode(long version, Object value) {
            this.version = version;
            this.value = value;
        }
    }
}