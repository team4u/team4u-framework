package com.team4u.log.proxy;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
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
    private static final Log log = Log.get();
    private static final ProxyRuleRepository INSTANCE = new ProxyRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.log.proxy";

    // 规则缓存：ClassName -> ProxyRule
    private volatile Map<String, ProxyRule> ruleCache = new HashMap<>();

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
                // 解析 JSON: 手动遍历解决某些 Hutool 版本泛型解析丢失的问题
                JSONObject jsonObj = JSONUtil.parseObj(json);
                Map<String, ProxyRule> rules = new HashMap<>();
                for (Map.Entry<String, Object> entry : jsonObj.entrySet()) {
                    JSONObject ruleObj = (JSONObject) entry.getValue();
                    rules.put(entry.getKey(), JSONUtil.toBean(ruleObj, ProxyRule.class));
                }

                // 原子替换缓存
                this.ruleCache = rules;
                return this.ruleCache;
            } catch (Exception e) {
                log.error("ProxyRuleRepository|parseConfig|error|msg={}", e.getMessage());
                // 解析失败时保留上次正常配置
                return this.ruleCache;
            }
        });
        // 触发首次拉取
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 获取指定类的代理规则
     *
     * @param className 类名
     * @return 代理规则
     */
    public ProxyRule getRule(String className) {
        // 确保注册表已初始化缓存（处理首次加载或热重载场景）
        if (registry != null) {
            Map<String, ProxyRule> rules = registry.get(CONFIG_KEY);
            if (rules != null) {
                // 从注册表返回最新的规则映射
                return rules.get(className);
            }
        }
        return ruleCache.get(className);
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
