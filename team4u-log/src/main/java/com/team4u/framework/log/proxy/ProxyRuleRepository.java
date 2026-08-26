package com.team4u.framework.log.proxy;

import com.team4u.framework.base.util.TypeReference;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;
import com.team4u.framework.serializer.json.JsonUtil;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * 初始化配置规则并挂载配置监听。
     * <p>
     * 通过指定的配置管理器构建驱动注册表，实现配置规则组件自治。
     * 同步加载初始配置信息，并在底层建立监听以响应后续的实时重读。
     *
     * @param configManager 注入配置管理实例
     */
    public synchronized void init(ConfigManager configManager) {
        if (this.registry != null) {
            this.registry.destroy();
        }

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
                return null;
            }
        });
        // 触发首次拉取
        this.registry.get();
    }

    /**
     * 注销并释放当前所持有的缓存状态和配置监听关系。
     * <p>
     * 避免因重复调用或者应用重启引发监听泄露。
     * 执行完毕后，环境处于无规则匹配且无内存占用的干净状态。
     */
    public synchronized void stop() {
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
        Map<String, ProxyRule> rules = registry.get();
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
