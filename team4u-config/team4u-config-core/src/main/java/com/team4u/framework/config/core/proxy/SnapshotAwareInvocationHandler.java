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
 * 快照感知与 L2 缓存动态代理调用处理器
 * <p>
 * 通过元数据预解析、路径记忆（Sticky Key）及 ConcurrentHashMap 优化，
 * 在保持配置松散绑定的同时，大幅降低高并发下的反射、字符串处理及锁竞争开销。
 */
public class SnapshotAwareInvocationHandler implements InvocationHandler {

    private final Class<?> interfaceType;
    private final String prefix;
    private final Supplier<ConfigSnapshot> snapshotProvider;
    private final boolean isPinned;
    private final ConfigProxyFactory proxyFactory;
    private final PropertyConverterRegistry converterRegistry;

    /**
     * 元数据全局静态缓存：共享解析后的方法元数据，提取静态特征
     */
    private static final Map<Method, MethodMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    /**
     * 二级值缓存：存储当前版本已解析的值，降低类型转换开销
     */
    private final Map<Method, CacheNode> valueCache = new ConcurrentHashMap<>();

    public SnapshotAwareInvocationHandler(Class<?> interfaceType,
            String prefix,
            Supplier<ConfigSnapshot> snapshotProvider,
            boolean isPinned,
            ConfigProxyFactory proxyFactory,
            PropertyConverterRegistry converterRegistry) {
        this.interfaceType = interfaceType;
        // 预处理前缀，保证以 "." 结尾，减少 invoke 时的判断
        this.prefix = (prefix == null || prefix.isEmpty()) ? "" : (prefix.endsWith(".") ? prefix : prefix + ".");
        this.snapshotProvider = snapshotProvider;
        this.isPinned = isPinned;
        this.proxyFactory = proxyFactory;
        this.converterRegistry = converterRegistry;

        // 预热元数据缓存
        warmUp();
    }

    /**
     * 预热元数据：遍历接口所有方法，提前解析并放入 METADATA_CACHE
     */
    private void warmUp() {
        for (Method method : interfaceType.getMethods()) {
            // 排除 Object 基础方法和默认方法，仅处理业务方法
            if (method.getDeclaringClass() != Object.class && !method.isDefault()) {
                METADATA_CACHE.computeIfAbsent(method, m -> createMetadata(m, converterRegistry));
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        // 拦截 Object 基础方法
        if (Object.class.equals(method.getDeclaringClass())) {
            return handleObjectMethods(proxy, method, args);
        }

        // 处理 SnapshotAware 接口的 pin() 方法
        if ("pin".equals(method.getName()) && (args == null || args.length == 0)) {
            return handlePin(proxy);
        }

        // 获取当前快照版本
        ConfigSnapshot snapshot = snapshotProvider.get();
        long currentVersion = snapshot.getVersion();

        // 优先从二级缓存中尝试命中当前版本的结果（快速路径）
        CacheNode node = valueCache.get(method);
        if (node != null && node.version == currentVersion) {
            return node.value;
        }

        // 缓存失效或未命中，获取或预热方法元数据
        MethodMetadata metadata = METADATA_CACHE.computeIfAbsent(method, m -> createMetadata(m, converterRegistry));

        // 解析配置值（慢路径，包含路径记忆逻辑）
        Object value = resolveValue(metadata, snapshot);

        // 更新二级缓存，允许多线程并发更新以提升吞吐量
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    /**
     * 解析配置值：利用路径记忆加速查找
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot) {
        // 根据是否是绝对路径选择拼接方式
        String targetKey = metadata.absolute ? metadata.baseName : prefix + metadata.baseName;

        // 1. 直接调用快照的智能松散匹配获取原始值
        String rawValue = snapshot.getSmart(targetKey).orElse(null);

        // 2. 如果未找到配置且返回类型是接口，尝试作为嵌套对象处理
        if (rawValue == null && metadata.returnType.isInterface()) {
            return proxyFactory.createProxy(snapshotProvider, targetKey, metadata.returnType, isPinned);
        }

        // 3. 处理必填逻辑：如果没有原始值且没有默认值，则抛出异常
        if (rawValue == null && metadata.required && metadata.defaultValue == null) {
            throw new ConfigMissingException("Missing required config: [" + targetKey + "]");
        }

        // 4. 如果依然未找到对应配置，返回预计算的默认值（包含注解值或类型零值）
        if (rawValue == null) {
            return metadata.defaultValue;
        }

        // 5. 优先使用自定义转换器
        if (metadata.converter != null) {
            try {
                return ((PropertyConverter) metadata.converter).convert(rawValue, metadata.returnType);
            } catch (Exception e) {
                // 转换失败时回退到默认值
                return metadata.defaultValue;
            }
        }

        // 6. 进行类型转换并处理潜在异常
        try {
            return Convert.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            // 转换异常时也回退到默认值
            return metadata.defaultValue;
        }
    }

    /**
     * 创建并缓存方法元数据，提取静态特征
     */
    private static MethodMetadata createMetadata(Method method, PropertyConverterRegistry converterRegistry) {
        String baseName;
        boolean absolute = false;

        // 1. 优先解析 @ConfigKey
        if (method.isAnnotationPresent(ConfigKey.class)) {
            String keyValue = method.getAnnotation(ConfigKey.class).value();
            if (keyValue.startsWith(".")) {
                baseName = keyValue.substring(1);
                absolute = true;
            } else {
                baseName = keyValue;
            }
        } else {
            // 自动推断：剥离 Getter 前缀
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                baseName = StrUtil.lowerFirst(name.substring(3));
            } else if (name.startsWith("is") && name.length() > 2) {
                baseName = StrUtil.lowerFirst(name.substring(2));
            } else {
                baseName = name;
            }
        }

        Class<?> returnType = method.getReturnType();
        Object defaultValue = null;

        // 2. 解析 @ConfigDefault
        if (method.isAnnotationPresent(ConfigDefault.class)) {
            String annotationValue = method.getAnnotation(ConfigDefault.class).value();
            try {
                // 提前转换类型，避免运行时转换开销
                defaultValue = Convert.convert(returnType, annotationValue);
            } catch (Exception ignore) {
                // 转换失败则回退
            }
        }

        // 3. 解析 @ConfigRequired
        boolean required = method.isAnnotationPresent(ConfigRequired.class);

        // 4. 解析 @ConfigConverter
        PropertyConverter<?> converter = null;
        if (method.isAnnotationPresent(ConfigConverter.class)) {
            Class<? extends PropertyConverter<?>> converterClass = method.getAnnotation(ConfigConverter.class).value();
            converter = converterRegistry.get(converterClass).orElseGet(() -> {
                try {
                    PropertyConverter<?> instance = converterClass.getDeclaredConstructor()
                            .newInstance();
                    converterRegistry.register(instance);
                    return instance;
                } catch (Exception e) {
                    throw new IllegalStateException("无法实例化转换器: " + converterClass.getName(), e);
                }
            });
        }

        // 5. 如果没有注解或转换失败，使用 Java 类型的默认值 (int=0, boolean=false, Object=null)
        if (defaultValue == null) {
            defaultValue = ClassUtil.getDefaultValue(returnType);
        }

        return new MethodMetadata(baseName, returnType, defaultValue, required, absolute, converter);
    }

    /**
     * 处理锚定请求，返回固定版本的代理对象
     */
    private Object handlePin(Object proxy) {
        if (isPinned) {
            return proxy;
        }
        return proxyFactory.createPinnedProxy(snapshotProvider.get(), prefix, interfaceType);
    }

    /**
     * 处理标准 Object 方法
     */
    private Object handleObjectMethods(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        switch (name) {
            case "toString":
                return "ConfigProxy[" + interfaceType.getSimpleName() + "|pinned=" + isPinned + "|prefix=" + prefix
                        + "]";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
        }
        return null;
    }

    /**
     * 方法静态元数据，用于持有基础名和路径记忆
     */
    private static class MethodMetadata {
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
     * L2 缓存节点，持有版本号和转换后的值
     */
    private static class CacheNode {
        final long version;
        final Object value;

        CacheNode(long version, Object value) {
            this.version = version;
            this.value = value;
        }
    }
}
