package com.team4u.config.core.proxy;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.domain.ConfigSnapshot;

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

    /**
     * 元数据缓存：存储方法的静态信息，避免重复解析方法名
     */
    private final Map<Method, MethodMetadata> metadataMap = new ConcurrentHashMap<>();

    /**
     * 二级值缓存：存储当前版本已解析的值，降低类型转换开销
     */
    private final Map<Method, CacheNode> valueCache = new ConcurrentHashMap<>();

    public SnapshotAwareInvocationHandler(Class<?> interfaceType,
                                          String prefix,
                                          Supplier<ConfigSnapshot> snapshotProvider,
                                          boolean isPinned,
                                          ConfigProxyFactory proxyFactory) {
        this.interfaceType = interfaceType;
        // 预处理前缀，保证以 "." 结尾，减少 invoke 时的判断
        this.prefix = (prefix == null || prefix.isEmpty()) ? "" :
                (prefix.endsWith(".") ? prefix : prefix + ".");
        this.snapshotProvider = snapshotProvider;
        this.isPinned = isPinned;
        this.proxyFactory = proxyFactory;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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
        MethodMetadata metadata = metadataMap.computeIfAbsent(method, this::createMetadata);

        // 解析配置值（慢路径，包含路径记忆逻辑）
        Object value = resolveValue(metadata, snapshot);

        // 更新二级缓存，允许多线程并发更新以提升吞吐量
        valueCache.put(method, new CacheNode(currentVersion, value));

        return value;
    }

    /**
     * 解析配置值：利用路径记忆加速查找
     */
    private Object resolveValue(MethodMetadata metadata, ConfigSnapshot snapshot) {
        String rawValue = null;
        String resolvedKey = null;

        // 尝试使用“记忆”中的键进行 O(1) 查找，消除风格转换开销
        String cachedKey = metadata.effectiveKey;
        if (cachedKey != null) {
            rawValue = snapshot.get(cachedKey).orElse(null);
            if (rawValue != null) {
                resolvedKey = cachedKey;
            }
        }

        // 若记忆失效或尚未建立记忆，则执行全量扫描
        if (rawValue == null) {
            String base = metadata.baseName;
            String key;

            // 按照驼峰 -> 中划线 -> 下划线 -> 点分隔的顺序查找
            // 1. CamelCase (原生名)
            key = prefix + base;
            if ((rawValue = snapshot.get(key).orElse(null)) != null) {
                resolvedKey = key;
            }
            // 2. Kebab-Case
            else if ((rawValue = snapshot.get(key = prefix + StrUtil.toSymbolCase(base, '-')).orElse(null)) != null) {
                resolvedKey = key;
            }
            // 3. Snake_Case
            else if ((rawValue = snapshot.get(key = prefix + StrUtil.toSymbolCase(base, '_')).orElse(null)) != null) {
                resolvedKey = key;
            }
            // 4. Dot.Case
            else if ((rawValue = snapshot.get(key = prefix + StrUtil.toSymbolCase(base, '.')).orElse(null)) != null) {
                resolvedKey = key;
            }
        }

        // 记录或更新路径记忆，以便下次直接命中
        if (resolvedKey != null && !resolvedKey.equals(metadata.effectiveKey)) {
            metadata.effectiveKey = resolvedKey;
        }

        // 若未找到对应配置，返回类型默认值
        if (rawValue == null) {
            return ClassUtil.getDefaultValue(metadata.returnType);
        }

        // 进行类型转换并处理潜在异常
        try {
            return Convert.convert(metadata.returnType, rawValue);
        } catch (Exception e) {
            return ClassUtil.getDefaultValue(metadata.returnType);
        }
    }

    /**
     * 创建并缓存方法元数据，提取静态特征
     */
    private MethodMetadata createMetadata(Method method) {
        String name = method.getName();
        String baseName = name;
        // 自动剥离 Getter 前缀
        if (name.startsWith("get") && name.length() > 3) {
            baseName = StrUtil.lowerFirst(name.substring(3));
        } else if (name.startsWith("is") && name.length() > 2) {
            baseName = StrUtil.lowerFirst(name.substring(2));
        }
        return new MethodMetadata(baseName, method.getReturnType());
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
        if ("toString".equals(name)) {
            return "ConfigProxy[" + interfaceType.getSimpleName() + "|pinned=" + isPinned + "|prefix=" + prefix + "]";
        }
        if ("hashCode".equals(name)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(name)) {
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
        /**
         * 运行时记忆：上一次命中的确切配置键，使用 volatile 保证多线程可见性
         */
        volatile String effectiveKey;

        MethodMetadata(String baseName, Class<?> returnType) {
            this.baseName = baseName;
            this.returnType = returnType;
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
