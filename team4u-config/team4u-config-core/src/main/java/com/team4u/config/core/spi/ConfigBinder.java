package com.team4u.config.core.spi;

import com.team4u.config.core.domain.ConfigSnapshot;

/**
 * 配置类型绑定器
 * <p>
 * 负责将 String 类型的配置映射并转换为强类型对象或 Java Bean。
 */
public interface ConfigBinder {

    /**
     * 获取快照中的信息，并转换为给定类型
     * <p>
     * 默认支持智能松散绑定 (Smart Relaxed Binding)，诸如中划线(kebab-case)、驼峰(camelCase)等兼容识别。
     *
     * @param snapshot 当前快照对
     * @param prefix   待绑定的配置前缀
     * @param type     期望绑定的目标类型或接口
     * @param <T>      强类型
     * @return 绑定好数据的实例对象，如果配置完全为空可能返回 null 或是采用对象默认值的实例（依据具体实现策略）
     */
    <T> T bind(ConfigSnapshot snapshot, String prefix, Class<T> type);
}
