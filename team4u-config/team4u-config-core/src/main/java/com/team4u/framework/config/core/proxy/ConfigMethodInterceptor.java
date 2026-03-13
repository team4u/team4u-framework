package com.team4u.framework.config.core.proxy;

import com.team4u.framework.base.util.ConvertUtil;
import com.team4u.framework.base.util.StringUtil;
import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.annotation.ConfigKey;
import com.team4u.framework.config.core.annotation.ConfigRequired;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigMissingException;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 配置方法拦截器
 * <p>
 * 实现配置项到方法调用的自动映射。
 * 支持 Java Bean 增强模式，Bean 字段初始值作为配置缺失时的兜底默认值。
 * </p>
 *
 * @author jay.wu
 */
public class ConfigMethodInterceptor implements MethodInterceptor {

    /**
     * 全局方法元数据静态缓存，用于存储方法解析后的元数据信息
     */
    private static final Map<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 目标配置类的类型
     */
    private final Class<?> targetType;

    /**
     * 配置键前缀
     */
    private final String prefix;

    /**
     * 配置快照提供者，用于支持配置的动态热更新
     */
    private final Supplier<ConfigSnapshot> snapshotProvider;

    /**
     * 是否为快照锚定模式
     */
    private final boolean isPinned;

    /**
     * 配置代理工厂，用于创建嵌套配置的代理对象
     */
    private final ConfigProxyFactory proxyFactory;

    /**
     * 属性转换器注册表
     */
    private final PropertyConverterRegistry converterRegistry;

    /**
     * 实例级配置值缓存，绑定快照版本号，版本变更时缓存失效
     */
    private final Map<Method, CacheNode> valueCache = new ConcurrentHashMap<>();

    public ConfigMethodInterceptor(Class<?> targetType,
                                   String prefix,
                                   Supplier<ConfigSnapshot> snapshotProvider,
                                   boolean isPinned,
                                   ConfigProxyFactory proxyFactory,
                                   PropertyConverterRegistry converterRegistry) {
        this.targetType = targetType;
        this.prefix = normalizePrefix(prefix);
        this.snapshotProvider = snapshotProvider;
        this.isPinned = isPinned;
        this.proxyFactory = proxyFactory;
        this.converterRegistry = converterRegistry;
    }

    /**
     * 创建方法相关的元数据信息
     */
    private static MethodMetadata createMetadata(Method method, PropertyConverterRegistry converterRegistry) {
        Class<?> returnType = method.getReturnType();
        Field field = findField(method);

        String baseName = resolveBaseName(method, field);
        boolean absolute = isAbsoluteKey(method, field);
        boolean required = isAnnotationPresent(method, field, ConfigRequired.class);
        PropertyConverter<?> converter = loadConverter(method, field, converterRegistry);

        return new MethodMetadata(baseName, returnType, required, absolute, converter);
    }

    /**
     * 查找方法对应的类字段
     */
    private static Field findField(Method method) {
        String propertyName = inferNameFromGetter(method.getName());
        try {
            return method.getDeclaringClass().getDeclaredField(propertyName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * 检查方法或对应字段上是否存在指定注解
     */
    private static boolean isAnnotationPresent(Method method, Field field,
                                               Class<? extends Annotation> annotationClass) {
        return method.isAnnotationPresent(annotationClass)
                || (field != null && field.isAnnotationPresent(annotationClass));
    }

    /**
     * 解析配置基础键名，支持从方法或字段的注解中提取，或根据 Getter 方法名推断
     */
    private static String resolveBaseName(Method method, Field field) {
        ConfigKey annotation = null;
        if (method.isAnnotationPresent(ConfigKey.class)) {
            annotation = method.getAnnotation(ConfigKey.class);
        } else if (field != null && field.isAnnotationPresent(ConfigKey.class)) {
            annotation = field.getAnnotation(ConfigKey.class);
        }

        if (annotation != null) {
            String keyValue = annotation.value();
            // 绝对路径标记会在后续逻辑中通过 absolute 标志识别
            return keyValue.startsWith(".") ? keyValue.substring(1) : keyValue;
        }
        return inferNameFromGetter(method.getName());
    }

    /**
     * 判断是否为绝对路径配置键
     */
    private static boolean isAbsoluteKey(Method method, Field field) {
        ConfigKey annotation = null;
        if (method.isAnnotationPresent(ConfigKey.class)) {
            annotation = method.getAnnotation(ConfigKey.class);
        } else if (field != null && field.isAnnotationPresent(ConfigKey.class)) {
            annotation = field.getAnnotation(ConfigKey.class);
        }
        return annotation != null && annotation.value().startsWith(".");
    }

    /**
     * 从 Getter 方法名推断其属性名称
     */
    private static String inferNameFromGetter(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return StringUtil.lowerFirst(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return StringUtil.lowerFirst(methodName.substring(2));
        }
        return methodName;
    }

    /**
     * 加载并初始化属性转换器
     */
    private static PropertyConverter<?> loadConverter(Method method, Field field,
                                                      PropertyConverterRegistry registry) {
        ConfigConverter annotation = null;
        if (method.isAnnotationPresent(ConfigConverter.class)) {
            annotation = method.getAnnotation(ConfigConverter.class);
        } else if (field != null && field.isAnnotationPresent(ConfigConverter.class)) {
            annotation = field.getAnnotation(ConfigConverter.class);
        }

        if (annotation == null) {
            return null;
        }

        Class<? extends PropertyConverter<?>> converterClass = annotation.value();
        return registry.get(converterClass).orElseGet(() -> {
            try {
                PropertyConverter<?> instance = converterClass.getDeclaredConstructor().newInstance();
                registry.register(instance);
                return instance;
            } catch (Exception e) {
                throw new IllegalStateException("实例化转换器失败: " + converterClass.getName(), e);
            }
        });
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // 拦截 SnapshotAware 接口中的 pin 方法，用于锚定当前配置快照
        if (isPinMethod(method)) {
            return handlePin(invocation.getProxy());
        }

        // 跳过无需拦截的方法，例如 Object 基础方法或带参数的方法
        if (shouldSkip(method)) {
            return invocation.proceed();
        }

        // 获取当前最新或绑定的配置快照
        ConfigSnapshot snapshot = snapshotProvider.get();
        long currentVersion = snapshot.getVersion();

        // 优先从二级缓存中通过版本号匹配获取解析后的结果
        CacheNode cached = valueCache.get(method);
        if (cached != null && cached.version == currentVersion) {
            return cached.value;
        }

        // 获取并缓存方法的元数据，包括配置键、转换器、必填属性等
        MethodMetadata metadata = METADATA_CACHE.computeIfAbsent(
                method, m -> createMetadata(m, converterRegistry));

        // 根据元数据解析最终的配置值
        Object value = resolveValue(metadata, snapshot, invocation);

        // 更新版本化缓存
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    /**
     * 判断是否为 pin 方法
     */
    private boolean isPinMethod(Method method) {
        return "pin".equals(method.getName()) && (method.getParameterCount() == 0);
    }

    /**
     * 判断是否需要跳过当前方法的拦截
     */
    private boolean shouldSkip(Method method) {
        // 不拦截 Object 类定义的 toString、hashCode 等基础方法
        if (method.getDeclaringClass() == Object.class) {
            return true;
        }
        // 配置 Getter 必须是无参方法
        return method.getParameterCount() > 0;
    }

    /**
     * 判断类型是否可以进行代理增强，非基础类型且非标准集合类型通常视为可代理的配置对象
     */
    private boolean isProxyable(Class<?> type) {
        return !type.isPrimitive()
                && type != String.class
                && !type.isArray()
                && !Iterable.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type)
                && !Optional.class.isAssignableFrom(type);
    }

    /**
     * 处理快照锚定逻辑
     */
    private Object handlePin(Object proxy) {
        if (isPinned) {
            return proxy;
        }
        return proxyFactory.createPinnedProxy(snapshotProvider.get(), prefix, targetType);
    }

    /**
     * 解析配置值
     */
    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot, MethodInvocation invocation)
            throws Throwable {
        String key = buildKey(metadata);

        // 获取经过松散匹配处理后的原始配置字符串
        String rawValue = snapshot.getSmart(key).orElse(null);

        // 处理嵌套配置对象：当没有直接配置且返回类型为可代理对象时，创建并返回嵌套代理
        if (rawValue == null && isProxyable(metadata.returnType)) {
            return proxyFactory.createProxy(snapshotProvider, key, metadata.returnType, isPinned);
        }

        // 当配置项不存在时，调用真实 Bean 的 Getter 方法获取字段初始值
        if (rawValue == null) {
            Object defaultValue = invocation.proceed();
            // 如果字段也未初始化且标记为必填，则抛出异常
            if (defaultValue == null && metadata.required) {
                throw new ConfigMissingException("配置项缺失: [" + key + "]");
            }
            return defaultValue;
        }

        // 如果配置了自定义转换器，则使用转换器处理
        if (metadata.converter != null) {
            return convertWithCustomConverter(rawValue, metadata, invocation);
        }

        // 使用通用转换引擎进行处理
        return convert(rawValue, metadata, invocation);
    }

    /**
     * 使用自定义转换器进行转换
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object convertWithCustomConverter(String rawValue, MethodMetadata metadata, MethodInvocation invocation)
            throws Throwable {
        try {
            return ((PropertyConverter) metadata.converter).convert(rawValue, metadata.returnType);
        } catch (Exception e) {
            // 转换发生异常时回退到字段初始值
            return invocation.proceed();
        }
    }

    /**
     * 执行通用类型转换
     */
    private Object convert(String rawValue, MethodMetadata metadata, MethodInvocation invocation) throws Throwable {
        try {
            return ConvertUtil.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            // 转换失败时回退到字段初始值
            return invocation.proceed();
        }
    }

    /**
     * 构建完整的配置键
     */
    private String buildKey(MethodMetadata metadata) {
        return metadata.absolute ? metadata.baseName : prefix + metadata.baseName;
    }

    /**
     * 标准化路径前缀，确保以点号结尾
     */
    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    /**
     * 方法元数据内部类
     */
    private static final class MethodMetadata {
        final String baseName;
        final Class<?> returnType;
        final boolean required;
        final boolean absolute;
        final PropertyConverter<?> converter;

        MethodMetadata(String baseName, Class<?> returnType, boolean required, boolean absolute,
                       PropertyConverter<?> converter) {
            this.baseName = baseName;
            this.returnType = returnType;
            this.required = required;
            this.absolute = absolute;
            this.converter = converter;
        }
    }

    /**
     * 缓存节点内部类，通过版本号实现缓存失效机制
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
