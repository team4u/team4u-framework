package com.team4u.config.core.proxy;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.LRUCache;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.team4u.config.core.domain.ConfigSnapshot;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 快照感知与 L2 缓存动态代理调用处理器
 */
public class SnapshotAwareInvocationHandler implements InvocationHandler {

    private final Class<?> interfaceType;
    private final String prefix;
    private final Supplier<ConfigSnapshot> snapshotProvider;
    private final boolean isPinned;
    private final ConfigProxyFactory proxyFactory;

    // 二级缓存：用于缓存转换后的类型结果，降低类型转化及反射带来的 CPU 开销。
    // key = method, value = CacheNode
    // 使用支持并发安全的 LRU 缓存，默认最大容量 512，防止内存无限膨胀
    private final LRUCache<Method, CacheNode> l2Cache = CacheUtil.newLRUCache(512);

    public SnapshotAwareInvocationHandler(Class<?> interfaceType,
                                          String prefix,
                                          Supplier<ConfigSnapshot> snapshotProvider,
                                          boolean isPinned,
                                          ConfigProxyFactory proxyFactory) {
        this.interfaceType = interfaceType;
        this.prefix = prefix == null ? "" : (prefix.endsWith(".") ? prefix : prefix + ".");
        this.snapshotProvider = snapshotProvider;
        this.isPinned = isPinned;
        this.proxyFactory = proxyFactory;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        // Object 基础方法处理
        if ("equals".equals(methodName)) {
            return proxy == args[0];
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("toString".equals(methodName)) {
            return "ConfigProxy[" + interfaceType.getSimpleName() + "|pinned=" + isPinned + "|prefix=" + prefix + "]";
        }

        // SnapshotAware 的 pin() 方法处理
        if ("pin".equals(methodName) && method.getParameterCount() == 0) {
            if (isPinned) {
                return proxy; // 已经是 Pinned，直接返回
            }
            // 产生一个新的固定版本 Proxy
            ConfigSnapshot pinnedSnapshot = snapshotProvider.get();
            return proxyFactory.createPinnedProxy(pinnedSnapshot, prefix, interfaceType);
        }

        // Getter 属性拦截读取逻辑
        long currentVersion = snapshotProvider.get().getVersion();

        // 尝试从 L2 缓存命中
        CacheNode cached = l2Cache.get(method);
        if (cached != null && cached.version == currentVersion) {
            return cached.value;
        }

        // 缓存失效或未命中，从 Snapshot 读取并重建转化
        Object resolvedValue = resolveConfigValue(method, snapshotProvider.get());

        // 更新二级缓存 (无锁替换，允许多线程小概率覆盖以提升读取吞吐量)
        l2Cache.put(method, new CacheNode(currentVersion, resolvedValue));

        return resolvedValue;
    }

    private Object resolveConfigValue(Method method, ConfigSnapshot snapshot) {
        Class<?> returnType = method.getReturnType();
        String baseName = extractBaseName(method.getName());

        // 尝试多种命名风格进行匹配
        String rawValue = findRawValue(snapshot, baseName);

        if (rawValue == null) {
            // 支持获取默认值逻辑，若框架未引入 @DefaultValue，暂回退为 JDK 默认 Null/零值策略
            // 如果返回基础类型但没有值，则给出基础类型的默认值
            return cn.hutool.core.util.ClassUtil.getDefaultValue(returnType);
        }

        // 利用 Hutool 尝试进行弱类型/强类型松散转化
        try {
            return Convert.convert(returnType, rawValue);
        } catch (Exception e) {
            // 容错处理：当配置不合规被读取时，不能使得核心业务直接异常，退回默认状态
            return cn.hutool.core.util.ClassUtil.getDefaultValue(returnType);
        }
    }

    /**
     * 根据方法名尝试在快照中寻找匹配的配置值
     */
    private String findRawValue(ConfigSnapshot snapshot, String baseName) {
        // 1. 尝试原始名称 (camelCase)
        String value = snapshot.get(prefix + baseName).orElse(null);
        if (value != null) {
            return value;
        }

        // 2. 尝试 kebab-case (最推荐风格)
        value = snapshot.get(prefix + StrUtil.toSymbolCase(baseName, '-')).orElse(null);
        if (value != null) {
            return value;
        }

        // 3. 尝试 snake_case
        value = snapshot.get(prefix + StrUtil.toSymbolCase(baseName, '_')).orElse(null);
        if (value != null) {
            return value;
        }

        // 4. 尝试 dot.case
        return snapshot.get(prefix + StrUtil.toSymbolCase(baseName, '.')).orElse(null);
    }

    /**
     * 从方法名中提取基础属性名
     */
    private String extractBaseName(String methodName) {
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return StrUtil.lowerFirst(methodName.substring(3));
        } else if (methodName.startsWith("is") && methodName.length() > 2) {
            return StrUtil.lowerFirst(methodName.substring(2));
        }
        return methodName;
    }

    /**
     * L2 零级节点缓存
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
