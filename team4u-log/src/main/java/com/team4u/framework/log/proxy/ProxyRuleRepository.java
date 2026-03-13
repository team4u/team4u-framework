package com.team4u.framework.log.proxy;

import com.team4u.framework.serializer.json.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代理规则仓库
 * <p>
 * 维护第三方类库的动态代理日志规则
 */
public class ProxyRuleRepository {
    private static final Logger log = LoggerFactory.getLogger(ProxyRuleRepository.class);
    private static final ProxyRuleRepository INSTANCE = new ProxyRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.log.proxy";

    private ConfigDrivenRegistry<Map<String, ProxyRule>> registry;

    private ProxyRuleRepository() {
    }

    /**
     * 获取仓库单例
     */
    public static ProxyRuleRepository getInstance() {
        return INSTANCE;
    }

    /**
     * 组件自治：自己初始化自己的配置监听
     */
    public void init(ConfigManager configManager) {
        this.registry = new ConfigDrivenRegistry<>(configManager, CONFIG_KEY, json -> {
            try {
                if (json == null || json.trim().isEmpty()) {
                    return new HashMap<>();
                }

                return JsonUtil.toBean(
                        json,
                        new TypeReference<Map<String, ProxyRule>>() {
                        },
                        false);
            } catch (Exception e) {
                log.error("ProxyRuleRepository|parseConfig|error|msg={}", e.getMessage());
                throw new IllegalArgumentException("Invalid proxy rule config", e);
            }
        });
        // 触发首次拉取
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 重置仓库状态（用于测试环境隔离）
     * <p>
     * 清空规则缓存并释放与 ConfigManager 的监听关系，
     * 确保下次 init() 时从干净状态重新初始化。
     */
    public void reset() {
        if (this.registry != null) {
            this.registry.destroy();
            this.registry = null;
        }
    }

    /**
     * 获取指定类的代理规则
     *
     * @param className 类名
     * @return 代理规则
     */
    public ProxyRule getRule(String className) {
        if (registry == null) {
            return null;
        }
        Map<String, ProxyRule> rules = registry.get(CONFIG_KEY);
        return rules == null ? null : rules.get(className);
    }

    /**
     * 代理规则实体
     */
    @Data
    public static class ProxyRule {
        /**
         * 允许拦截的方法名列表，配置 ["*"] 代表拦截所有 public 方法
         */
        private List<String> methods;

        /**
         * 慢日志阈值（毫秒）
         */
        private long slowThreshold = -1;

        /**
         * 需被视为业务异常而被降级打印的异常类名列表
         */
        private List<String> ignoreExceptions;
    }
}
