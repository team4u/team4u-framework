package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统属性与环境变量配置源实现
 * <p>
 * 该类负责合并以下两种系统级配置：
 * <ul>
 *     <li><b>JVM 系统属性</b>（通过 {@code -Dkey=value} 传入，优先级更高）</li>
 *     <li><b>操作系统环境变量</b>（环境变量，优先级较低）</li>
 * </ul>
 * 键名归一化处理：由于环境变量通常采用大写和下划线命名（如 {@code APP_PORT}），
 * 本实现会自动生成对应的点分小写键（如 {@code app.port}），以便业务以统一样式进行检索。
 * </p>
 */
public class SystemEnvConfigSource implements ConfigSource {

    /**
     * 默认数据源名称标识
     */
    public static final String DEFAULT_NAME = "SystemEnv";

    /**
     * 数据源描述名称
     */
    private final String name;

    /**
     * 排序优先级
     */
    private final int priority;

    /**
     * 构建系统环境配置源
     *
     * @param priority 优先级数值，数值越小越优先
     */
    public SystemEnvConfigSource(int priority) {
        this(DEFAULT_NAME, priority);
    }

    /**
     * 构建系统环境配置源（自定义名称）
     *
     * @param name     描述名称
     * @param priority 优先级数值
     */
    public SystemEnvConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int priority() {
        return priority;
    }

    /**
     * 执行系统级配置加载
     * <p>
     * 遵循以下合并逻辑：
     * <ul>
     *     <li>先加载操作系统环境变量，并为符合条件的键生成归一化的副本</li>
     *     <li>再加载 JVM 系统属性，若存在重名键，则覆盖环境变量中的对应项</li>
     * </ul>
     * </p>
     */
    @Override
    public Map<String, ConfigEntry> load() {
        long timestamp = System.currentTimeMillis();
        Map<String, ConfigEntry> result = new HashMap<>();

        // 阶段一：加载环境变量（低优先级）
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            String rawKey = env.getKey();
            String value = env.getValue();

            // 保留环境变量原始键
            result.put(rawKey, new ConfigEntry(rawKey, value, name, timestamp));

            // 生成规范化的点分小写键副本
            String normalizedKey = normalizeEnvKey(rawKey);
            if (!normalizedKey.equals(rawKey)) {
                // 仅在目标键位空置时放入，避免覆盖原生已有的配置
                result.putIfAbsent(normalizedKey, new ConfigEntry(normalizedKey, value, name, timestamp));
            }
        }

        // 阶段二：加载 JVM 系统属性（高优先级），直接覆盖同名环境变量
        for (String key : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(key);
            result.put(key, new ConfigEntry(key, value, name, timestamp));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * 环境变量键名规范化逻辑
     * <p>
     * 示例：{@code APP_PORT} -> {@code app.port}
     * </p>
     */
    private String normalizeEnvKey(String envKey) {
        return envKey.replace('_', '.').toLowerCase();
    }
}