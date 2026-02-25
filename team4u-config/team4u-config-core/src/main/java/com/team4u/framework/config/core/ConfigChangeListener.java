package com.team4u.framework.config.core;

/**
 * 明确的配置变更回调接口
 */
public interface ConfigChangeListener {
    /**
     * 配置变更回调
     *
     * @param key      发生变更的精确配置键
     * @param oldValue 旧值 (如果之前不存在或已删除，则可能为 null)
     * @param newValue 新值 (如果被删除，则为 null)
     */
    void onChange(String key, String oldValue, String newValue);
}
