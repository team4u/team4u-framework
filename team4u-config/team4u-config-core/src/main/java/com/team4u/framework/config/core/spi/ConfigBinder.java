package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.domain.ConfigSnapshot;

/**
 * 配置绑定器 SPI 接口
 * <p>
 * 负责将配置快照中的数据（通常为字符串或树形 Map）映射并转换为 Java Bean 或强类型对象。
 * </p>
 */
public interface ConfigBinder {

    /**
     * 将快照数据绑定至目标类型
     * <p>
     * 实现类应当支持智能松散绑定，能够兼容处理不同命名风格（如中划线、驼峰、下划线）的自动匹配。
     * </p>
     *
     * @param snapshot 当前配置快照
     * @param prefix   待绑定的配置前缀
     * @param type     期望绑定的目标 Java 类型
     * @param <T>      目标强类型
     * @return 绑定完成的实例对象，若配置完全缺失则可能返回 null 或由实现决定的空实例
     */
    <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type);
}
