package com.team4u.log.mask.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 脱敏规则仓库
 * <p>
 * 维护第三方类或 Map 的脱敏规则，支持快速检索。
 */
public class MaskRuleRepository {
    private static final MaskRuleRepository INSTANCE = new MaskRuleRepository();

    /**
     * 规则缓存：ClassName -> (FieldName -> MaskPolicyKey)
     */
    private volatile Map<String, Map<String, String>> ruleCache = new HashMap<>();

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
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则 Key，若无则返回 null
     */
    public String findRule(String className, String fieldName) {
        // 1. 优先尝试：精确匹配具体的类名 (优先级最高，允许特殊类覆盖全局规则)
        Map<String, String> classRules = ruleCache.get(className);
        if (classRules != null && classRules.containsKey(fieldName)) {
            return classRules.get(fieldName);
        }

        // 2. 兜底尝试：全局字段匹配 (只要配置了 "*" 的规则)
        Map<String, String> globalRules = ruleCache.get("*");
        if (globalRules != null && globalRules.containsKey(fieldName)) {
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
        return ruleCache.get(className);
    }

    /**
     * 刷新脱敏规则
     *
     * @param newRules 新规则对
     */
    public void refreshRules(Map<String, Map<String, String>> newRules) {
        this.ruleCache = newRules;
    }
}
