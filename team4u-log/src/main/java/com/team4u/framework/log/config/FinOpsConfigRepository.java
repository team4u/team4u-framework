package com.team4u.framework.log.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 成本与性能（FinOps）配置仓库
 * <p>
 * 统一管理限流阈值、最大日志截断长度等 FinOps 相关配置。
 */
public class FinOpsConfigRepository {
    private static final Logger log = LoggerFactory.getLogger(FinOpsConfigRepository.class);
    private static final FinOpsConfigRepository INSTANCE = new FinOpsConfigRepository();
    private static final String CONFIG_KEY = "team4u.log.finops";

    private volatile FinOpsConfig config = FinOpsConfig.defaults();
    private ConfigDrivenRegistry<FinOpsConfig> registry;

    private FinOpsConfigRepository() {
    }

    /**
     * 获取仓库单例
     */
    public static FinOpsConfigRepository getInstance() {
        return INSTANCE;
    }

    /**
     * 组件自治：自己初始化自己的配置监听
     */
    public synchronized void init(ConfigManager configManager) {
        if (this.registry != null) {
            this.registry.destroy();
        }

        this.registry = new ConfigDrivenRegistry<>(configManager, CONFIG_KEY, json -> {
            try {
                FinOpsConfig nextConfig;
                if (json == null || json.trim().isEmpty()) {
                    nextConfig = FinOpsConfig.defaults();
                } else {
                    FinOpsConfig newConfig = JsonUtil.toBean(json, FinOpsConfig.class);
                    nextConfig = newConfig != null ? newConfig : FinOpsConfig.defaults();
                }
                this.config = nextConfig;
                return nextConfig;
            } catch (Exception e) {
                log.error("FinOpsConfigRepository|parseConfig|error|msg={}", e.getMessage());
                return this.config;
            }
        });
        FinOpsConfig loaded = this.registry.get(CONFIG_KEY);
        this.config = loaded != null ? loaded : FinOpsConfig.defaults();
    }

    /**
     * 重置仓库状态（用于测试环境隔离）
     * <p>
     * 无论当前配置来自配置中心还是测试注入，均恢复默认配置，
     * 以确保单元测试和嵌入式运行场景没有状态泄漏。
     */
    public synchronized void stop() {
        if (this.registry != null) {
            this.registry.destroy();
            this.registry = null;
        }
        this.config = FinOpsConfig.defaults();
    }

    /**
     * 获取当前的 FinOps 配置
     */
    public FinOpsConfig get() {
        return config;
    }

    /**
     * 直接替换当前配置快照（测试或嵌入式场景专用）
     */
    public void replace(FinOpsConfig config) {
        this.config = config != null ? config : FinOpsConfig.defaults();
    }

    /**
     * FinOps 实体类
     */
    @Data
    public static final class FinOpsConfig {
        private static final int DEFAULT_MAX_LOG_LENGTH = 5000;
        private static final int DEFAULT_MAX_STRING_LENGTH = 2000;
        private static final int DEFAULT_ERROR_LIMIT_PER_SECOND = 10;

        /**
         * 整体日志最大长度（单位：字符，兜底保护）
         */
        private final int maxLogLength;

        /**
         * 单个字符串字段的最大长度（防止单个大报文/文件撑爆内存）
         */
        private final int maxStringLength;

        /**
         * 每秒错误日志限流阈值
         */
        private final int errorLimitPerSecond;

        @JsonCreator
        public FinOpsConfig(
                @JsonProperty("maxLogLength") Integer maxLogLength,
                @JsonProperty("maxStringLength") Integer maxStringLength,
                @JsonProperty("errorLimitPerSecond") Integer errorLimitPerSecond) {
            this(
                    maxLogLength != null ? maxLogLength : DEFAULT_MAX_LOG_LENGTH,
                    maxStringLength != null ? maxStringLength : DEFAULT_MAX_STRING_LENGTH,
                    errorLimitPerSecond != null ? errorLimitPerSecond : DEFAULT_ERROR_LIMIT_PER_SECOND);
        }

        private FinOpsConfig(int maxLogLength, int maxStringLength, int errorLimitPerSecond) {
            this.maxLogLength = maxLogLength;
            this.maxStringLength = maxStringLength;
            this.errorLimitPerSecond = errorLimitPerSecond;
        }

        public static FinOpsConfig defaults() {
            return new FinOpsConfig(
                    DEFAULT_MAX_LOG_LENGTH,
                    DEFAULT_MAX_STRING_LENGTH,
                    DEFAULT_ERROR_LIMIT_PER_SECOND);
        }

        public FinOpsConfig withMaxLogLength(int maxLogLength) {
            return new FinOpsConfig(maxLogLength, this.maxStringLength, this.errorLimitPerSecond);
        }

        public FinOpsConfig withMaxStringLength(int maxStringLength) {
            return new FinOpsConfig(this.maxLogLength, maxStringLength, this.errorLimitPerSecond);
        }

        public FinOpsConfig withErrorLimitPerSecond(int errorLimitPerSecond) {
            return new FinOpsConfig(this.maxLogLength, this.maxStringLength, errorLimitPerSecond);
        }
    }
}