package com.team4u.log.mask.config;

import com.team4u.log.mask.MaskType;

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
     * 规则缓存：ClassName -> (FieldName -> MaskType)
     */
    private volatile Map<String, Map<String, MaskType>> ruleCache = new HashMap<>();

    private MaskRuleRepository() {
        reset();
    }

    public static MaskRuleRepository getInstance() {
        return INSTANCE;
    }

    /**
     * 初始化默认规则
     */
    public void reset() {
        Map<String, Map<String, MaskType>> initialRules = new HashMap<>();

        Map<String, MaskType> userRules = new HashMap<>();
        userRules.put("mobile", MaskType.PHONE);
        initialRules.put("com.demo.ThirdPartyUser", userRules);

        Map<String, MaskType> mapRules = new HashMap<>();
        mapRules.put("password", MaskType.PASSWORD);
        mapRules.put("creditCard", MaskType.DYNAMIC);
        initialRules.put("java.util.HashMap", mapRules);
        initialRules.put("java.util.LinkedHashMap", mapRules);

        this.ruleCache = initialRules;
    }

    /**
     * 检索脱敏规则
     *
     * @param className 类名
     * @param fieldName 字段名
     * @return 匹配到的规则，若无则返回 null
     */
    public MaskType findRule(String className, String fieldName) {
        // 1. 优先尝试：精确匹配具体的类名 (优先级最高，允许特殊类覆盖全局规则)
        Map<String, MaskType> classRules = ruleCache.get(className);
        if (classRules != null && classRules.containsKey(fieldName)) {
            return classRules.get(fieldName);
        }

        // 2. 兜底尝试：全局字段匹配 (只要配置了 "*" 的规则)
        Map<String, MaskType> globalRules = ruleCache.get("*");
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
    public Map<String, MaskType> getClassRules(String className) {
        return ruleCache.get(className);
    }

    /**
     * 刷新脱敏规则
     *
     * @param newRules 新规则对
     */
    public void refreshRules(Map<String, Map<String, MaskType>> newRules) {
        this.ruleCache = newRules;
    }
}
