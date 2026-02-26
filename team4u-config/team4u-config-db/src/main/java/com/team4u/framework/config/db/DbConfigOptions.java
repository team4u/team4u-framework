package com.team4u.framework.config.db;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 数据库配置映射选项。
 * <p>
 * 用于自定义配置表的表名及字段名映射，增强框架的灵活性。
 * </p>
 */
@Data
@Accessors(chain = true)
public class DbConfigOptions {

    /**
     * 默认配置表名
     */
    public static final String DEFAULT_TABLE_NAME = "system_config";

    /**
     * 默认配置类型字段名
     */
    public static final String DEFAULT_CONFIG_TYPE_COLUMN = "config_type";

    /**
     * 默认配置键字段名
     */
    public static final String DEFAULT_CONFIG_KEY_COLUMN = "config_key";

    /**
     * 默认配置值字段名
     */
    public static final String DEFAULT_CONFIG_VALUE_COLUMN = "config_value";

    /**
     * 默认启用状态字段名
     */
    public static final String DEFAULT_ENABLED_COLUMN = "enabled";

    /**
     * 默认更新时间字段名
     */
    public static final String DEFAULT_UPDATE_TIME_COLUMN = "update_time";

    /**
     * 配置表名
     */
    private String tableName = DEFAULT_TABLE_NAME;

    /**
     * 配置类型字段名（对应 Key 的前缀）
     */
    private String configTypeColumn = DEFAULT_CONFIG_TYPE_COLUMN;

    /**
     * 配置键字段名
     */
    private String configKeyColumn = DEFAULT_CONFIG_KEY_COLUMN;

    /**
     * 配置值字段名
     */
    private String configValueColumn = DEFAULT_CONFIG_VALUE_COLUMN;

    /**
     * 启用状态字段名（0 表示禁用/删除）
     */
    private String enabledColumn = DEFAULT_ENABLED_COLUMN;

    /**
     * 更新时间字段名（用于增量拉取及变更探测）
     */
    private String updateTimeColumn = DEFAULT_UPDATE_TIME_COLUMN;
}
