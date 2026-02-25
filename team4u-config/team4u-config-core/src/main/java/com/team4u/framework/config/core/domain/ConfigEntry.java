package com.team4u.framework.config.core.domain;

import com.team4u.framework.config.core.spi.ConfigSource;
import lombok.Value;

/**
 * 最小配置单元
 * <p>
 * 配置的原子载体，包含配置的键值和元数据。
 */
@Value
public class ConfigEntry {
    /**
     * 配置键 (例如: "app.server.port")
     */
    String key;
    /**
     * 配置值 (若为 {@link ConfigSource#TOMBSTONE_VALUE}，则代表该配置在此源中被删除或未定义)
     */
    String value;
    /**
     * 来源 (例如: "JDBC-Primary", "File:/opt/conf/app.prop")
     */
    String sourceName;
    /**
     * 更新时间戳
     */
    long timestamp;

    /**
     * 判断当前配置项是否为空或已删除
     *
     * @return 如果值为 {@link ConfigSource#TOMBSTONE_VALUE}，则视为已删除或失效
     */
    public boolean isEmptyOrDeleted() {
        return value == ConfigSource.TOMBSTONE_VALUE;
    }
}
