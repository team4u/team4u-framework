package com.team4u.framework.config.core.domain;

import com.team4u.framework.config.core.spi.ConfigSource;
import lombok.Value;

/**
 * 配置实体单元
 * <p>
 * 配置的原子载体，封装了配置的键值对及其元数据。
 * 该类是不可变的，确保在快照中安全共享。
 * </p>
 */
@Value
public class ConfigEntry {
    /**
     * 配置键，例如 "app.server.port"
     */
    String key;
    /**
     * 配置值
     * <p>
     * 如果该值为 {@link ConfigSource#TOMBSTONE_VALUE}，表示该配置已失效。
     * </p>
     */
    String value;
    /**
     * 配置来源标识，例如 "JDBC-Primary" 或 "File:/opt/conf/app.prop"
     */
    String sourceName;
    /**
     * 记录更新的时间戳（毫秒）
     */
    long timestamp;

    /**
     * 检查当前配置项是否已失效
     *
     * @return 如果值等于 {@link ConfigSource#TOMBSTONE_VALUE}，则返回 true
     */
    public boolean isEmptyOrDeleted() {
        return value == ConfigSource.TOMBSTONE_VALUE;
    }
}
