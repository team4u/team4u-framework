package com.team4u.log.config;

/**
 * 日志配置监听器
 * <p>
 * 当日志相关的动态配置发生变更时，由 LogConfigManager 触发。
 */
public interface LogConfigListener {

    /**
     * 当配置发生变更时触发
     *
     * @param newConfig 全新的配置快照
     */
    void onConfigChanged(LogDynamicConfig newConfig);
}
