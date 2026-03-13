package com.team4u.framework.mask.config;

import com.team4u.framework.serializer.json.TypeReference;
import com.team4u.framework.serializer.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库
 * <p>
 * 维护第三方类或 Map 的脱敏规则，支持快速检索。
 */
public class MaskRuleRepository {
    private static final Logger log = LoggerFactory.getLogger(MaskRuleRepository.class);
    private static final MaskRuleRepository INSTANCE = new MaskRuleRepository();

    // 配置中心的 Key 变更为 team4u.mask.rules
    private static final String CONFIG_KEY = "team4u.mask.rules";

    /**
     * 手动注入规则缓存（主要用于测试场景）
     */
    private volatile Map<String, Map<String, String>> ruleCache = new HashMap<>();

    private ConfigDrivenRegistry<Map<String, Map<String, String>>> registry;

    private MaskRuleRepository() {
    }

    /**
     * 获取规则仓库单例实例
     *
     * @return MaskRuleRepository 实例
     */
    public static MaskRuleRepository getInstance() {
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
                Map<String, Map<String, String>> rules = JsonUtil.toBean(
                        json,
                        new TypeReference<Map<String, Map<String, String>>>() {
                        },
                        false);
                return rules != null ? rules : new HashMap<>();
            } catch (Exception e) {
                log.error("MaskRuleRepository|parseConfig|error|msg={}", e.getMessage());
                throw new IllegalArgumentException("Invalid mask rule config", e);
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
        this.ruleCache = new HashMap<>();
        if (this.registry != null) {
            this.registry.destroy();
            this.registry = null;
        }
    }

    /**
     * 直接注入规则缓存（测试专用，避免反射操作）
     *
     * @param rules className -> (fieldName -> maskPolicyKey)
     */
    public void setRuleCache(Map<String, Map<String, String>> rules) {
        this.ruleCache = rules != null ? rules : new HashMap<>();
    }

    /**
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则 Key，若无则返回 null
     */
    public String findRule(String className, String fieldName) {
        Map<String, Map<String, String>> rules = currentRules();

        // 精确匹配具体的类名（优先级最高，允许特殊类覆盖全局规则）
        Map<String, String> classRules = rules.get(className);
        if (classRules != null) {
            String classRule = classRules.get(fieldName);
            if (classRule != null) {
                return classRule;
            }
        }

        // 兜底匹配：全局通配符规则（配置了 "*" 的字段）
        Map<String, String> globalRules = rules.get("*");
        if (globalRules != null) {
            return globalRules.get(fieldName);
        }

        return null;
    }

    /**
     * 获取指定类的脱敏规则
     *
     * @param className 类名
     * @return 规则 Map
     */
    public Map<String, String> getClassRules(String className) {
        return currentRules().get(className);
    }

    private Map<String, Map<String, String>> currentRules() {
        if (registry != null) {
            Map<String, Map<String, String>> rules = registry.get(CONFIG_KEY);
            if (rules != null) {
                return rules;
            }
        }
        return ruleCache;
    }
}
