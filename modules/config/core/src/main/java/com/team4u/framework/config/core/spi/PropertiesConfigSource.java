package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 基于 Java Properties 的配置源实现
 * <p>
 * 该实现允许从 {@link Properties} 对象或类路径下的属性文件中加载配置。
 * 适用于加载静态的本地预置配置，例如 {@code app.properties}。
 * </p>
 */
public class PropertiesConfigSource implements ConfigSource {

    /**
     * 数据源描述名称
     */
    private final String name;

    /**
     * 排序优先级
     */
    private final int priority;

    /**
     * 内部持有的配置数据镜像
     */
    private final Map<String, ConfigEntry> store;

    /**
     * 构建属性配置源
     *
     * @param name       描述名称
     * @param priority   优先级数值
     * @param properties 原始属性集
     */
    public PropertiesConfigSource(int priority, Properties properties) {
        this("Properties", priority, properties);
    }

    /**
     * 构建属性配置源（自定义名称）
     *
     * @param name       描述名称
     * @param priority   优先级数值
     * @param properties 原始属性集
     */
    public PropertiesConfigSource(String name, int priority, Properties properties) {
        this.name = name;
        this.priority = priority;
        this.store = convert(properties);
    }

    /**
     * 从类路径资源加载属性文件并创建配置源
     *
     * @param name         描述名称
     * @param priority     优先级数值
     * @param resourcePath 类路径下的资源路径（如 "config/app.properties"）
     * @return 配置源实例
     * @throws IOException 加载资源失败时抛出
     */
    public static PropertiesConfigSource fromResource(String name, int priority, String resourcePath) throws IOException {
        Properties properties = new Properties();
        try (InputStream is = PropertiesConfigSource.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            properties.load(is);
        }
        return new PropertiesConfigSource(name, priority, properties);
    }

    /**
     * 将 Properties 对象转换为内部统一的实体映射
     */
    private Map<String, ConfigEntry> convert(Properties properties) {
        if (properties == null) {
            return Collections.emptyMap();
        }

        Map<String, ConfigEntry> map = new HashMap<>();
        long timestamp = System.currentTimeMillis();

        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            map.put(key, new ConfigEntry(key, value, name, timestamp));
        }

        return Collections.unmodifiableMap(map);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Map<String, ConfigEntry> load() {
        return store;
    }
}
