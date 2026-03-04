package com.team4u.log.mask.config;

import cn.hutool.json.JSONUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.log.Log;
import com.team4u.framework.config.core.ConfigManager;
import com.team4u.framework.config.core.support.ConfigDrivenRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库 (组件自治)
 * <p>
 * 维护第三方类或 Map 的脱敏规则，支持快速检索。
 */
public class MaskRuleRepository {
    private static final Log log = Log.get();
    private static final MaskRuleRepository INSTANCE = new MaskRuleRepository();

    // 配置中心的 Key
    private static final String CONFIG_KEY = "team4u.log.mask";

    /**
     * 规则缓存：ClassName -> (FieldName -> MaskPolicyKey)
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
                // 解析 JSON
                Map<String, Map<String, String>> rules = JSONUtil.toBean(
                        json,
                        new TypeReference<Map<String, Map<String, String>>>() {
                        },
                        false);
                // 原子性替换缓存
                this.ruleCache = rules != null ? rules : new HashMap<>();
                return this.ruleCache;
            } catch (Exception e) {
                log.error("MaskRuleRepository|parseConfig|error|msg={}", e.getMessage());
                // 解析失败时，保留之前的 ruleCache，或者返回空的 HashMap
                return this.ruleCache;
            }
        });

        // 触发首次拉取
        this.registry.get(CONFIG_KEY);
    }

    /**
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则 Key，若无则返回 null
     */
    public String findRule(String className, String fieldName) {
        // 1. 优先尝试：精确匹配具体的类名 (优先级最高，允许特殊类覆盖全局规则)
        Map<String, String> classRules = ruleCache.get(className);
        if (classRules != null) {
            String classRule = classRules.get(fieldName);
            if (classRule != null) {
                return classRule;
            }
        }

        // 2. 兜底尝试：全局字段匹配 (只要配置了 "*" 的规则)
        Map<String, String> globalRules = ruleCache.get("*");
        if (globalRules != null) {
            String globalRule = globalRules.get(fieldName);
            if (globalRule != null) {
                return globalRule;
            }
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
        return ruleCache.get(className);
    }
}
