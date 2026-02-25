package com.team4u.config.core.spi;

import com.team4u.config.core.domain.ConfigEntry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 基于 Properties 的配置源实现
 * <p>
 * 支持从 Java {@link Properties} 对象加载配置数据。
 * 该源通常用于加载静态的本地配置，如配置文件、系统属性等。
 */
public class PropertiesConfigSource implements ConfigSource {

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 数据源优先级，数值越小优先级越高
     */
    private final int priority;

    /**
     * 内部存储的配置快照
     */
    private final Map<String, ConfigEntry> store;

    /**
     * 通过名称、优先级和 Properties 对象构建配置源
     *
     * @param name       数据源名称
     * @param priority   排序优先级
     * @param properties 配置属性
     */
    public PropertiesConfigSource(String name, int priority, Properties properties) {
        this.name = name;
        this.priority = priority;
        this.store = convert(properties);
    }

    /**
     * 从类路径资源加载属性文件
     *
     * @param name         数据源名称
     * @param priority     优先级
     * @param resourcePath 类路径资源路径（如 "config/app.properties"）
     * @return PropertiesConfigSource 实例
     * @throws IOException 如果加载失败
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
     * 将 Properties 转换为内部存储格式
     *
     * @param properties 配置属性
     * @return 配置项映射
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
