package com.team4u.framework.log.proxy;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.AbstractJsonConfigRepository;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 代理规则仓库
 * <p>
 * 维护第三方类库的动态代理日志规则。
 * init/stop/解析/热更新骨架收编自 {@link AbstractJsonConfigRepository}，
 * 统一降级语义：首次加载失败抛异常，热更新失败保留旧规则。
 */
public class ProxyRuleRepository extends AbstractJsonConfigRepository<Map<String, ProxyRuleRepository.ProxyRule>> {

    private static final ProxyRuleRepository INSTANCE = new ProxyRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.log.proxy";

    private ProxyRuleRepository() {
    }

    /**
     * 获取仓库单例
     */
    public static ProxyRuleRepository getInstance() {
        return INSTANCE;
    }

    @Override
    protected String configKey() {
        return CONFIG_KEY;
    }

    @Override
    protected TypeReference<Map<String, ProxyRule>> typeReference() {
        return new TypeReference<Map<String, ProxyRule>>() {
        };
    }

    @Override
    protected Map<String, ProxyRule> emptyConfig() {
        return new java.util.HashMap<>();
    }

    /**
     * 获取指定类的代理规则
     *
     * @param className 类名
     * @return 代理规则
     */
    public ProxyRule getRule(String className) {
        Map<String, ProxyRule> rules = get();
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
