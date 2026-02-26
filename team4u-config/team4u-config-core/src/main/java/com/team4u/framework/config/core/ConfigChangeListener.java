package com.team4u.framework.config.core;

/**
 * 配置变更监听器接口
 * <p>
 * 当配置中心的配置项发生变更时，将通过此接口进行回调通知。
 * 监听器可以注册到特定的配置键或匹配模式上。
 */
public interface ConfigChangeListener {
    /**
     * 当配置发生变更时触发此回调
     *
     * @param key      发生变更的精确配置键名
     * @param oldValue 变更前的原始值，如果该配置项之前不存在或已被标记为失效，则返回 null
     * @param newValue 变更后的目标值，如果该配置项被移除，则返回 null
     */
    void onChange(String key, String oldValue, String newValue);
}
