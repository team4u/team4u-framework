package com.team4u.framework.log.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.AbstractJsonConfigRepository;
import lombok.Data;

/**
 * 成本与性能（FinOps）配置仓库
 * <p>
 * 统一管理限流阈值、最大日志截断长度等 FinOps 相关配置。
 * init/stop/解析/热更新骨架收编自 {@link AbstractJsonConfigRepository}，
 * 统一降级语义：首次加载失败抛异常，热更新失败保留旧配置。
 */
public class FinOpsConfigRepository extends AbstractJsonConfigRepository<FinOpsConfigRepository.FinOpsConfig> {

    private static final FinOpsConfigRepository INSTANCE = new FinOpsConfigRepository();
    private static final String CONFIG_KEY = "team4u.log.finops";

    private FinOpsConfigRepository() {
    }

    /**
     * 获取仓库单例
     */
    public static FinOpsConfigRepository getInstance() {
        return INSTANCE;
    }

    @Override
    protected String configKey() {
        return CONFIG_KEY;
    }

    @Override
    protected TypeReference<FinOpsConfig> typeReference() {
        return new TypeReference<FinOpsConfig>() {
        };
    }

    @Override
    protected FinOpsConfig emptyConfig() {
        return FinOpsConfig.defaults();
    }

    @Override
    protected FinOpsConfig parseJson(String json) throws Exception {
        FinOpsConfig newConfig = com.team4u.framework.serializer.json.JsonUtil.toBean(json, FinOpsConfig.class);
        return newConfig != null ? newConfig : FinOpsConfig.defaults();
    }

    /**
     * 获取当前的 FinOps 配置
     */
    public FinOpsConfig get() {
        FinOpsConfig config = super.get();
        return config != null ? config : FinOpsConfig.defaults();
    }

    /**
     * 直接替换当前配置快照（测试或嵌入式场景专用）
     */
    public void replace(FinOpsConfig config) {
        replaceConfig(config);
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
