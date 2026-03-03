package com.team4u.log.config;

import cn.hutool.log.Log;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 动态配置管理器
 * <p>
 * 对接配置中心（team4u-config），利用 ConfigDrivenRegistry 维护配置的生命周期。
 */
public class LogConfigManager {

    /**
     * 配置键名
     */
    private static final String CONFIG_KEY = "team4u.log.config";

    private static final LogConfigManager INSTANCE = new LogConfigManager();

    private final LogConfigParser parser = new LogConfigParser();

    /**
     * 配置驱动注册表
     */
    private ConfigDrivenRegistry<LogDynamicConfig> registry;

    /**
     * 监听器列表
     */
    private final List<LogConfigListener> listeners = new CopyOnWriteArrayList<>();

    private volatile LogDynamicConfig currentConfig = new LogDynamicConfig();

    private LogConfigManager() {
    }

    /**
     * 获取配置管理器单例实例
     *
     * @return LogConfigManager 实例
     */
    public static LogConfigManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册配置监听器
     *
     * @param listener 监听器实例
     */
    public void addListener(LogConfigListener listener) {
        if (listener != null) {
            listeners.add(listener);
            // 注册时立即触发一次同步，确保组件状态与当前配置一致
            listener.onConfigChanged(getCurrentConfig());
        }
    }

    /**
     * 初始化配置管理器
     *
     * @param globalConfigManager 全局配置管理器
     */
    public void init(ConfigManager globalConfigManager) {
        this.registry = new ConfigDrivenRegistry<>(globalConfigManager, "team4u.log", json -> {
            LogDynamicConfig config = parser.parse(json);
            if (config == null) {
                config = new LogDynamicConfig();
            }
            this.currentConfig = config;

            // 触发监听器通知，解耦硬编码的分发逻辑
            for (LogConfigListener listener : listeners) {
                try {
                    listener.onConfigChanged(config);
                } catch (Exception e) {
                    // 打印错误日志，避免单条监听器异常中断整体配置刷新
                    Log.get().error("LogConfigManager|notifyListener|error|msg={}", e.getMessage());
                }
            }
            return config;
        });

        // 首次加载配置
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 手动更新当前配置（主要用于单元测试或特定初始化场景）
     *
     * @param config 新的配置实例
     */
    public void setCurrentConfig(LogDynamicConfig config) {
        if (config == null) {
            config = new LogDynamicConfig();
        }
        this.currentConfig = config;

        // 触发通知
        for (LogConfigListener listener : listeners) {
            try {
                listener.onConfigChanged(config);
            } catch (Exception e) {
                Log.get().error("LogConfigManager|setCurrentConfig|error|msg={}", e.getMessage());
            }
        }
    }

    /**
     * 获取当前最实时的配置快照
     *
     * @return LogDynamicConfig 实例
     */
    public LogDynamicConfig getCurrentConfig() {
        return currentConfig;
    }
}
