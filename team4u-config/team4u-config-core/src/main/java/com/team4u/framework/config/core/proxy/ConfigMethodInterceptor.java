package com.team4u.framework.config.core.proxy;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.annotation.ConfigConverter;
import com.team4u.framework.config.core.annotation.ConfigDefault;
import com.team4u.framework.config.core.annotation.ConfigKey;
import com.team4u.framework.config.core.annotation.ConfigRequired;
import com.team4u.framework.config.core.convert.PropertyConverter;
import com.team4u.framework.config.core.convert.PropertyConverterRegistry;
import com.team4u.framework.config.core.domain.ConfigMissingException;
import com.team4u.framework.config.core.domain.ConfigSnapshot;
import com.team4u.framework.proxy.core.MethodInterceptor;
import com.team4u.framework.proxy.core.MethodInvocation;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 现代化的配置方法拦截器
 * <p>
 * 基于 team4u-proxy 提供的拦截机制，实现配置项到方法调用的自动映射。
 * 支持接口代理和 Java Bean 增强模式。
 * </p>
 *
 * @author jay.wu
 */
public class ConfigMethodInterceptor implements MethodInterceptor {

    /**
     * 全局方法元数据静态缓存
     */
    private static final Map<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    private final Class<?> targetType;
    private final String prefix;
    private final Supplier<ConfigSnapshot> snapshotProvider;
    private final boolean isPinned;
    private final ConfigProxyFactory proxyFactory;
    private final PropertyConverterRegistry converterRegistry;

    /**
     * 实例级配置值缓存，绑定快照版本号
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

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        // 1. 拦截 SnapshotAware.pin() 锚定请求
        if (isPinMethod(method)) {
            return handlePin(invocation.getProxy());
        }

        // 2. 检查是否为需要处理的方法（无参且非 Object 基础方法）
        if (shouldSkip(method)) {
            return invocation.proceed();
        }

        ConfigSnapshot snapshot = snapshotProvider.get();
        long currentVersion = snapshot.getVersion();

        // 3. 尝试从版本化缓存中快速获取
        CacheNode cached = valueCache.get(method);
        if (cached != null && cached.version == currentVersion) {
            return cached.value;
        }

        // 4. 解析元数据
        MethodMetadata metadata = METADATA_CACHE.computeIfAbsent(
                method, m -> createMetadata(m, converterRegistry));

        // 5. 执行解析逻辑
        Object value = resolveValue(metadata, snapshot);

        // 6. 更新缓存
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    private boolean isPinMethod(Method method) {
        return "pin".equals(method.getName()) && (method.getParameterCount() == 0);
    }

    private boolean shouldSkip(Method method) {
        // Object 的基础方法不拦截
        if (method.getDeclaringClass() == Object.class) {
            return true;
        }
        // 有参数的方法暂不视为配置 Getter
        return method.getParameterCount() > 0;
    }

    private boolean isProxyable(Class<?> type) {
        if (type.isInterface()) {
            return true;
        }
        // 排除基础类型、字符串、数组、集合、映射等简单类型或标准容器
        return !type.isPrimitive()
                && type != String.class
                && !type.isArray()
                && !Iterable.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type)
                && !java.util.Optional.class.isAssignableFrom(type);
    }

    private Object handlePin(Object proxy) {
        if (isPinned) {
            return proxy;
        }
        return proxyFactory.createPinnedProxy(snapshotProvider.get(), prefix, targetType);
    }

    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot) {
        String key = buildKey(metadata);

        // 获取经过松散匹配处理后的原始配置值
        String rawValue = snapshot.getSmart(key).orElse(null);

        // 处理嵌套对象：若当前层级无直接配置，且方法返回类型可能是配置对象，则将其视为下级配置节点进行代理
        if (rawValue == null && isProxyable(metadata.returnType)) {
            return proxyFactory.createProxy(snapshotProvider, key, metadata.returnType, isPinned);
        }

        // 必填项缺失校验
        if (rawValue == null && metadata.required && metadata.defaultValue == null) {
            throw new ConfigMissingException("配置项缺失: [" + key + "]");
        }

        // 若原始配置不存在，则使用预解析的默认值
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object convertWithCustomConverter(String rawValue, MethodMetadata metadata) {
        try {
            return ((PropertyConverter) metadata.converter).convert(rawValue, metadata.returnType);
        } catch (Exception e) {
            return metadata.defaultValue;
        }
    }

    private Object convert(String rawValue, MethodMetadata metadata) {
        try {
            return Convert.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            return metadata.defaultValue;
        }
    }

    private String buildKey(MethodMetadata metadata) {
        return metadata.absolute ? metadata.baseName : prefix + metadata.baseName;
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        return prefix.endsWith(".") ? prefix : prefix + ".";
    }

    private static MethodMetadata createMetadata(Method method, PropertyConverterRegistry converterRegistry) {
        String baseName = resolveBaseName(method);
        boolean absolute = isAbsoluteKey(method);
        Class<?> returnType = method.getReturnType();
        boolean required = method.isAnnotationPresent(ConfigRequired.class);
        Object defaultValue = resolveDefaultValue(method, returnType);
        PropertyConverter<?> converter = loadConverter(method, converterRegistry);

        return new MethodMetadata(baseName, returnType, defaultValue, required, absolute, converter);
    }

    private static String resolveBaseName(Method method) {
        if (method.isAnnotationPresent(ConfigKey.class)) {
            String keyValue = method.getAnnotation(ConfigKey.class).value();
            return keyValue.startsWith(".") ? keyValue.substring(1) : keyValue;
        }
        return inferNameFromGetter(method.getName());
    }

    private static boolean isAbsoluteKey(Method method) {
        return method.isAnnotationPresent(ConfigKey.class) && method.getAnnotation(ConfigKey.class).value().startsWith(".");
    }

    private static String inferNameFromGetter(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return StrUtil.lowerFirst(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return StrUtil.lowerFirst(methodName.substring(2));
        }
        return methodName;
    }

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

    private static PropertyConverter<?> loadConverter(Method method, PropertyConverterRegistry registry) {
        if (!method.isAnnotationPresent(ConfigConverter.class)) {
            return null;
        }

        Class<? extends PropertyConverter<?>> converterClass = method.getAnnotation(ConfigConverter.class).value();
        return registry.get(converterClass).orElseGet(() -> {
            try {
                PropertyConverter<?> instance = converterClass.getDeclaredConstructor().newInstance();
                registry.register(instance);
                return instance;
            } catch (Exception e) {
                throw new IllegalStateException("无法实例化转换器: " + converterClass.getName(), e);
            }
        });
    }

    private static final class MethodMetadata {
        final String baseName;
        final Class<?> returnType;
        final Object defaultValue;
        final boolean required;
        final boolean absolute;
        final PropertyConverter<?> converter;

        MethodMetadata(String baseName, Class<?> returnType, Object defaultValue, boolean required, boolean absolute, PropertyConverter<?> converter) {
            this.baseName = baseName;
            this.returnType = returnType;
            this.defaultValue = defaultValue;
            this.required = required;
            this.absolute = absolute;
            this.converter = converter;
        }
    }

    private static final class CacheNode {
        final long version;
        final Object value;

        CacheNode(long version, Object value) {
            this.version = version;
            this.value = value;
        }
    }
}
