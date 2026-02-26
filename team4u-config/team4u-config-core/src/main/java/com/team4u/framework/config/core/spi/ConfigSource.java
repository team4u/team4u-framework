package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigEntry;
import com.team4u.framework.policy.OrderedPolicy;

import java.util.Map;

/**
 * 核心配置数据源 SPI 接口
 * <p>
 * 负责从特定介质（如文件、数据库、环境变量等）加载原始配置数据。
 * 继承自 {@link OrderedPolicy}，支持基于优先级的插件化加载。
 * </p>
 */
public interface ConfigSource extends OrderedPolicy {

    /**
     * 配置失效哨兵值
     * <p>
     * 当数据源返回此值时，显式表示该配置项已失效，将屏蔽低优先级源中的同名配置。
     * </p>
     */
    String TOMBSTONE_VALUE = null;

    /**
     * 数据源描述名称
     * <p>
     * 用于在日志或快照元数据中标识配置来源，例如 "JDBC-Primary" 或 "Local-File"。
     * </p>
     *
     * @return 唯一的描述名称
     */
    String name();

    /**
     * 执行全量配置加载
     *
     * @return 当前数据源包含的所有配置映射表，应包含失效标记（Tombstone）条目
     */
    Map<String, ConfigEntry> load();

    /**
     * 执行增量配置加载
     * <p>
     * 若数据源支持高效的增量获取，可实现此方法以提升聚合效率。
     * </p>
     *
     * @param timestamp 上次访问的时间戳
     * @return 自指定时间点以来发生变更的配置项；若不支持增量加载，请返回 null，框架将回退到全量加载
     */
    default Map<String, ConfigEntry> loadSince(long timestamp) {
        return null;
    }
}
