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
 * 核心功能：
 * <ul>
 *     <li>基于元数据预解析机制，在首次访问时完成注解解析并建立静态缓存</li>
 *     <li>提供基于版本号对齐的实例级结果缓存，显著降低高频访问下的类型转换开销</li>
 *     <li>支持松散匹配、占位符解析、默认值兜底及必填项校验等完整配置处理流程</li>
 * </ul>
 * </p>
 */
public class SnapshotAwareInvocationHandler implements InvocationHandler {

    private final Class<?> interfaceType;
    private final String prefix;
    private final Supplier<ConfigSnapshot> snapshotProvider;
    private final boolean isPinned;
    private final ConfigProxyFactory proxyFactory;
    private final PropertyConverterRegistry converterRegistry;

    /**
     * 全局方法元数据静态缓存
     * 由于接口方法上的注解在运行时是静态不变的，所有代理实例共享此缓存以节省解析开销
     */
    private static final Map<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 实例级配置值缓存
     * 绑定了配置快照的版本号。当快照版本发生变更时，缓存将自动失效并重新执行解析逻辑。
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

        // 执行初始化预热，提前填充元数据缓存
        warmUp();
    }

    // ---------------------------------------------------------------------------
    // 代理调用核心流程
    // ---------------------------------------------------------------------------

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // 拦截 Object 基础方法调用
        if (Object.class.equals(method.getDeclaringClass())) {
            return handleObjectMethods(proxy, method, args);
        }

        // 拦截 SnapshotAware.pin() 锚定请求
        if (isPinMethod(method)) {
            return handlePin(proxy);
        }

        ConfigSnapshot snapshot = snapshotProvider.get();
        long currentVersion = snapshot.getVersion();

        // 尝试从结果缓存中快速获取已转换的值（要求版本号完全对齐）
        CacheNode cached = valueCache.get(method);
        if (cached != null && cached.version == currentVersion) {
            return cached.value;
        }

        // 执行完整的解析与转换逻辑
        MethodMetadata metadata = METADATA_CACHE.computeIfAbsent(
                method, m -> createMetadata(m, converterRegistry));

        Object value = resolveValue(metadata, snapshot);

        // 回写结果缓存，供后续相同版本的调用复用
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    // ---------------------------------------------------------------------------
    // 配置值解析流程
    // ---------------------------------------------------------------------------

    /**
     * 执行多层级的配置值检索与处理逻辑
     * <p>
     * 处理优先级如下：
     * <ul>
     *     <li>配置键定位：执行松散匹配检索原始字符串</li>
     *     <li>嵌套处理：若原始值缺失且返回值为接口，则尝试递归创建下级代理</li>
     *     <li>必填项检查：确保必需的配置项存在</li>
     *     <li>默认值填充：应用 @ConfigDefault 注解定义的兜底值</li>
     *     <li>类型转换：依次尝试自定义转换器或通用转换引擎</li>
     * </ul>
     * </p>
     */
    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot) {
        String key = buildKey(metadata);

        // 获取经过松散匹配处理后的原始配置值
        String rawValue = snapshot.getSmart(key).orElse(null);

        // 处理嵌套对象：若当前层级无直接配置，且方法返回类型是接口，则将其视为下级配置节点进行代理
        if (rawValue == null && metadata.returnType.isInterface()) {
            return proxyFactory.createProxy(snapshotProvider, key, metadata.returnType, isPinned);
        }

        // 必填项缺失校验
        if (rawValue == null && metadata.required && metadata.defaultValue == null) {
            throw new ConfigMissingException("配置项缺失: [" + key + "]");
        }

        // 若原始配置不存在，则使用预解析的默认值（可能来自注解或 Java 类型零值）
        if (rawValue == null) {
            return metadata.defaultValue;
        }

        // 优先应用显式声明的自定义属性转换器
        if (metadata.converter != null) {
            return convertWithCustomConverter(rawValue, metadata);
        }

        // 应用通用类型转换引擎
        return convert(rawValue, metadata);
    }

    /**
     * 调用自定义转换器实现
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
     * 调用通用类型转换器实现
     */
    private Object convert(String rawValue, MethodMetadata metadata) {
        try {
            return Convert.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            return metadata.defaultValue;
        }
    }

    // ---------------------------------------------------------------------------
    // 元数据构建逻辑
    // ---------------------------------------------------------------------------

    /**
     * 拼装完整的配置检索键
     */
    private String buildKey(MethodMetadata metadata) {
        return metadata.absolute ? metadata.baseName : prefix + metadata.baseName;
    }

    /**
     * 构建方法级的解析元数据，该结果将被静态缓存
     */
    private static MethodMetadata createMetadata(Method method,
                                                 PropertyConverterRegistry converterRegistry) {
        String baseName = resolveBaseName(method);
        boolean absolute = isAbsoluteKey(method);
        Class<?> returnType = method.getReturnType();
        boolean required = method.isAnnotationPresent(ConfigRequired.class);

        // 预处理默认值
        Object defaultValue = resolveDefaultValue(method, returnType);
        // 预加载转换器实例
        PropertyConverter<?> converter = ConverterLoader.load(method, converterRegistry);

        return new MethodMetadata(baseName, returnType, defaultValue, required, absolute, converter);
    }

    /**
     * 推断配置项名称
     * <p>
     * 遵循以下规则：
     * <ul>
     *     <li>优先读取 @ConfigKey 注解值</li>
     *     <li>若未注解，则根据 Java Getter 规范（get/is 前缀）自动剥离并推断</li>
     * </ul>
     * </p>
     */
    private static String resolveBaseName(Method method) {
        if (method.isAnnotationPresent(ConfigKey.class)) {
            String keyValue = method.getAnnotation(ConfigKey.class).value();
            return keyValue.startsWith(".") ? keyValue.substring(1) : keyValue;
        }
        return inferNameFromGetter(method.getName());
    }

    /**
     * 检查是否为绝对路径定义
     */
    private static boolean isAbsoluteKey(Method method) {
        if (!method.isAnnotationPresent(ConfigKey.class)) {
            return false;
        }
        return method.getAnnotation(ConfigKey.class).value().startsWith(".");
    }

    /**
     * 标准 Getter 名称解析
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
     * 预处理并转换默认值字符串
     */
    private static Object resolveDefaultValue(Method method, Class<?> returnType) {
        if (method.isAnnotationPresent(ConfigDefault.class)) {
            String annotationValue = method.getAnnotation(ConfigDefault.class).value();
            try {
                return Convert.convert(returnType, annotationValue);
            } catch (Exception ignore) {
            }
        }
        return ClassUtil.getDefaultValue(returnType);
    }

    // ---------------------------------------------------------------------------
    // 辅助逻辑处理
    // ---------------------------------------------------------------------------

    private static boolean isPinMethod(Method method) {
        return "pin".equals(method.getName())
                && (method.getParameterCount() == 0);
    }

    /**
     * 处理锚定请求，实现快照的锁定
     */
    private Object handlePin(Object proxy) {
        if (isPinned) {
            return proxy;
        }
        return proxyFactory.createPinnedProxy(snapshotProvider.get(), prefix, interfaceType);
    }

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

    /**
     * 执行反射扫描预热，消除首次运行时的延迟
     */
    private void warmUp() {
        for (Method method : interfaceType.getMethods()) {
            if (method.getDeclaringClass() != Object.class && !method.isDefault()) {
                METADATA_CACHE.computeIfAbsent(method, m -> createMetadata(m, converterRegistry));
            }
        }
    }

    /**
     * 前缀规范化，确保始终以点号结尾
     */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    // ---------------------------------------------------------------------------
    // 内部组件封装
    // ---------------------------------------------------------------------------

    /**
     * 转换器加载器，负责按需实例化转换器实现类
     */
    private static final class ConverterLoader {

        private ConverterLoader() {
        }

        static PropertyConverter<?> load(Method method, PropertyConverterRegistry registry) {
            if (!method.isAnnotationPresent(ConfigConverter.class)) {
                return null;
            }

            Class<? extends PropertyConverter<?>> converterClass = method.getAnnotation(ConfigConverter.class).value();
            return registry.get(converterClass).orElseGet(() -> instantiateAndRegister(converterClass, registry));
        }

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
     * 方法解析元数据封装
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
     * 结果缓存容器
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