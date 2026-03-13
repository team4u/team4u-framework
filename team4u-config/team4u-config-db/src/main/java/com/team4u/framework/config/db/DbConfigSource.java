package com.team4u.framework.config.db;

import com.team4u.framework.base.util.ConvertUtil;
import com.team4u.framework.base.util.JdbcUtil;
import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.config.core.spi.ConfigSource;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

/**
 * 基于数据库的配置源实现。
 * <p>
 * 从 {@code system_config} 表加载配置，支持全量及增量加载。
 * 配置键采用 {@code config_type.config_key} 格式，实现模块化隔离。
 * 支持软删除逻辑（{@code enabled=0}），自动映射为失效标记。
 * </p>
 */
@Slf4j
public class DbConfigSource implements ConfigSource {

    /**
     * 数据源描述名称
     */
    private final String name;

    /**
     * 优先级数值，数值越小越优先
     */
    private final int priority;

    /**
     * 数据库数据源
     */
    private final DataSource dataSource;

    /**
     * 数据库配置选项
     */
    private final DbConfigOptions options;

    /**
     * 构建 DB 配置源
     *
     * @param name       描述名称
     * @param priority   优先级
     * @param dataSource 数据源
     */
    public DbConfigSource(String name, int priority, DataSource dataSource) {
        this(name, priority, dataSource, new DbConfigOptions());
    }

    /**
     * 构建 DB 配置源
     *
     * @param name       描述名称
     * @param priority   优先级
     * @param dataSource 数据源
     * @param options    数据库配置选项
     */
    public DbConfigSource(String name, int priority, DataSource dataSource, DbConfigOptions options) {
        this.name = name;
        this.priority = priority;
        this.dataSource = dataSource;
        this.options = options;
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
     * 全量加载配置项。
     */
    @Override
    public Map<String, ConfigEntry> load() {
        try {
            List<Map<String, Object>> rows = queryRows(null);
            return toConfigMap(rows);
        } catch (SQLException e) {
            log.error("[{}] Failed to load all configs", name, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 增量拉取配置变更。
     *
     * @param timestamp 起始毫秒时间戳
     */
    @Override
    public Map<String, ConfigEntry> loadSince(long timestamp) {
        try {
            List<Map<String, Object>> rows = queryRows(timestamp);
            return toConfigMap(rows);
        } catch (SQLException e) {
            log.error("[{}] Failed to load incremental configs, timestamp={}", name, timestamp, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 执行数据库查询。
     *
     * @param sinceTimestamp 起始时间戳，不为空时执行增量查询
     * @return 记录快照列表
     */
    private List<Map<String, Object>> queryRows(Long sinceTimestamp) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(options.getTableName())
                .append(" WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (sinceTimestamp != null) {
            sql.append(" AND ").append(options.getUpdateTimeColumn()).append(" > ?");
            params.add(new Timestamp(sinceTimestamp));
        }

        return JdbcUtil.query(dataSource, sql.toString(), params.toArray());
    }

    /**
     * 数据实体转换逻辑。
     * <p>
     * 将数据库行映射为配置条目，并处理软删除逻辑。
     * </p>
     */
    private Map<String, ConfigEntry> toConfigMap(List<Map<String, Object>> rows) {
        Map<String, ConfigEntry> result = new HashMap<>(rows.size());
        long now = System.currentTimeMillis();

        for (Map<String, Object> row : rows) {
            String configType = ConvertUtil.toStr(row.get(options.getConfigTypeColumn().toLowerCase()));
            String configKey = ConvertUtil.toStr(row.get(options.getConfigKeyColumn().toLowerCase()));
            String configValue = ConvertUtil.toStr(row.get(options.getConfigValueColumn().toLowerCase()));
            Integer enabled = ConvertUtil.toInt(row.get(options.getEnabledColumn().toLowerCase()));

            // 拼接配置键：prefix.key
            String fullKey = configType + "." + configKey;

            // 处理软删除，标记为失效条目
            String value = (enabled != null && enabled == 0) ? TOMBSTONE_VALUE : configValue;

            result.put(fullKey, new ConfigEntry(fullKey, value, name, now));
        }

        return Collections.unmodifiableMap(result);
    }
}
