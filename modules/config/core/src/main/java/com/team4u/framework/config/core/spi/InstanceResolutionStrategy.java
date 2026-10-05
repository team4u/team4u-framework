package com.team4u.framework.config.core.spi;

import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;

import java.util.Optional;

/**
 * 配置驱动实例解析与生命周期策略接口
 * <p>
 * 封装不同配置模式（单键文档型、扁平键前缀树或自定义扩展模式）下的配置规则解析、实例构建、变更分发与资源生命周期差异。
 * </p>
 *
 * @param <T> 实例类型
 */
public interface InstanceResolutionStrategy<T> {

    /**
     * 获取默认实例缓存键
     * <p>
     * 用于推导是否支持无参 {@code get()}。若返回非空 Optional，则无参 {@code get()} 将使用该键获取实例；
     * 若返回 {@link Optional#empty()}，则表明该注册表不支持无参调用。
     * </p>
     *
     * @return 默认实例缓存键，默认返回空
     */
    default Optional<String> defaultInstanceKey() {
        return Optional.empty();
    }

    /**
     * 解析实例缓存键
     * <p>
     * 将传入的查询键（完整配置键或短标识）解析为统一的实例缓存键。
     * </p>
     *
     * @param configKey 完整配置键或短标识
     * @return 实例缓存键
     */
    String resolveInstanceKey(String configKey);

    /**
     * 创建初始实例
     * <p>
     * 在缓存未命中时从当前配置中心构建对应实例。若配置缺失或工厂构建返回 null，表示该键无实例、不入缓存。
     * </p>
     *
     * @param configManager 配置管理器
     * @param instanceKey   实例缓存键
     * @return 构建出的实例对象，若配置缺失或无对应实例则返回 null
     */
    T createInstance(ConfigManager configManager, String instanceKey);

    /**
     * 处理配置变更
     * <p>
     * 根据具体的配置语义（键级删除或分层子树删除、内容指纹去重等）决策并执行热重载或实例下线。
     * </p>
     *
     * @param registry   配置驱动注册表门面
     * @param changedKey 发生变更的配置键
     * @param oldValue   旧配置值
     * @param newValue   新配置值
     */
    void onConfigChanged(ConfigDrivenRegistry<T> registry, String changedKey, String oldValue, String newValue);

    /**
     * 实例被移除时的清理回调
     *
     * @param instanceKey 实例缓存键
     */
    default void onInstanceRemoved(String instanceKey) {
    }

    /**
     * 策略销毁与资源清理
     */
    default void destroy() {
    }
}
