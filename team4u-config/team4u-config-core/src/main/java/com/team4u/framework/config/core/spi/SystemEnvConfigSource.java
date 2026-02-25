package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于系统属性与环境变量的配置源实现
 * <p>
 * 按照以下优先级顺序合并两类系统级数据：
 * <ol>
 * <li><b>JVM 系统属性</b>（{@link System#getProperties()}），通过 {@code -Dkey=value}
 * 传入，优先级更高</li>
 * <li><b>操作系统环境变量</b>（{@link System#getenv()}），优先级较低</li>
 * </ol>
 * 当同一个键同时存在于系统属性和环境变量中时，系统属性的值将覆盖环境变量的值。
 * <p>
 * 键名归一化：环境变量通常以 {@code MY_APP_PORT} 形式（全大写、下划线分隔）定义，
 * 本实现会将其同时以原始形式（{@code MY_APP_PORT}）和"点分小写"形式（{@code my.app.port}）
 * 写入存储，以便调用方用任意惯用风格查询。
 * <p>
 * 本实现每次调用 {@link #load()} 都会重新读取系统状态，可感知 JVM 运行期间
 * 对系统属性的动态修改，因此不缓存结果。
 */
public class SystemEnvConfigSource implements ConfigSource {

    /**
     * 默认数据源名称
     */
    public static final String DEFAULT_NAME = "SystemEnv";

    /**
     * 数据源名称
     */
    private final String name;

    /**
     * 数据源优先级，数值越小优先级越高；
     * 系统属性通常作为覆盖层，建议分配较高（数值较小）的优先级
     */
    private final int priority;

    /**
     * 使用默认名称（{@value #DEFAULT_NAME}）和指定优先级构建系统属性配置源
     *
     * @param priority 排序优先级，数值越小越优先
     */
    public SystemEnvConfigSource(int priority) {
        this(DEFAULT_NAME, priority);
    }

    /**
     * 使用自定义名称和优先级构建系统属性配置源
     *
     * @param name     数据源名称
     * @param priority 排序优先级，数值越小越优先
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
     * 加载系统属性与环境变量，合并为统一的配置映射
     * <p>
     * 加载策略：
     * <ol>
     * <li>先写入操作系统环境变量（原始键 + 点分小写键）</li>
     * <li>再写入 JVM 系统属性，同名键将覆盖环境变量</li>
     * </ol>
     *
     * @return 当前所有系统级配置项的只读视图
     */
    @Override
    public Map<String, ConfigEntry> load() {
        long timestamp = System.currentTimeMillis();
        Map<String, ConfigEntry> result = new HashMap<>();

        // 第一步：写入操作系统环境变量（优先级低）
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            String rawKey = env.getKey();
            String value = env.getValue();

            // 保留原始键（如 MY_APP_PORT）
            result.put(rawKey, new ConfigEntry(rawKey, value, name, timestamp));

            // 同时写入点分小写的规范化键（如 my.app.port），便于统一风格查询
            String normalizedKey = normalizeEnvKey(rawKey);
            if (!normalizedKey.equals(rawKey)) {
                // 规范化键不覆盖已有项，避免系统属性被环境变量的衍生键意外抹除
                result.putIfAbsent(normalizedKey, new ConfigEntry(normalizedKey, value, name, timestamp));
            }
        }

        // 第二步：写入 JVM 系统属性，优先级更高，直接覆盖同名环境变量
        for (String key : System.getProperties().stringPropertyNames()) {
            String value = System.getProperty(key);
            result.put(key, new ConfigEntry(key, value, name, timestamp));
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * 将环境变量的命名风格（{@code MY_APP_PORT}）转换为点分小写风格（{@code my.app.port}）
     *
     * @param envKey 原始环境变量键名
     * @return 规范化后的点分小写键名；若无需转换则返回原值
     */
    private String normalizeEnvKey(String envKey) {
        return envKey.replace('_', '.').toLowerCase();
    }
}